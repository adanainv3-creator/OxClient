package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.proxy.PacketHelper
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketListener
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

class KillAura : BaseModule(
    name        = "KillAura",
    category    = ModuleCategory.COMBAT,
    description = "Yakındaki düşmanlara otomatik saldırır"
), PacketListener {

    override val priority = 80

    enum class AttackMode  { Single, Multi, Switch }
    enum class RotationMode{ Lock, Approximate, None }
    enum class SwingMode   { Client, Server, Both, None }
    enum class PriorityMode{ Distance, Health, Direction }

    // ── Ayarlar ───────────────────────────────────────────────────────────
    private val cpsMin         = IntSetting("CPS Min",        8,    1,  20)
    private val cpsMax         = IntSetting("CPS Max",        12,   1,  20)
    private val range          = FloatSetting("Range",        3.7f, 1f,  6f)
    private val fov            = IntSetting("Fov",            180,  30, 360)
    private val switchDelay    = IntSetting("SwitchDelay",    50,   0,  500)
    private val attackMode     = EnumSetting("AttackMode",    AttackMode.Single,    AttackMode.entries)
    private val rotationMode   = EnumSetting("RotationMode",  RotationMode.Lock,    RotationMode.entries)
    private val swingMode      = EnumSetting("Swing",         SwingMode.Both,       SwingMode.entries)
    private val priorityMode   = EnumSetting("PriorityMode",  PriorityMode.Distance,PriorityMode.entries)
    private val reversePriority= BoolSetting("ReversePriority",false)
    private val mouseover      = BoolSetting("Mouseover",     false)
    private val swingSound     = BoolSetting("SwingSound",    true)
    private val failRate       = FloatSetting("FailRate",     0f,   0f,  1f)
    private val shortcut       = BoolSetting("Shortcut",      false)

    override fun registerSettings() = listOf(
        cpsMin, cpsMax, range, fov, switchDelay,
        attackMode, rotationMode, swingMode, priorityMode,
        reversePriority, mouseover, swingSound, failRate, shortcut
    )

    // ── İç durum ──────────────────────────────────────────────────────────
    private var currentTargetId = 0L
    private var lastSwitchMs    = 0L
    private var lastAttackMs    = 0L

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob : Job? = null

    override fun onEnable() {
        PacketEventBus.register(this)
        currentTargetId = 0L
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
    }

    override fun onPacket(event: PacketEvent) { /* EntityTracker hallediyor */ }

    // ── Tick ──────────────────────────────────────────────────────────────

    private suspend fun tickLoop() {
        while (coroutineContext.isActive) {
            if (isEnabled) {
                if (attackMode.value == AttackMode.Multi) attackMulti()
                else attackSingle()
            }
            delay(50L)
        }
    }

    private fun attackSingle() {
        val target = selectTarget() ?: return
        val now    = System.currentTimeMillis()
        if (now - lastAttackMs < delayMs()) return
        if (failRate.value > 0f && Math.random() < failRate.value) return
        lastAttackMs = now
        doAttack(target)
    }

    private fun attackMulti() {
        val now = System.currentTimeMillis()
        EntityTracker.getEntitiesInRange(range.value)
            .filter { isInFov(it) }
            .forEach { e ->
                if (failRate.value > 0f && Math.random() < failRate.value) return@forEach
                if (now - lastAttackMs >= delayMs()) {
                    lastAttackMs = now
                    doAttack(e)
                }
            }
    }

    private fun doAttack(e: EntityTracker.TrackedEntity) {
        // Rotasyon
        when (rotationMode.value) {
            RotationMode.Lock        -> sendRotation(e, approx = false)
            RotationMode.Approximate -> sendRotation(e, approx = true)
            RotationMode.None        -> {}
        }
        // Swing
        when (swingMode.value) {
            SwingMode.Server, SwingMode.Both ->
                PacketHelper.injectToServer(PacketHelper.buildAnimate(EntityTracker.selfRuntimeId))
            else -> {}
        }
        // Saldırı
        PacketHelper.injectToServer(
            PacketHelper.buildAttack(e.runtimeId, EntityTracker.selfRuntimeId)
        )
    }

    private fun sendRotation(e: EntityTracker.TrackedEntity, approx: Boolean) {
        val dx = e.x - EntityTracker.selfX
        val dy = e.y - EntityTracker.selfY
        val dz = e.z - EntityTracker.selfZ
        var yaw   = Math.toDegrees(Math.atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        var pitch = Math.toDegrees(Math.atan2(-dy.toDouble(),
            Math.sqrt((dx*dx + dz*dz).toDouble()))).toFloat()
        if (approx) { yaw += (Math.random()*4-2).toFloat(); pitch += (Math.random()*2-1).toFloat() }
        PacketHelper.injectToServer(
            PacketHelper.buildMovePlayer(
                EntityTracker.selfRuntimeId,
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ,
                yaw, pitch, yaw, onGround = true
            )
        )
    }

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(range.value)
            .filter { isInFov(it) }

        // SWITCH: belirli aralıkla hedef değiştir
        if (attackMode.value == AttackMode.Switch) {
            val now = System.currentTimeMillis()
            if (now - lastSwitchMs >= switchDelay.value) {
                currentTargetId = 0L; lastSwitchMs = now
            }
            val cur = candidates.find { it.runtimeId == currentTargetId }
            if (cur != null) return cur
        }

        val sorted = when (priorityMode.value) {
            PriorityMode.Distance  -> candidates.sortedBy  { EntityTracker.distanceTo(it) }
            PriorityMode.Health    -> candidates.sortedBy  { it.health }
            PriorityMode.Direction -> candidates.sortedBy  { EntityTracker.angleToEntity(it) }
        }
        val result = if (reversePriority.value) sorted.lastOrNull() else sorted.firstOrNull()
        if (attackMode.value == AttackMode.Switch && result != null) currentTargetId = result.runtimeId
        return result
    }

    private fun isInFov(e: EntityTracker.TrackedEntity): Boolean {
        if (fov.value >= 360) return true
        return EntityTracker.angleToEntity(e) <= fov.value / 2f
    }

    private fun delayMs(): Long {
        val lo = cpsMin.value.coerceIn(1, 20)
        val hi = cpsMax.value.coerceIn(lo, 20)
        return 1000L / (lo..hi).random()
    }
}