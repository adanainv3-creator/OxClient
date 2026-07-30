
package com.nexoraclient.module.combat

import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.core.relay.NexoraRelaySession
import com.nexoraclient.events.PacketEvent
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.*
import com.nexoraclient.module.misc.InventoryHelper
import com.nexoraclient.module.social.isFriendEntity
import com.nexoraclient.utils.MathUtil
import com.nexoraclient.utils.PacketUtil
import com.nexoraclient.utils.CritLock
import com.nexoraclient.utils.RotationUtil
import com.nexoraclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlinx.coroutines.*

/**
 * KillAuraPro — orijinal KillAura'nın "profesyonel/agresif" varyantı.
 *
 * KillAuraPro'dan farkı:
 *  - Head Lock açıkken (varsayılan açık) kafa her zaman en düşük canlı rakibe
 *    kilitli kalır — KillAura'daki Head Lock ile birebir aynı mekanizma, sürekli
 *    (sadece saldırı anında değil) PlayerAuthInputPacket üzerinden uygulanır.
 *    Tamamen görünmez kalması gerekiyorsa "Head Lock" ayarından kapatılabilir.
 *  - Menzildeki TÜM hedeflere aynı tick'te saldırır (burst multi), tek tek
 *    seçim/switch mantığı yok — maksimum DPS.
 *  - CPS'e küçük rastgele jitter eklenir (12-18 arası dalgalanır) — sabit
 *    aralıkla saldırmak yerine daha az "robotik" bir patern.
 *  - Kritik enjeksiyonu Criticals.kt'deki fix'le birebir aynı mantık:
 *    gerçek zaman aralıklı (delay'li) düşüş paketleri + doğru sıralama,
 *    böylece HER vuruş güvenilir şekilde kritik oluyor.
 */
class KillAuraPro : BaseModule(
    name        = "KillAuraPro",
    category    = ModuleCategory.COMBAT,
    description = "Silent, burst-attack, garantili kritik — profesyonel KillAura varyantı"
), PacketEventBus.PacketListener {

    private val cpsMin        = int  ("CPS Min",       28,   1,  30)
    private val cpsMax        = int  ("CPS Max",       30,   1,  30)
    private val range         = float("Range",         10f, 1f,  10f)
    private val maxTargets    = int  ("Max Targets",   1,    1,  15)
    private val predictDelay  = float("Predict Delay", 0.05f, 0.05f, 0.5f)
    private val ignoreFriends = bool ("Ignore Friends", true)
    private val alwaysCrit    = bool ("Always Crit",   true)
    private val headLock       = bool ("Head Lock",        true)
    private val headLockSmooth = float("Head Lock Smooth", 1f, 0.01f, 1f)
    private val shortcut      = bool ("Shortcut",      false)
    private val autoWeapon    = bool ("AutoWeapon",    true)

    @Volatile private var lastAttackMs = 0L
    @Volatile private var headLockYaw   = 0f
    @Volatile private var headLockPitch = 0f
    @Volatile private var cachedHeadLockTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var lastHeadLockScanMs = 0L
    @Volatile private var lastScanMs = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    private var tickJob: Job? = null

    companion object {
        private const val HEAD_LOCK_SCAN_INTERVAL_MS = 50L
        private const val TICK_INTERVAL_MS = 5L
        // KillAura.kt'deki aynı fix: hedef yokken/CPS kapısı açık kalınca
        // selectTargets() saniyede 200 kez (her tick) çalışıp tüm entity'leri
        // tarıyordu — asıl lag kaynağı buydu. Artık tarama en fazla bu aralıkla
        // yapılıp cache'leniyor.
        private const val SCAN_INTERVAL_MS = 50L
    }

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        headLockYaw = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        cachedHeadLockTarget = null
        lastHeadLockScanMs = 0L
        lastScanMs = 0L
        cachedTargets = emptyList()
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    // Head Lock: her PlayerAuthInputPacket'te (saldırı anıyla sınırlı değil, sürekli)
    // rakibe dönük tutar. KillAura'daki aynı mantık — gerçek giden paketin rotasyonunu
    // doğrudan değiştiriyoruz ki hemen ardından gelen gerçek input onu ezmesin.
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled || !headLock.value) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        applyHeadLock(event, pkt)
    }

    private fun applyHeadLock(event: PacketEvent, pkt: PlayerAuthInputPacket) {
        val now = System.currentTimeMillis()
        val cached = cachedHeadLockTarget?.takeIf { EntityTracker.getById(it.runtimeId) != null }
        if (cached == null || now - lastHeadLockScanMs >= HEAD_LOCK_SCAN_INTERVAL_MS) {
            cachedHeadLockTarget = findHeadLockTarget()
            lastHeadLockScanMs = now
        }
        val target = cachedHeadLockTarget ?: return

        val targetRot = RotationUtil.toEntity(target)
        val smoothFactor = headLockSmooth.value.coerceIn(0.01f, 1f)

        val newYaw = smoothYaw(headLockYaw, targetRot.yaw, smoothFactor)
        val newPitch = smoothPitch(headLockPitch, targetRot.pitch, smoothFactor)

        headLockYaw = newYaw
        headLockPitch = newPitch

        pkt.rotation = Vector3f.from(newPitch, newYaw, newYaw)
        EntityTracker.selfYaw = newYaw
        EntityTracker.selfPitch = newPitch

        // KRİTİK FIX: cancelAndReplace çağrılmazsa relay ham wire byte'larını
        // gönderiyor, bu mutation server'a hiç ulaşmıyordu. Bkz. KillAura.kt.
        event.cancelAndReplace(pkt)
    }

    private fun smoothYaw(current: Float, target: Float, factor: Float): Float {
        var diff = target - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val result = current + (diff * factor)
        var normalized = result % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }

    private fun smoothPitch(current: Float, target: Float, factor: Float): Float {
        val diff = target - current
        return (current + (diff * factor)).coerceIn(-90f, 90f)
    }

    private fun findHeadLockTarget(): EntityTracker.TrackedEntity? {
        return EntityTracker.getEntitiesInRange(range.value * 1.5f)
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .minByOrNull { it.health }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return

        if (now - lastScanMs >= SCAN_INTERVAL_MS) {
            cachedTargets = selectTargets()
            lastScanMs = now
        }
        val targets = cachedTargets
        if (targets.isEmpty()) return

        lastAttackMs = now
        val session = PacketEventBus.currentSession ?: return

        targets.take(maxTargets.value).forEach { target ->
            scope.launch { burstAttack(session, target) }
        }
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        return EntityTracker.getEntitiesInRange(range.value)
            .asSequence()
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .sortedBy { it.health }
            .toList()
    }

    private suspend fun burstAttack(session: NexoraRelaySession, target: EntityTracker.TrackedEntity) {
        if (alwaysCrit.value) CritLock.tryRun { injectCritTimed(session) }

        val predPos = target.predictedPosition(predictDelay.value)
        val clickPos = Vector3f.from(predPos.first, predPos.second + 1.5f, predPos.third)

        PacketUtil.sendSwing(session)

        // Silah seçimi: yağmur/su + AutoWeapon açıksa trident slotu, değilse
        // ("havadayken") InventoryHelper'ın kılıç için ayırdığı slot kullanılır.
        val hotbarSlot = resolveWeaponSlot()
        PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)
    }

    // ---------- Silah slotu seçimi ----------
    // KillAura.kt ile birebir aynı mantık: AutoWeapon kapalıysa dokunulmaz;
    // yağmur/suda trident slotuna, aksi halde InventoryHelper'ın gerçek
    // konfigürasyonuna bakarak kılıcın olduğu slota geçilir.
    // FIX: aynı kök sebep KillAura.kt'de düzeltildi — InventoryHelper cache'i
    // boşken doğrudan seçili slota düşmek, elde kılıç yoksa yumruk saldırısına
    // sebep oluyordu. Artık düşmeden önce hotbar canlı taranıyor.
    private fun resolveWeaponSlot(): Int {
        val fallback = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        if (!autoWeapon.value) return fallback

        val wet = EntityTracker.selfIsRaining || WorldBlockTracker.isPlayerInWater()
        if (wet) {
            InventoryHelper.currentTridentSlot?.let { return it }
            InventoryHelper.findTridentSlotInHotbar()?.let { return it }
            InventoryHelper.currentSwordSlot?.let { return it }
            InventoryHelper.findSwordSlotInHotbar()?.let { return it }
            return fallback
        }

        InventoryHelper.currentSwordSlot?.let { return it }
        InventoryHelper.findSwordSlotInHotbar()?.let { return it }
        return fallback
    }

    // Criticals.kt'deki fix ile birebir aynı prensip: gerçek zaman aralıklı
    // düşüş paketleri. 0ms arayla göndermek sunucunun "düşüyor" durumunu hiç
    // kaydetmemesine sebep oluyordu — burada delay'ler bilinçli olarak var.
    private suspend fun injectCritTimed(s: NexoraRelaySession) {
        try {
            PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false)
            delay(10L)
            PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = false)
            delay(5L)
            PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = true)
        } catch (_: Exception) {}
    }
}
