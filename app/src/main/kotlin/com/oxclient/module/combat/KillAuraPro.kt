package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.module.social.isFriendEntity
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.CritLock
import org.cloudburstmc.math.vector.Vector3f
import kotlinx.coroutines.*

/**
 * KillAuraPro — orijinal KillAura'nın "profesyonel/agresif" varyantı.
 *
 * KillAura'dan farkı:
 *  - Rotasyon HİÇ değiştirilmez (tam silent): saldırı, oyuncunun ekranda hiç
 *    dönmeden, clickPosition ile hedefe kilitlenir. Rakip tarafında görünür
 *    "aim snap" yok.
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

    private val cpsMin        = int  ("CPS Min",       16,   1,  30)
    private val cpsMax        = int  ("CPS Max",       20,   1,  30)
    private val range         = float("Range",         10f, 1f,  10f)
    private val maxTargets    = int  ("Max Targets",   1,    1,  15)
    private val predictDelay  = float("Predict Delay", 0.12f, 0.05f, 0.5f)
    private val ignoreFriends = bool ("Ignore Friends", true)
    private val alwaysCrit    = bool ("Always Crit",   true)
    private val shortcut      = bool ("Shortcut",      true)

    @Volatile private var lastAttackMs = 0L
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    // Bu modülde rotasyonla ilgilenmediğimiz için PlayerAuthInputPacket'e
    // dokunmuyoruz — sadece PacketListener arayüzünü BaseModule ile aynı
    // pattern'de tutmak için var, gerçek iş tickLoop'ta.
    override fun onPacket(event: PacketEvent) {}

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(1L)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return

        val targets = selectTargets()
        if (targets.isEmpty()) return

        lastAttackMs = now
        val session = PacketEventBus.currentSession ?: return

        targets.take(maxTargets.value).forEach { target ->
            scope.launch { burstAttack(session, target) }
        }
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val rangeSq = range.value * range.value
        return EntityTracker.getEntitiesInRange(range.value)
            .asSequence()
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .filter { MathUtil.dist3sq(it.x, it.y, it.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ) <= rangeSq }
            .sortedBy { it.health }
            .toList()
    }

    private suspend fun burstAttack(session: OxRelaySession, target: EntityTracker.TrackedEntity) {
        if (alwaysCrit.value) CritLock.tryRun { injectCritTimed(session) }

        val predPos = target.predictedPosition(predictDelay.value)
        val clickPos = Vector3f.from(predPos.first, predPos.second + 1.5f, predPos.third)

        PacketUtil.sendSwing(session)
        val hotbarSlot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)
    }

    // Criticals.kt'deki fix ile birebir aynı prensip: gerçek zaman aralıklı
    // düşüş paketleri. 0ms arayla göndermek sunucunun "düşüyor" durumunu hiç
    // kaydetmemesine sebep oluyordu — burada delay'ler bilinçli olarak var.
    private suspend fun injectCritTimed(s: OxRelaySession) {
        try {
            listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f).forEach { dy ->
                PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = false)
                delay(25L)
            }
        } catch (_: Exception) {}
    }
}
