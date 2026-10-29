package com.aura.livewall.core

import kotlin.math.*

class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun norm(): V3 { val l = len(); return if (l < 1e-8f) V3(0f, 1f, 0f) else this * (1f / l) }
    companion object { val ZERO = V3(0f, 0f, 0f); val ONE = V3(1f, 1f, 1f) }
}

class Q(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(o: Q) = Q(
        w * o.x + o.w * x + y * o.z - z * o.y,
        w * o.y + o.w * y + z * o.x - x * o.z,
        w * o.z + o.w * z + x * o.y - y * o.x,
        w * o.w - x * o.x - y * o.y - z * o.z
    )
    fun conj() = Q(-x, -y, -z, w)
    fun rotate(v: V3): V3 { val u = V3(x, y, z); val t = u.cross(v) * 2f; return v + t * w + u.cross(t) }
    fun norm(): Q { val l = sqrt(x * x + y * y + z * z + w * w); return if (l < 1e-8f) IDENT else Q(x / l, y / l, z / l, w / l) }

    companion object {
        val IDENT = Q(0f, 0f, 0f, 1f)
        fun axisAngle(a: V3, r: Float): Q { val s = sin(r / 2f); return Q(a.x * s, a.y * s, a.z * s, cos(r / 2f)) }
        /** Кратчайший поворот между единичными векторами. */
        fun fromTo(a: V3, b: V3): Q {
            val d = a.dot(b)
            if (d > 0.99999f) return IDENT
            if (d < -0.99999f) {
                var ax = V3(1f, 0f, 0f).cross(a)
                if (ax.len() < 1e-3f) ax = V3(0f, 1f, 0f).cross(a)
                ax = ax.norm(); return Q(ax.x, ax.y, ax.z, 0f)
            }
            val c = a.cross(b); return Q(c.x, c.y, c.z, 1f + d).norm()
        }
    }
}

/** Column-major 4x4 <-> TRS (формат Filament TransformManager). */
object Xf {
    fun decompose(m: FloatArray): Triple<V3, Q, V3> {
        val sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]).coerceAtLeast(1e-6f)
        val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]).coerceAtLeast(1e-6f)
        val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]).coerceAtLeast(1e-6f)
        val r00 = m[0] / sx; val r10 = m[1] / sx; val r20 = m[2] / sx
        val r01 = m[4] / sy; val r11 = m[5] / sy; val r21 = m[6] / sy
        val r02 = m[8] / sz; val r12 = m[9] / sz; val r22 = m[10] / sz
        val tr = r00 + r11 + r22
        val q = when {
            tr > 0f -> { val s = sqrt(tr + 1f) * 2f; Q((r21 - r12) / s, (r02 - r20) / s, (r10 - r01) / s, s / 4f) }
            r00 > r11 && r00 > r22 -> { val s = sqrt(1f + r00 - r11 - r22) * 2f; Q(s / 4f, (r01 + r10) / s, (r02 + r20) / s, (r21 - r12) / s) }
            r11 > r22 -> { val s = sqrt(1f + r11 - r00 - r22) * 2f; Q((r01 + r10) / s, s / 4f, (r12 + r21) / s, (r02 - r20) / s) }
            else -> { val s = sqrt(1f + r22 - r00 - r11) * 2f; Q((r02 + r20) / s, (r12 + r21) / s, s / 4f, (r10 - r01) / s) }
        }.norm()
        return Triple(V3(m[12], m[13], m[14]), q, V3(sx, sy, sz))
    }

    fun compose(t: V3, q: Q, s: V3, o: FloatArray) {
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        o[0] = (1 - 2 * (yy + zz)) * s.x; o[1] = 2 * (xy + wz) * s.x; o[2] = 2 * (xz - wy) * s.x; o[3] = 0f
        o[4] = 2 * (xy - wz) * s.y; o[5] = (1 - 2 * (xx + zz)) * s.y; o[6] = 2 * (yz + wx) * s.y; o[7] = 0f
        o[8] = 2 * (xz + wy) * s.z; o[9] = 2 * (yz - wx) * s.z; o[10] = (1 - 2 * (xx + yy)) * s.z; o[11] = 0f
        o[12] = t.x; o[13] = t.y; o[14] = t.z; o[15] = 1f
    }
}
