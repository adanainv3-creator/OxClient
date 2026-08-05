package com.rubidiumclient.utils

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

    // FIX: bu fonksiyon 1..20 aralığına clamp ediyordu ama KillAura/KillAuraPro
    // CPS Min/Max ayarları 1..30 aralığında. 28-30 CPS ayarlasan bile burası
    // sessizce 20'ye düşürüyordu — gerçek saldırı hızın hiçbir zaman ayarladığın
    // değere ulaşmıyordu. Üst sınır artık modüllerle aynı: 30.
    fun cpsToDelayMs(cpsLo: Int, cpsHi: Int): Long {
        val lo = cpsLo.coerceIn(1, 30)
        val hi = cpsHi.coerceIn(lo, 30)
        return 1000L / (lo..hi).random()
    }

    fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Minecraft Bedrock dünya koordinatını ekran koordinatına çevirir.
     *
     * Koordinat sistemi:
     *   - Yaw  0°  = Güney (+Z), 90° = Batı (-X), -90° = Doğu (+X)
     *   - Pitch pozitif = aşağı bakış
     *   - selfY = ayak pozisyonu; göz yüksekliği +1.62f eklenerek düzeltilir
     *   - fov   = DİKEY (vertical) FOV derece cinsinden — Minecraft'ın fov ayarı gerçekte
     *             dikey FOV'dur (Matrix4f.perspective(fovy,...) convention'ı), yatay ondan
     *             aspect ile türetilir. Bunu ters kurarsan geniş ekranlarda dikey sapma olur.
     *
     * @return ekran koordinatı (px, py) veya kamera arkasındaysa null
     */
    fun worldToScreen(
        wx: Float, wy: Float, wz: Float,
        selfX: Float, selfY: Float, selfZ: Float,
        yaw: Float, pitch: Float,
        screenW: Int, screenH: Int,
        fov: Float = 110f
    ): Pair<Float, Float>? {

        val eyeY = selfY + 1.62f

        val dx = (wx - selfX).toDouble()
        val dy = (wy - eyeY).toDouble()
        val dz = (wz - selfZ).toDouble()

        val yawR   = Math.toRadians(-yaw.toDouble())
        val pitchR = Math.toRadians(-pitch.toDouble())

        val sinY = sin(yawR);  val cosY = cos(yawR)
        val sinP = sin(pitchR); val cosP = cos(pitchR)

        val rx0 = -dx * cosY + dz * sinY
        val rz0 =  dx * sinY + dz * cosY

        val rx =  rx0
        val ry =  dy * cosP - rz0 * sinP
        val rz =  dy * sinP + rz0 * cosP

        if (rz <= 0.1) return null

        val aspect      = screenW.toDouble() / screenH.toDouble()
        val tanHalfFovY = tan(Math.toRadians(fov / 2.0))
        val tanHalfFovX = tanHalfFovY * aspect

        val sx = (( rx / (rz * tanHalfFovX)) * (screenW / 2.0) + screenW / 2.0).toFloat()
        val sy = ((-ry / (rz * tanHalfFovY)) * (screenH / 2.0) + screenH / 2.0).toFloat()

        return Pair(sx, sy)
    }
}
