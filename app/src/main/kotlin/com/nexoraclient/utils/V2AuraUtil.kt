package com.rubidiumclient.utils

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import kotlinx.coroutines.delay
import org.cloudburstmc.math.vector.Vector3f

/**
 * V2Aura'ya özel yardımcı fonksiyonlar.
 *
 * Problem: hem biz hem hedef aynı anda hızlı hareket edip (strafe/circle,
 * knockback, elytra vb.) yer değiştirebiliyoruz. Sabit bir "predict delay"
 * ile hedefin SADECE kendi hızına göre ileri tahmin yapmak (ör. eski
 * target.predictedPosition(t) yaklaşımı), iki taraf da hızlıyken paketin
 * ulaşacağı ana kadar hedefin ZATEN bizi geçmiş/ters tarafa geçmiş olması
 * durumunda tahmini yanlış tarafa fırlatıyor — sonuç: kilit ters pozisyona
 * kayıyor, saldırı/rotasyon hedefin olmadığı bir noktaya gidiyor.
 *
 * Çözüm: RELATİF hız (hedef hızı - kendi hızımız) ile "en yakın yaklaşma"
 * (closest approach) zamanını hesaplayıp, tahmin süresini bu geçiş anını
 * ASLA aşmayacak şekilde kırpıyoruz. Böylece hiçbir zaman hedefi olduğundan
 * fazla ileri gidip karşı tarafa tahmin etmiyoruz.
 */
object V2AuraUtil {

    // ---------- Kendi hız vektörümüz ----------
    // EntityTracker sadece selfSpeedXZ (yönsüz skaler) tutuyor. Relatif hız
    // hesabı için yön bilgisi de gerektiğinden, kendi konum geçmişimizden
    // basit bir EMA (üstel ortalama) hız vektörü çıkarıyoruz.
    @Volatile private var lastSelfX = 0f
    @Volatile private var lastSelfY = 0f
    @Volatile private var lastSelfZ = 0f
    @Volatile private var lastSampleMs = 0L

    @Volatile private var selfVelX = 0f
    @Volatile private var selfVelY = 0f
    @Volatile private var selfVelZ = 0f

    private const val EMA_ALPHA = 0.35f
    // Bu süreden uzun aralıklarda hız sıfırlanır (teleport/respawn/dünya
    // değişimi sonrası eski örneklemden sahte bir hız türetmemek için).
    private const val MAX_SAMPLE_DT_MS = 250L

    private fun sampleSelfVelocity() {
        val now = System.currentTimeMillis()
        val dtMs = now - lastSampleMs

        if (lastSampleMs == 0L || dtMs <= 0L || dtMs > MAX_SAMPLE_DT_MS) {
            lastSelfX = EntityTracker.selfX
            lastSelfY = EntityTracker.selfY
            lastSelfZ = EntityTracker.selfZ
            lastSampleMs = now
            selfVelX = 0f; selfVelY = 0f; selfVelZ = 0f
            return
        }

        val dt = dtMs / 1000f
        val vx = (EntityTracker.selfX - lastSelfX) / dt
        val vy = (EntityTracker.selfY - lastSelfY) / dt
        val vz = (EntityTracker.selfZ - lastSelfZ) / dt

        selfVelX = MathUtil.lerp(selfVelX, vx, EMA_ALPHA)
        selfVelY = MathUtil.lerp(selfVelY, vy, EMA_ALPHA)
        selfVelZ = MathUtil.lerp(selfVelZ, vz, EMA_ALPHA)

        lastSelfX = EntityTracker.selfX
        lastSelfY = EntityTracker.selfY
        lastSelfZ = EntityTracker.selfZ
        lastSampleMs = now
    }

    data class Intercept(
        val x: Float, val y: Float, val z: Float,
        val usedDelay: Float,
        val crossing: Boolean
    )

    /**
     * Hedefin, verilen maksimum gecikme (maxDelay, saniye) içinde nerede
     * olacağını, kendi hareketimizi de hesaba katarak (RELATİF hız) tahmin
     * eder. Bu süre içinde iki taraf birbirini geçecekse (yani relatif
     * konum vektörünün büyüklüğü bu aralıkta minimuma inip tekrar
     * büyüyecekse), tahmin süresi o "geçiş/en yakın yaklaşma" anına
     * kırpılır — böylece asla hedefi olması gerekenden fazla ileri, karşı
     * tarafa tahmin etmeyiz.
     */
    fun interceptPosition(target: EntityTracker.TrackedEntity, maxDelay: Float): Intercept {
        sampleSelfVelocity()

        val relX = target.x - EntityTracker.selfX
        val relY = target.y - EntityTracker.selfY
        val relZ = target.z - EntityTracker.selfZ

        val relVelX = target.velX - selfVelX
        val relVelY = target.velY - selfVelY
        val relVelZ = target.velZ - selfVelZ

        val velSq = relVelX * relVelX + relVelY * relVelY + relVelZ * relVelZ

        var effDelay = maxDelay.coerceAtLeast(0f)
        var crossing = false

        if (velSq > 1e-4f) {
            // İki nokta sabit hızla hareket ederken en yakın yaklaşma zamanı:
            // t* = -(rel · relVel) / |relVel|^2  (standart kapanma/yaklaşma formülü)
            val tStar = -(relX * relVelX + relY * relVelY + relZ * relVelZ) / velSq
            if (tStar in 0f..maxDelay) {
                effDelay = tStar
                crossing = true
            }
        }

        val px = target.x + target.velX * effDelay
        val py = target.y + target.velY * effDelay
        val pz = target.z + target.velZ * effDelay

        return Intercept(px, py, pz, effDelay, crossing)
    }

    /**
     * Saldırı/rotasyon için kullanılacak nihai vurulacak noktayı hesaplar
     * (hedef tipine göre göz/gövde hizası offseti dahil).
     */
    fun computeStrikePoint(target: EntityTracker.TrackedEntity, maxDelay: Float): Vector3f {
        val ip = interceptPosition(target, maxDelay)
        val heightOffset = when {
            target.isPlayer -> 1.5f
            target.isCrystal -> 0f
            else -> 0.5f
        }
        return Vector3f.from(ip.x, ip.y + heightOffset, ip.z)
    }

    /**
     * Kanıtlanmış "Fast" kritik enjeksiyon paterni — Criticals.kt / KillAuraPro.kt
     * ile birebir aynı, gerçek zaman aralıklı düşüş paketleri (0ms arayla
     * göndermek sunucunun "düşüyor" durumunu hiç kaydetmemesine sebep oluyordu).
     * Çağıran taraf bunu CritLock.tryRun { } içine sarmalı.
     */
    suspend fun injectCrit(session: RubidiumRelaySession) {
        PacketUtil.sendMoveAtSelf(session, dyOffset = 0.42f, onGround = false)
        delay(10L)
        PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = false)
        delay(5L)
        PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = true)
    }
}
