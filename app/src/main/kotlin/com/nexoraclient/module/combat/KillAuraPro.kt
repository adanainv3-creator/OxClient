
package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.CritLock
import com.rubidiumclient.utils.RotationUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlinx.coroutines.*

/**
 * KillAuraPro — orijinal KillAura'nın "profesyonel/agresif" varyantı.
 *
 *  - Head Lock: KillAura ile birebir aynı mekanizma.
 *  - Menzildeki TÜM hedeflere aynı tick'te saldırır (burst multi).
 *  - CPS gerçekten 30'a kadar (MathUtil fix'i sonrası).
 *  - Kritik enjeksiyonu artık attack paketinden önce landing göndermiyor —
 *    hit anında hâlâ "düşüyor" state'inde oluyorsun, crit gerçekten tutuyor.
 *  - Kendi TP sistemi var: hedef menzil dışına kaçarsa (TP Range içindeyse)
 *    ayrı bir TPAura modülüne ihtiyaç duymadan otomatik yaklaşır/ışınlanır —
 *    wclient'in KillauraModule'undeki tpAura entegrasyonunun karşılığı.
 *  - Packet Count: her saldırıda attack isteği kaç kez gönderilsin (paket
 *    kaybına karşı hit-register güvencesi).
 *  - Anti Bot: isim/uuid'si geçersiz sahte entity'leri hedeflemez.
 */
class KillAuraPro : BaseModule(
    name        = "KillAuraPro",
    category    = ModuleCategory.COMBAT,
    description = "Silent, burst-attack, garantili kritik, kendi TP sistemi — profesyonel KillAura varyantı"
), PacketEventBus.PacketListener {

    private val cpsMin        = int  ("CPS Min",       28,   1,  30)
    private val cpsMax        = int  ("CPS Max",       30,   1,  30)
    private val range         = float("Range",         10f, 1f,  18f)
    // Max hasar için varsayılan menzildeki 3 hedefe birden vurur.
    private val maxTargets    = int  ("Max Targets",   3,    1,  15)
    private val predictDelay  = float("Predict Delay", 0.05f, 0.05f, 0.5f)
    private val ignoreFriends = bool ("Ignore Friends", true)
    private val alwaysCrit    = bool ("Always Crit",   true)
    private val headLock       = bool ("Head Lock",        true)
    private val headLockSmooth = float("Head Lock Smooth", 1f, 0.01f, 1f)
    private val shortcut      = bool ("Shortcut",      false)
    private val autoWeapon    = bool ("AutoWeapon",    true)
    private val swordSlot     = int  ("Sword Slot",    0,    0,  8)
    private val tridentSlot   = int  ("Trident Slot",  1,    0,  8)

    private val antiBot       = bool ("Anti Bot",      true)

    // Kendi TP sistemi (ayrı TPAura modülüne bağımlı değil)
    private val tpEnabled     = bool ("TP Aura",       false)
    private val tpRange       = float("TP Range",      20f, 4f,  40f)
    private val tpSpeedMs     = int  ("TP Speed",      100,  20, 1000)
    private val tpYOffset     = float("TP Y Offset",   0f, -2f, 2f)
    private val keepDistance  = float("Keep Distance", 2.5f, 1f, 8f)

    @Volatile private var lastAttackMs = 0L
    @Volatile private var lastTpMs     = 0L
    @Volatile private var headLockYaw   = 0f
    @Volatile private var headLockPitch = 0f
    @Volatile private var cachedHeadLockTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var lastHeadLockScanMs = 0L
    @Volatile private var lastScanMs = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    private var tickJob: Job? = null

    companion object {
        private const val HEAD_LOCK_SCAN_INTERVAL_MS = 50L
        // FIX: 5ms (200Hz) idi -> KillAura + AutoTotem ile birlikte çalışınca
        // asıl "aşırı lag" sebebiydi. 15ms yeterli hassasiyeti koruyor.
        private const val TICK_INTERVAL_MS = 15L
        private const val SCAN_INTERVAL_MS = 50L
    }

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        lastTpMs = 0L
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
            .let { if (antiBot.value) it.filterNot { e -> e.isLikelyBot() } else it }
            .minByOrNull { it.health }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        val now = System.currentTimeMillis()

        val tpTargets = if (tpEnabled.value) selectTpCandidates() else emptyList()
        val closest = tpTargets.minByOrNull { EntityTracker.distanceTo(it) }
        if (closest != null) {
            val dist = EntityTracker.distanceTo(closest)
            if (dist > range.value && now - lastTpMs >= tpSpeedMs.value) {
                tpToTarget(session, closest)
                lastTpMs = now
            }
        }

        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return

        if (now - lastScanMs >= SCAN_INTERVAL_MS) {
            cachedTargets = selectTargets()
            lastScanMs = now
        }
        val targets = cachedTargets
        if (targets.isEmpty()) return

        lastAttackMs = now

        targets.take(maxTargets.value).forEach { target ->
            scope.launch { burstAttack(session, target) }
        }
    }

    // Kendi TP sistemi: menzil dışında ama TP Range içinde olan en yakın
    // hedefe doğru "keepDistance" kadar yakınlaşacak şekilde ışınlanır.
    // Ayrı bir TPAura modülüne gerek kalmadan KillAuraPro'yu menzil dışı
    // kaçışlara karşı kapatır.
    private fun selectTpCandidates(): List<EntityTracker.TrackedEntity> {
        return EntityTracker.getEntitiesInRange(tpRange.value)
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .let { if (antiBot.value) it.filterNot { e -> e.isLikelyBot() } else it }
    }

    private fun tpToTarget(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        val selfX = EntityTracker.selfX
        val selfZ = EntityTracker.selfZ

        val dist = MathUtil.dist2(selfX, selfZ, target.x, target.z)
        if (dist <= keepDistance.value) return

        val nx = (target.x - selfX) / dist
        val nz = (target.z - selfZ) / dist

        val newX = target.x - nx * keepDistance.value
        val newZ = target.z - nz * keepDistance.value
        val newY = target.y + tpYOffset.value

        val rot = RotationUtil.toEntity(target)

        // FIX: mirrorToClient=true eklendi. Öncesinde bu paket sadece
        // serverBound gidiyordu — sunucu seni yeni yerde görüyordu ama
        // client'ın kendi render pozisyonu güncellenmediği için telefonda
        // hiçbir hareket olmuyormuş gibi duruyordu (TP tamamen "çalışmıyor"
        // gibi görünmesinin sebebi buydu). TPAura zaten aynı şekilde hem
        // serverBound hem clientBound gönderiyor, burada da eşitledik.
        PacketUtil.sendMove(session, newX, newY, newZ, rot.yaw, rot.pitch, onGround = false, teleport = true, mirrorToClient = true)

        EntityTracker.selfX = newX
        EntityTracker.selfY = newY
        EntityTracker.selfZ = newZ
        EntityTracker.selfYaw = rot.yaw
        EntityTracker.selfPitch = rot.pitch
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        return EntityTracker.getEntitiesInRange(range.value)
            .asSequence()
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .let { if (antiBot.value) it.filterNot { e -> e.isLikelyBot() } else it }
            .sortedBy { it.health }
            .toList()
    }

    // EntityTracker.kt'ye göre düzeltildi: kimlik `name`/`uniqueId` üzerinden,
    // `uuid` alanı yok.
    private fun EntityTracker.TrackedEntity.isLikelyBot(): Boolean {
        if (name.isBlank()) return true
        if (uniqueId == 0L) return true
        return false
    }

    private suspend fun burstAttack(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        // CritLock.tryRun Unit döndürür — Boolean bekleyen eski hâli
        // derlenmiyordu. Ayrıca modül adıyla ayrı kilit kullanıyoruz ki
        // KillAura ile aynı anda açıkken birbirinin crit'ini iptal etmesin.
        var didCrit = false
        if (alwaysCrit.value) {
            CritLock.tryRun("KillAuraPro") {
                didCrit = injectCritTimed(session)
            }
        }

        val predPos = target.predictedPosition(predictDelay.value)
        val clickPos = Vector3f.from(predPos.first, predPos.second + 1.5f, predPos.third)

        PacketUtil.sendSwing(session)

        // KillAura ile tutarlı olsun diye "Packet Count" slider'ı kaldırıldı,
        // sabit double-attack (2x) yapıyoruz.
        val hotbarSlot = resolveWeaponSlot()
        PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)
        PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)

        // FIX: landing paketi artık attack'tan SONRA gönderiliyor. Öncesinde
        // injectCritTimed kendi içinde onGround=true ile bitiyordu ve bu,
        // gerçek attack paketi gitmeden önce server'a "yerdeyim" diyordu —
        // crit şartını hit anından önce bozuyordu.
        if (didCrit) {
            delay(5L)
            PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = true)
        }
    }

    private fun resolveWeaponSlot(): Int {
        val fallback = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        if (!autoWeapon.value) return fallback

        val wet = EntityTracker.selfIsRaining || WorldBlockTracker.isPlayerInWater()
        return if (wet) tridentSlot.value.coerceIn(0, 8) else swordSlot.value.coerceIn(0, 8)
    }

    private suspend fun injectCritTimed(s: RubidiumRelaySession): Boolean {
        return try {
            PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false)
            delay(10L)
            PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = false)
            true
        } catch (_: Exception) {
            false
        }
    }
}
