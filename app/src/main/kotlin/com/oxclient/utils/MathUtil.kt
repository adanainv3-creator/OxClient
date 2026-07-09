package com.oxclient.utils

import kotlin.math.*

object MathUtil {

    fun dist2(x1: Float, z1: Float, x2: Float, z2: Float): Float {
        val dx = x1 - x2; val dz = z1 - z2
        return sqrt(dx * dx + dz * dz)
    }

    fun dist3(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2; val dz = z1 - z2
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun dist3sq(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2; val dz = z1 - z2
        return dx * dx + dy * dy + dz * dz
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun clamp(v: Float, min: Float, max: Float): Float = v.coerceIn(min, max)

    fun randomRange(lo: Float, hi: Float): Float =
        lo + (Math.random() * (hi - lo)).toFloat()

    fun randomInt(lo: Int, hi: Int): Int =
        (lo..hi).random()

    fun cpsToDelayMs(cpsLo: Int, cpsHi: Int): Long {
        val lo = cpsLo.coerceIn(1, 20)
        val hi = cpsHi.coerceIn(lo, 20)
        return 1000L / (lo..hi).random()
    }

    fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun worldToScreen(
        wx: Float, wy: Float, wz: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        fov: Float = 70f
    ): Pair<Float, Float>? {
        // ⚠️ FIX: X ekseni ayna (mirror) hatası — Bedrock look-vector'ünde
        // Fx = -sin(yaw)*cos(pitch) olduğu için dx'in işareti ters alınmalı.
        // yaw=90 (batıya bakış) iken -X yönündeki (batıdaki) hedefin rz'si
        // pozitif (önde) çıkmalıydı; eski kodda dx = wx - selfX olduğunda
        // hedef "arkada" hesaplanıyor, tracer'lar doğu/batı yönlerinde
        // ters/kaymış çıkıyordu. dy ve dz işaretleri zaten doğruydu.
        val dx = selfX - wx
        val dy = wy - selfY
        val dz = wz - selfZ

        val yawR   = Math.toRadians(yaw.toDouble())
        val pitchR = Math.toRadians(pitch.toDouble())

        val sinY = sin(yawR); val cosY = cos(yawR)
        val sinP = sin(pitchR); val cosP = cos(pitchR)

        val rx = dx * cosY - dz * sinY
        val ry = dx * sinY * sinP + dy * cosP + dz * cosY * sinP
        val rz = dx * sinY * cosP - dy * sinP + dz * cosY * cosP

        if (rz <= 0.05f) return null

        val aspect = screenW.toFloat() / screenH.toFloat()
        val tanHalfFovY = tan(Math.toRadians(fov / 2.0))
        val tanHalfFovX = tanHalfFovY * aspect

        val sx = ((rx / (rz * tanHalfFovX)) * (screenW / 2f) + screenW / 2f).toFloat()
        val sy = ((-ry / (rz * tanHalfFovY)) * (screenH / 2f) + screenH / 2f).toFloat()

        return Pair(sx, sy)
    }
}