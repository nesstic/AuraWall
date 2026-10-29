package com.aura.livewall.render

import com.aura.livewall.core.*
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import kotlin.math.*
import kotlin.random.Random

/** Управление персонажем: idle/действия из glTF-анимаций, мимика (morph targets), взгляд, моргание, речь. */
class CharacterController(
    private val engine: Engine,
    private val asset: FilamentAsset,
    private val kick: (Float) -> Unit
) {
    private companion object {
        // роли: 1 smile 2 angry 3 sad 4 surprised 5 blush 6 blink 7 mouth
        val RX = listOf(
            "smile|happy|joy|laugh|笑|にこ", "angry|anger|mad|怒", "sad|sorrow|cry|悲|哀",
            "surpris|驚|おどろ", "blush|shy|照れ", "blink|eyes?_?clos|まばたき",
            "^(a|aa|mouth_?a|mouth_?open|vrc\\.v_aa|あ)$"
        ).map { Regex(it) }
        fun roleOf(n: String) = RX.indexOfFirst { it.containsMatchIn(n) } + 1
    }

    private class Morph(val inst: Int, val roles: IntArray, val w: FloatArray)

    private val animator = asset.instance.animator
    private val tm = engine.transformManager
    private val rm = engine.renderableManager
    private val tmp = FloatArray(16)
    private val morphs = ArrayList<Morph>()

    private var t = 0f
    private var idle = -1
    private var cur = -1
    private var curStart = 0f
    private var headInst = 0
    private var headRest = Q.IDENT
    private var yaw = 0f
    private var pitch = 0f
    private var proc = ""
    private var procStart = 0f
    private var emotion = "neutral"
    private var speakUntil = 0f
    private var blinkAt = 2f
    private var blink = -1f
    private var blinkV = 0f

    init {
        val idleRx = Regex("idle|stand|wait|breath|待機")
        for (i in 0 until animator.animationCount)
            if (idle < 0 && idleRx.containsMatchIn(animator.getAnimationName(i).lowercase())) idle = i

        val headRx = Regex("(^|[:_.\\-])head$")
        for (e in asset.entities) {
            if (headInst == 0 && tm.hasComponent(e) && headRx.containsMatchIn(asset.getName(e)?.lowercase() ?: "")) {
                headInst = tm.getInstance(e)
                tm.getTransform(headInst, tmp)
                headRest = Xf.decompose(tmp).second
            }
        }
        for (e in asset.renderableEntities) {
            val names = asset.getMorphTargetNames(e)
            if (names.isEmpty()) continue
            val roles = IntArray(names.size) { roleOf(names[it].lowercase()) }
            if (roles.any { it != 0 }) morphs += Morph(rm.getInstance(e), roles, FloatArray(names.size))
        }
    }

    fun setEmotion(e: String) { emotion = e.lowercase().trim() }
    fun speak(ms: Long) { speakUntil = t + ms / 1000f }

    /** Действие от LLM: сначала ищем glTF-анимацию с таким именем, иначе процедурно. */
    fun action(name: String) {
        val n = name.lowercase().trim()
        if (n.isEmpty() || n == "none") return
        for (i in 0 until animator.animationCount) {
            if (animator.getAnimationName(i).lowercase().contains(n)) { cur = i; curStart = t; return }
        }
        proc = n; procStart = t
        if (n == "jump" || n == "wave" || n == "shake") kick(1f)
    }

    fun update(dt: Float, lx: Float, ly: Float) {
        t += dt
        var animating = false
        if (cur >= 0) {
            val a = t - curStart
            if (a >= animator.getAnimationDuration(cur)) cur = -1
            else { animator.applyAnimation(cur, a); animating = true }
        }
        if (!animating && idle >= 0) {
            animator.applyAnimation(idle, t % max(0.05f, animator.getAnimationDuration(idle)))
            animating = true
        }
        updateHead(dt, lx, ly, animating)
        updateMorphs(dt)
    }

    private fun updateHead(dt: Float, lx: Float, ly: Float, animating: Boolean) {
        if (headInst == 0) return
        var ty = lx * 0.35f
        var tp = -ly * 0.2f
        if (proc.isNotEmpty()) {
            val a = t - procStart
            if (a > 1.6f) proc = "" else {
                val env = sin(PI.toFloat() * a / 1.6f)
                if (proc == "nod") tp += sin(a * 10f) * 0.25f * env
                if (proc == "shake") ty += sin(a * 12f) * 0.4f * env
            }
        }
        val k = min(1f, dt * 8f)
        yaw += (ty - yaw) * k; pitch += (tp - pitch) * k
        if (abs(yaw) < 1e-3f && abs(pitch) < 1e-3f && proc.isEmpty()) return
        tm.getTransform(headInst, tmp)
        val (p, q, s) = Xf.decompose(tmp)
        val base = if (animating) q else headRest
        Xf.compose(p, base * Q.axisAngle(V3(0f, 1f, 0f), yaw) * Q.axisAngle(V3(1f, 0f, 0f), pitch), s, tmp)
        tm.setTransform(headInst, tmp)
    }

    private fun updateMorphs(dt: Float) {
        if (morphs.isEmpty()) return
        if (blink < 0f && t >= blinkAt) blink = 0f
        if (blink >= 0f) {
            blink += dt
            blinkV = max(0f, 1f - abs(blink / 0.08f - 1f))
            if (blink > 0.16f) { blink = -1f; blinkV = 0f; blinkAt = t + 2.5f + Random.nextFloat() * 3.5f }
        }
        val mouth = if (t < speakUntil) abs(sin(t * 13f)) * 0.7f else 0f
        val k = min(1f, dt * 12f)
        for (m in morphs) {
            for (i in m.roles.indices) {
                val tgt = when (m.roles[i]) {
                    1 -> if (emotion == "happy") 1f else if (emotion == "shy") 0.4f else 0f
                    2 -> if (emotion == "angry") 1f else 0f
                    3 -> if (emotion == "sad") 1f else 0f
                    4 -> if (emotion == "surprised") 1f else 0f
                    5 -> if (emotion == "shy") 1f else 0f
                    6 -> blinkV
                    7 -> mouth
                    else -> 0f
                }
                m.w[i] += (tgt - m.w[i]) * (if (m.roles[i] == 6) 1f else k)
            }
            rm.setMorphWeights(m.inst, m.w, 0)
        }
    }
}
