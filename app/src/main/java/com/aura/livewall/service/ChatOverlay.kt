package com.aura.livewall.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*

/** Оверлей поверх лаунчера: короткий "пузырь мысли" и интерактивный диалог (появляется плавно). */
class ChatOverlay(base: Context) {
    private val ctx = ContextThemeWrapper(base, android.R.style.Theme_Material_NoActionBar)
    private val wm = base.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val dm get() = ctx.resources.displayMetrics
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private var bubble: View? = null
    private var dlg: View? = null
    private var reply: TextView? = null
    private var typing: Runnable? = null
    private val hideBubble = Runnable { dismissBubble() }

    private fun dp(v: Int) = (v * dm.density).toInt()
    fun canDraw() = Settings.canDrawOverlays(ctx)
    val dialogOpen get() = dlg != null

    private fun panelBg() = GradientDrawable().apply {
        setColor(0xEE0B0B10.toInt()); cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x44FFFFFF)
    }

    fun bubble(text: String, ms: Long = 6500) {
        if (!canDraw() || dlg != null || text.isBlank()) return
        dismissBubble()
        val tv = TextView(ctx).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(16), dp(10), dp(16), dp(10)); maxWidth = (dm.widthPixels * 0.78f).toInt()
            background = panelBg(); alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (dm.heightPixels * 0.14f).toInt() }
        if (runCatching { wm.addView(tv, lp) }.isFailure) return
        bubble = tv
        tv.animate().alpha(1f).setDuration(350).start()
        ui.removeCallbacks(hideBubble); ui.postDelayed(hideBubble, ms)
    }

    private fun dismissBubble() {
        val v = bubble ?: return
        bubble = null
        v.animate().alpha(0f).setDuration(300).withEndAction { runCatching { wm.removeView(v) } }.start()
    }

    /** Интерактивное окно ввода/диалога поверх модели. */
    fun dialog(title: String, onSend: (String) -> Unit) {
        if (!canDraw() || dlg != null) return
        dismissBubble()
        val root = FrameLayout(ctx).apply { setBackgroundColor(0x88000000.toInt()); alpha = 0f; setOnClickListener { closeDialog() } }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = panelBg(); isClickable = true
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        val head = TextView(ctx).apply { text = title; setTextColor(0xFFB69CFF.toInt()); textSize = 13f }
        val rep = TextView(ctx).apply { text = "…"; setTextColor(Color.WHITE); textSize = 16f; minLines = 2; setPadding(0, dp(8), 0, dp(12)) }
        val input = EditText(ctx).apply {
            hint = "Напиши…"; setHintTextColor(0x88FFFFFF.toInt()); setTextColor(Color.WHITE)
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        fun send() {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { input.setText(""); rep.text = "…"; onSend(t) }
        }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(Button(ctx).apply { text = "→"; setOnClickListener { send() } })
        row.addView(Button(ctx).apply { text = "✕"; setOnClickListener { closeDialog() } })
        panel.addView(head); panel.addView(rep); panel.addView(row)
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(20), 0, dp(20), 0)
        })

        val lp = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT, type, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE }
        if (runCatching { wm.addView(root, lp) }.isFailure) return
        dlg = root; reply = rep
        panel.translationY = dp(30).toFloat()
        root.animate().alpha(1f).setDuration(280).start()
        panel.animate().translationY(0f).setDuration(280).start()
        input.post { input.requestFocus() }
    }

    fun setReply(text: String) {
        val tv = reply ?: return
        typing?.let { ui.removeCallbacks(it) }
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i = (i + 2).coerceAtMost(text.length)
                tv.text = text.substring(0, i)
                if (i < text.length) ui.postDelayed(this, 28)
            }
        }
        typing = r; ui.post(r)
    }

    fun closeDialog() {
        val v = dlg ?: return
        dlg = null; reply = null
        v.animate().alpha(0f).setDuration(220).withEndAction { runCatching { wm.removeView(v) } }.start()
    }

    fun dismissAll() { closeDialog(); dismissBubble() }
}
