package com.aura.livewall.render

import com.aura.livewall.core.*
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import kotlin.math.*
import kotlin.random.Random

/**
 * Группы пружинных костей. Подбор по имени узла в .glb (регэкспы можно расширять под свои риги).
 * stiffness — 1/с², drag — демпфирование на шаг (120 Гц), gravity — м/с², maxAngle — предел отклонения.
 */
enum class SpringGroup(pattern: String, val stiffness: Float, val drag: Float, val gravity: Float, maxAngleDeg: Float) {
    HAIR("hair|ponytail|twintail|bang|ahoge|ribbon|tail|(?<![a-z])ears?(?![a-z])|ear[_.\\-]?[lr]", 220f, 0.06f, 2.0f, 85f),
    CLOTH("skirt|cloth|cape|cloak|sleeve|scarf|frill|dress", 300f, 0.08f, 1.5f, 70f),
    FACE("cheek|jowl|chin_?fat|face_?soft", 900f, 0.10f, 0f, 25f),
    SOFT("bust|breast|boob|mune|oppai|chest[_.\\-]?[lr](?![a-z])|[lr][_.\\-]?chest|belly|butt|hip_?soft|thigh_?soft", 520f, 0.05f, 0.8f, 40f);

    val regex = Regex(pattern)
    val cosMax = cos(Math.toRadians(maxAngleDeg.toDouble())).toFloat()
    val sinMax = sin(Math.toRadians(maxAngleDeg.toDouble())).toFloat()

    companion object {
        private val EXCLUDE = Regex("^(spine|neck|head|hips|root)|thumb|index|middle|ring|little|finger")
        fun match(name: String): SpringGroup? =
            if (EXCLUDE.containsMatchIn(name)) null else entries.firstOrNull { it.regex.containsMatchIn(name) }
    }
}

/** Verlet spring bones в мировом пространстве + "оттягивание" пальцем (силовое поле вокруг касания). */
class SpringBones(private val engine: Engine, private val asset: FilamentAsset) {

    private class Bone(
        val inst: Int, val parent: Int, val g: SpringGroup,
        val rPos: V3, val rRot: Q, val rScale: V3, val axis: V3, val len: Float, val depth: Int
    ) {
        var parentBone: Bone? = null
        var cur = V3.ZERO; var prev = V3.ZERO
        var wPos = V3.ZERO; var wRot = Q.IDENT; var wScale = 1f
        var lRot = Q.IDENT
        var pPos = V3.ZERO; var pRot = Q.IDENT; var pScale = 1f   // мир родителя, если он не симулируется
    }

    private companion object {
        const val H = 1f / 120f
        const val PULL_R = 0.22f
        val GRAV = V3(0f, -1f, 0f)
    }

    private val tm = engine.transformManager
    private val bones = ArrayList<Bone>()
    private val tmp = FloatArray(16)
    private var acc = 0f
    val count get() = bones.size

    fun build() {
        val matched = HashMap<Int, SpringGroup>()
        for (e in asset.entities) {
            if (!tm.hasComponent(e)) continue
            val n = asset.getName(e)?.lowercase() ?: continue
            SpringGroup.match(n)?.let { matched[e] = it }
        }
        val firstChild = HashMap<Int, Int>()
        for (e in matched.keys) {
            val p = tm.getParent(tm.getInstance(e))
            if (p != 0 && matched.containsKey(p) && !firstChild.containsKey(p)) firstChild[p] = e
        }
        val byEntity = HashMap<Int, Bone>()
        for ((e, g) in matched) {
            val inst = tm.getInstance(e)
            tm.getTransform(inst, tmp)
            val (t, q, s) = Xf.decompose(tmp)
            val child = firstChild[e]
            val axis: V3; val len: Float
            if (child != null) {
                tm.getTransform(tm.getInstance(child), tmp)
                val ct = Xf.decompose(tmp).first
                axis = ct.norm(); len = max(ct.len(), 0.005f)
            } else {
                len = max(t.len(), 0.04f)
                axis = q.conj().rotate(if (t.len() < 1e-5f) V3(0f, 1f, 0f) else t.norm())
            }
            var depth = 0
            var p = tm.getParent(inst)
            while (p != 0 && matched.containsKey(p)) { depth++; p = tm.getParent(tm.getInstance(p)) }
            val b = Bone(inst, tm.getParent(inst), g, t, q, s, axis, len, depth)
            bones += b; byEntity[e] = b
        }
        bones.sortBy { it.depth }
        for (b in bones) b.parentBone = byEntity[b.parent]
    }

    private fun refreshRoots() {
        for (b in bones) {
            if (b.parentBone != null) continue
            if (b.parent == 0) { b.pPos = V3.ZERO; b.pRot = Q.IDENT; b.pScale = 1f; continue }
            tm.getWorldTransform(tm.getInstance(b.parent), tmp)
            val (p, q, s) = Xf.decompose(tmp)
            b.pPos = p; b.pRot = q; b.pScale = s.x
        }
    }

    /** Поставить все хвосты в позу покоя (после смены модели/трансформа). */
    fun reset() {
        refreshRoots()
        for (b in bones) {
            val pb = b.parentBone
            val pPos = pb?.wPos ?: b.pPos; val pRot = pb?.wRot ?: b.pRot; val ps = pb?.wScale ?: b.pScale
            val head = pPos + pRot.rotate(b.rPos * ps)
            val restRot = pRot * b.rRot
            val scale = ps * b.rScale.x
            b.cur = head + restRot.rotate(b.axis) * (b.len * scale)
            b.prev = b.cur
            b.wPos = head; b.wRot = restRot; b.wScale = scale; b.lRot = b.rRot
        }
        acc = 0f
    }

    /** Мгновенный импульс всем костям (реакция на тап / действия, заданные LLM). */
    fun kick(strength: Float) {
        val v = V3((Random.nextFloat() - 0.5f) * 0.006f * strength, 0.004f * strength, (Random.nextFloat() - 0.5f) * 0.004f * strength)
        for (b in bones) b.prev = b.prev - v
    }

    fun step(dt: Float, stiffMul: Float, touch: V3?) {
        if (bones.isEmpty()) return
        refreshRoots()
        acc += dt
        var n = 0
        while (acc >= H && n < 4) { sim(stiffMul, touch); acc -= H; n++ }
        if (acc > H) acc = 0f
        tm.openLocalTransformTransaction()
        for (b in bones) { Xf.compose(b.rPos, b.lRot, b.rScale, tmp); tm.setTransform(b.inst, tmp) }
        tm.commitLocalTransformTransaction()
    }

    private fun sim(stiff: Float, touch: V3?) {
        for (b in bones) {
            val pb = b.parentBone
            val pPos = pb?.wPos ?: b.pPos; val pRot = pb?.wRot ?: b.pRot; val ps = pb?.wScale ?: b.pScale
            val head = pPos + pRot.rotate(b.rPos * ps)
            val restRot = pRot * b.rRot
            val restDir = restRot.rotate(b.axis)
            val scale = ps * b.rScale.x
            val tailLen = b.len * scale
            val g = b.g

            val restTail = head + restDir * tailLen
            var next = b.cur + (b.cur - b.prev) * (1f - g.drag) +
                ((restTail - b.cur) * (g.stiffness * stiff) + GRAV * g.gravity) * (H * H)

            if (touch != null) {                      // оттягивание: поле притяжения вокруг пальца
                val dx = touch.x - b.cur.x; val dy = touch.y - b.cur.y
                val d = sqrt(dx * dx + dy * dy)
                if (d < PULL_R) { val w = 1f - d / PULL_R; next = next + (V3(touch.x, touch.y, next.z) - next) * (0.12f * w * w) }
            }

            var dir = (next - head).norm()
            val cosA = dir.dot(restDir)
            if (cosA < g.cosMax) {                     // предел угла
                val perp = dir - restDir * cosA
                val pl = perp.len()
                dir = if (pl < 1e-6f) restDir else restDir * g.cosMax + perp * (g.sinMax / pl)
            }
            next = head + dir * tailLen                // постоянная длина кости
            b.prev = b.cur; b.cur = next

            val rot = Q.fromTo(restDir, dir) * restRot
            b.wPos = head; b.wRot = rot; b.wScale = scale
            b.lRot = pRot.conj() * rot
        }
    }
}
