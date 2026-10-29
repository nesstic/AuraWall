package com.aura.livewall.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.aura.livewall.ai.LlmBrain
import com.aura.livewall.ai.Reaction
import com.aura.livewall.data.*
import com.aura.livewall.render.WallpaperRenderer
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

enum class Trigger { CHARGING, UNPLUGGED, LOW_BATTERY, BATTERY_OK }

class AuraWallpaperService : WallpaperService() {

    private lateinit var prefs: Prefs
    private lateinit var db: AuraDb
    private lateinit var brain: LlmBrain
    private lateinit var overlay: ChatOverlay
    private val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var active: AuraEngine? = null

    // Заряд / низкий заряд (+ смена даты для суточной очистки истории). Регистрация — динамическая.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> active?.onTrigger(Trigger.CHARGING)
                Intent.ACTION_POWER_DISCONNECTED -> active?.onTrigger(Trigger.UNPLUGGED)
                Intent.ACTION_BATTERY_LOW -> active?.onTrigger(Trigger.LOW_BATTERY)
                Intent.ACTION_BATTERY_OKAY -> active?.onTrigger(Trigger.BATTERY_OK)
                Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED -> svcScope.launch(Dispatchers.IO) { db.purgeOld() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this); db = AuraDb.get(this); brain = LlmBrain.get(this); overlay = ChatOverlay(this)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW); addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        svcScope.launch(Dispatchers.IO) { db.purgeOld() }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        overlay.dismissAll(); brain.close(); svcScope.cancel()
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = AuraEngine()

    inner class AuraEngine : Engine() {
        private var gl: WallpaperRenderer? = null
        private val ui = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var shown = false
        private var current: CharacterEntity? = null
        private var shownId = -1L

        private var touching = false
        private var ax = 0f; private var ay = 0f; private var downAt = 0L
        private val slop by lazy { ViewConfiguration.get(this@AuraWallpaperService).scaledTouchSlop * 2f }
        private val kg by lazy { this@AuraWallpaperService.getSystemService(KeyguardManager::class.java) }

        private val longPress = Runnable { onLongPress() }
        private val thoughtTask = object : Runnable {
            override fun run() { think(); ui.postDelayed(this, prefs.thoughtMin.coerceAtLeast(1) * 60_000L) }
        }
        private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "cid") loadCharacter() else gl?.applyPrefs()
        }

        override fun onCreate(sh: SurfaceHolder) {
            super.onCreate(sh)
            setTouchEventsEnabled(true)
            gl = WallpaperRenderer(prefs)
            prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        }

        override fun onSurfaceCreated(h: SurfaceHolder) { super.onSurfaceCreated(h); gl?.attach(h.surface); loadCharacter() }
        override fun onSurfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { super.onSurfaceChanged(h, f, w, hh); gl?.resize(w, hh) }
        override fun onSurfaceDestroyed(h: SurfaceHolder) { gl?.detach(); super.onSurfaceDestroyed(h) }

        override fun onOffsetsChanged(xO: Float, yO: Float, xS: Float, yS: Float, xP: Int, yP: Int) { gl?.setScroll(xO) }

        override fun onVisibilityChanged(visible: Boolean) {
            shown = visible
            if (visible) {
                active = this; gl?.setRunning(true); loadCharacter()
                ui.removeCallbacks(thoughtTask); ui.postDelayed(thoughtTask, prefs.thoughtMin.coerceAtLeast(1) * 60_000L)
            } else {
                if (active === this) active = null
                gl?.setRunning(false); endTouch(); overlay.dismissAll(); ui.removeCallbacks(thoughtTask)
            }
        }

        override fun onDestroy() {
            ui.removeCallbacksAndMessages(null); scope.cancel()
            prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
            if (active === this) active = null
            gl?.release(); gl = null
            super.onDestroy()
        }

        // ---------- touch: только когда обои видны (лаунчер), не на lock screen ----------
        override fun onTouchEvent(e: MotionEvent) {
            val r = gl ?: return
            if (!shown || kg.isKeyguardLocked) { endTouch(); return }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (r.isOnModel(e.x, e.y)) {
                    touching = true; ax = e.x; ay = e.y; downAt = System.currentTimeMillis()
                    r.touch(e.x, e.y); armLongPress()
                }
                MotionEvent.ACTION_MOVE -> if (touching) {
                    r.touch(e.x, e.y)
                    if (hypot(e.x - ax, e.y - ay) > slop) { ax = e.x; ay = e.y; armLongPress() }   // 5 с — в ОДНОЙ точке
                }
                MotionEvent.ACTION_UP -> {
                    if (touching && System.currentTimeMillis() - downAt < 220 && hypot(e.x - ax, e.y - ay) < slop) r.kick()
                    endTouch()
                }
                MotionEvent.ACTION_CANCEL -> endTouch()
            }
        }

        private fun armLongPress() { ui.removeCallbacks(longPress); ui.postDelayed(longPress, 5_000L) }
        private fun endTouch() { touching = false; ui.removeCallbacks(longPress); gl?.touchUp() }

        private fun onLongPress() {
            if (!shown || !touching) return
            if (!overlay.canDraw()) {
                Toast.makeText(this@AuraWallpaperService, "Выдай «Поверх других окон» в приложении Aura Wall", Toast.LENGTH_LONG).show()
                return
            }
            gl?.react(Reaction("", "surprised", "none"))
            overlay.dialog(current?.name ?: "") { converse(it) }
        }

        // ---------- character ----------
        private fun loadCharacter() {
            val id = prefs.characterId
            scope.launch {
                val c = withContext(Dispatchers.IO) { db.characters().get(id) ?: db.characters().first() } ?: return@launch
                current = c
                if (c.id == shownId) return@launch
                withContext(Dispatchers.IO) { runCatching { ModelCache.get(c.modelPath) } }   // чтение файла вне main
                gl?.showModel(c.modelPath)
                shownId = c.id
            }
        }

        private fun deliver(r: Reaction) {
            gl?.react(r)
            if (r.say.isNotBlank()) overlay.bubble(r.say)
        }

        // ---------- dialog ----------
        private fun converse(text: String) {
            scope.launch {
                val c = current ?: return@launch
                val hist = withContext(Dispatchers.IO) { db.history().recent(c.id, 8) }
                withContext(Dispatchers.IO) { db.history().insert(HistoryEntity(characterId = c.id, role = "user", text = text)) }
                val r = (if (prefs.llmEnabled) brain.react(c, text, hist) else null)
                    ?: Reaction(
                        if (brain.available()) "…" else "LLM-модель не найдена. Положи .task в ${brain.dir().path}",
                        "neutral", "none"
                    )
                withContext(Dispatchers.IO) { db.history().insert(HistoryEntity(characterId = c.id, role = "assistant", text = r.say)) }
                overlay.setReply(r.say); gl?.react(r)
            }
        }

        // ---------- system triggers (зависят от характера) ----------
        fun onTrigger(kind: Trigger) {
            if (!shown) return
            val c = current ?: return
            val (hint, fallback, emo) = when (kind) {
                Trigger.CHARGING -> Triple(c.chargingPrompt.ifBlank { "Пользователь подключил зарядку." }, c.fallbackCharging, "happy")
                Trigger.LOW_BATTERY -> Triple(c.lowBatteryPrompt.ifBlank { "Заряд батареи почти закончился." }, c.fallbackLowBattery, "sad")
                Trigger.UNPLUGGED -> Triple("Зарядку отключили.", "", "neutral")
                Trigger.BATTERY_OK -> Triple("Заряд снова в норме.", "", "happy")
            }
            scope.launch {
                val r = (if (prefs.llmEnabled) brain.react(c, "[СОБЫТИЕ] $hint", emptyList()) else null)
                    ?: Reaction(fallback.ifBlank { hint }, emo, "none")
                deliver(r)
            }
        }

        private fun think() {
            if (!shown || !prefs.llmEnabled || overlay.dialogOpen || !brain.available()) return
            val c = current ?: return
            val bat = this@AuraWallpaperService.getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            scope.launch {
                brain.react(c, "[МЫСЛЬ] Время $now, заряд $bat%. Скажи короткую мысль вслух.", emptyList())?.let { deliver(it) }
            }
        }
    }
}
