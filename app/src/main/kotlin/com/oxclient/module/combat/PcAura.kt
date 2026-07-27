package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.module.social.isFriendEntity
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import com.oxclient.utils.CritLock
import com.oxclient.utils.InventoryUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import kotlinx.coroutines.*

class PcAura : BaseModule(
    name        = "OxAura",
    category    = ModuleCategory.COMBAT,
    description = "PC kalitesinde ultra güçlü otomatik saldırı"
), PacketEventBus.PacketListener {

    private val cpsMin        = int  ("CPS Min",         45,   1,   50)
    private val cpsMax        = int  ("CPS Max",         50,   1,   50)
    private val range         = float("Range",           20f,  1f,  20f)
    private val fov           = int  ("FOV",             360,  30,  360)
    private val maxTargets    = int  ("Max Targets",     15,   1,   15)
    private val predictDelay  = float("Predict Delay",   0.1f, 0.05f, 0.5f)
    private val ignoreFriends = bool ("Ignore Friends",  true)
    private val autoWeapon    = bool ("Auto Weapon",     true)

    private val alwaysCrit    = bool ("Always Crit",     true)
    private val silentRot     = bool ("Silent Rotation", true)
    private val ignoreHurt    = bool ("Ignore HurtTime", true)
    private val mobAura       = bool ("Mob Aura",        false)

    enum class PriorityMode { DISTANCE, HEALTH, LOWEST_HEALTH, DIRECTION }
    private val priorityMode  = enum ("Priority",        PriorityMode.LOWEST_HEALTH)
    private val reversePri    = bool ("Reverse Priority", false)
    private val shortcut      = bool ("Shortcut",        false)

    @Volatile private var lastAttackMs = 0L
    @Volatile private var lastRotSendMs = 0L
    @Volatile private var headLockYaw = 0f
    @Volatile private var headLockPitch = 0f
    @Volatile private var cachedRotTarget: EntityTracker.TrackedEntity? = null

    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        lastRotSendMs = 0L
        headLockYaw = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        cachedRotTarget = null

        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val pkt = event.packet as? org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket ?: return

        if (silentRot.value) {
            val now = System.currentTimeMillis()
            val cached = cachedRotTarget?.takeIf { EntityTracker.getById(it.runtimeId) != null }
            val target = if (cached != null && now - lastRotSendMs < 50L) {
                cached
            } else {
                selectTargets().firstOrNull().also { cachedRotTarget = it }
            }
            if (target != null) {
                applySilentRotation(pkt, target)
            }
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            // Eskiden 1ms idi -> saniyede 1000 gereksiz kontrol (max CPS 50 iken en hızlı
            // gerçek aralık zaten 20ms). Bu fazladan 980 uyanma/kontrol telefonu yoruyor ve
            // overlay/network thread'leriyle çakışıp lag'e sebep oluyordu. 10ms hâlâ bol payla
            // en hızlı CPS aralığını yakalıyor ama scheduler yükünü ~10 kat azaltıyor.
            delay(10L)
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

        if (autoWeapon.value) {
            selectBestWeapon(session)
        }

        val targetsToHit = targets.take(maxTargets.value)

        targetsToHit.forEach { target ->
            scope.launch {
                performAttack(session, target)
            }
        }

        if (!silentRot.value && targets.isNotEmpty()) {
            val target = targets.first()
            val rot = RotationUtil.toEntity(target)
            PacketUtil.sendMoveAtSelf(
                session,
                yaw = rot.yaw,
                pitch = rot.pitch,
                onGround = true
            )
        }
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        return EntityTracker.getEntitiesInRange(range.value)
            .asSequence()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { isValidTarget(it) }
            .filter { fov.value >= 360 || EntityTracker.angleToEntity(it) <= fov.value / 2f }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .filter { !mobAura.value || isMob(it) }
            .sortedWith(compareBy { getPriorityScore(it) })
            .toList()
    }

    private fun isValidTarget(entity: EntityTracker.TrackedEntity): Boolean {
        if (!mobAura.value && !entity.isPlayer) return false
        if (mobAura.value && !entity.isPlayer && !isMob(entity)) return false

        if (entity.health <= 0) return false

        if (!ignoreHurt.value && entity.hurtTime > 0) return false

        return true
    }

    private fun isMob(entity: EntityTracker.TrackedEntity): Boolean {
        return entity.isHostile || entity.type == EntityTracker.EntityType.ANIMAL ||
               entity.type == EntityTracker.EntityType.PASSIVE
    }

    private fun getPriorityScore(entity: EntityTracker.TrackedEntity): Float {
        val score = when (priorityMode.value) {
            PriorityMode.DISTANCE -> EntityTracker.distanceTo(entity)
            PriorityMode.HEALTH -> entity.health
            PriorityMode.LOWEST_HEALTH -> -entity.health
            PriorityMode.DIRECTION -> EntityTracker.angleToEntity(entity)
        }
        return if (reversePri.value) -score else score
    }

    private fun selectBestWeapon(session: OxRelaySession) {
        try {
            var bestSlot = EntityTracker.selfHotbarSlot
            var bestDamage = 0f

            for (slot in 0..8) {
                val item = EntityTracker.getInventoryItem(slot) ?: continue
                val damage = getWeaponDamage(item)
                if (damage > bestDamage) {
                    bestDamage = damage
                    bestSlot = slot
                }
            }

            if (bestSlot != EntityTracker.selfHotbarSlot) {
                InventoryUtil.sendHotbarSelect(session, bestSlot)
            }
        } catch (_: Exception) { }
    }

    private fun getWeaponDamage(item: ItemData): Float {
        val identifier = runCatching { item.definition?.identifier }.getOrElse { null } ?: return 1f

        return when {
            identifier.contains("netherite_sword") -> 8f
            identifier.contains("diamond_sword") -> 7f
            identifier.contains("iron_sword") -> 6f
            identifier.contains("stone_sword") -> 5f
            identifier.contains("wooden_sword") -> 4f
            identifier.contains("netherite_axe") -> 7f
            identifier.contains("diamond_axe") -> 6f
            identifier.contains("iron_axe") -> 5f
            identifier.contains("stone_axe") -> 4f
            identifier.contains("wooden_axe") -> 3f
            identifier.contains("trident") -> 6f
            else -> 1f
        }
    }

    private suspend fun performAttack(session: OxRelaySession, target: EntityTracker.TrackedEntity) {
        try {
            if (alwaysCrit.value) {
                CritLock.tryRun { injectCritical(session) }
            }

            val predPos = target.predictedPosition(predictDelay.value)

            val clickPos = Vector3f.from(
                predPos.first,
                predPos.second + 1.62f,
                predPos.third
            )

            val hotbarSlot = EntityTracker.selfHotbarSlot.coerceIn(0, 8)

            PacketUtil.sendSwing(session)
            PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)

            // Eskiden 3 attack paketi gönderiliyordu (1 + repeat(2)). KillAura'daki
            // "double-attack" (registration güvenliği için 2x) yeterli — 3. paket
            // sadece ekstra trafikti. maxTargets=15 iken bu tek başına 15 hedef × 1
            // paket = 15 gereksiz attack paketi/tick demekti, kalabalık yerlerde
            // (mob farm vb.) asıl lag kaynağıydı.
            PacketUtil.sendAttack(session, target.runtimeId, hotbarSlot, clickPos)
        } catch (e: Exception) {
            android.util.Log.e("OxAura", "performAttack failed for ${target.runtimeId}: ${e.message}", e)
        }
    }

    private suspend fun injectCritical(session: OxRelaySession) {
        try {
            PacketUtil.sendMoveAtSelf(session, dyOffset = 0.42f, onGround = false)
            delay(8L)

            PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = false)
            delay(5L)

            PacketUtil.sendMoveAtSelf(session, dyOffset = 0f, onGround = true)

        } catch (_: Exception) { }
    }

    private fun applySilentRotation(
        pkt: org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket,
        target: EntityTracker.TrackedEntity
    ) {
        val now = System.currentTimeMillis()
        if (now - lastRotSendMs < 50L) return
        lastRotSendMs = now

        val rot = RotationUtil.toEntity(target)

        val smoothFactor = 0.7f
        val newYaw = smoothAngle(headLockYaw, rot.yaw, smoothFactor)
        val newPitch = smoothAngle(headLockPitch, rot.pitch, smoothFactor)

        headLockYaw = newYaw
        headLockPitch = newPitch

        pkt.rotation = Vector3f.from(newPitch, newYaw, newYaw)

        EntityTracker.selfYaw = newYaw
        EntityTracker.selfPitch = newPitch
    }

    private fun smoothAngle(current: Float, target: Float, factor: Float): Float {
        var diff = target - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val result = current + (diff * factor)
        var normalized = result % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }

    fun getTargetCount(): Int = selectTargets().size

    fun isTargeting(): Boolean = selectTargets().isNotEmpty()
}