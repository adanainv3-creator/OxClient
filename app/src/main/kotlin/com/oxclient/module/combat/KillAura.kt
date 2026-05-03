package com.oxclient.module.combat

import android.util.Log
import com.oxclient.core.entity.TrackedEntity
import com.oxclient.core.proxy.PacketFactory
import com.oxclient.core.proxy.PacketIds
import com.oxclient.events.PacketEvent
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import com.oxclient.session.SessionManager
import com.oxclient.utils.BinaryUtils
import kotlin.math.*

/**
 * KillAura — packet-level auto-attack for 2b2t.pe.
 *
 * Attack mechanism: injects InventoryTransactionPacket (USE_ITEM_ON_ENTITY)
 * directly into the proxy stream → 2b2tpe.org.
 */
class KillAura : BaseModule(
    name        = "KillAura",
    description = "Automatically attacks nearby entities",
    category    = ModuleCategory.COMBAT
) {
    // Settings (matching wclient/protohax style)
    val cpsMin       = floatSetting("CPS Min",      1f,  20f,  5f,  0.5f)
    val cpsMax       = floatSetting("CPS Max",      1f,  20f,  8f,  0.5f)
    val range        = floatSetting("Range",        1f,  8f,   3.7f,0.1f)
    val fov          = floatSetting("FOV",          10f, 360f, 180f,5f)
    val switchDelay  = intSetting("SwitchDelay",    0,   500,  50)
    val attackMode   = enumSetting("AttackMode",   AttackMode.SINGLE, AttackMode.values())
    val rotationMode = enumSetting("RotationMode", RotationMode.SILENT, RotationMode.values())
    val priority     = enumSetting("Priority",     Priority.NEAREST, Priority.values())
    val attackPlayers = boolSetting("AttackPlayers", true)
    val attackMobs    = boolSetting("AttackMobs",    true)
    val noSwing       = boolSetting("NoSwing",       false)

    private var lastAttackMs   = 0L
    private var currentCps     = 6f
    private var currentTarget: TrackedEntity? = null
    private var lastSwitchMs   = 0L
    private var hotbarSlot     = 0
    private var playerYaw      = 0f
    private var playerPitch    = 0f

    override fun onEnable() { randomizeCps(); Log.i("KillAura","enabled range=${range.value}") }
    override fun onDisable() { currentTarget = null }

    override fun onPacketSend(event: PacketEvent) {
        when (event.packetId) {
            PacketIds.PLAYER_AUTH_INPUT -> parseAuthInput(event)
        }
    }
    override fun onPacketReceive(event: PacketEvent) {
        SessionManager.entityTracker.onServerPacket(event)
    }

    override suspend fun onTick() {
        val now = System.currentTimeMillis()
        if (now - lastAttackMs < (1000f / currentCps).toLong()) return

        val et = SessionManager.entityTracker
        val sx = et.selfX; val sy = et.selfY; val sz = et.selfZ

        val candidates = et.getNearby(sx, sy, sz, range.value)
            .filter { isValid(it) && inFov(it, sx, sy, sz) }

        if (candidates.isEmpty()) { currentTarget = null; return }

        val targets: List<TrackedEntity> = when (attackMode.value) {
            AttackMode.SINGLE -> listOf(selectTarget(candidates))
            AttackMode.MULTI  -> candidates
            AttackMode.SWITCH -> {
                if (currentTarget == null || now - lastSwitchMs >= switchDelay.value) {
                    currentTarget = candidates.firstOrNull { it.runtimeId != currentTarget?.runtimeId } ?: candidates.first()
                    lastSwitchMs = now
                }
                listOf(currentTarget!!)
            }
        }

        targets.forEach { doAttack(it, sx, sy, sz) }
        lastAttackMs = now; randomizeCps()
    }

    private fun doAttack(t: TrackedEntity, sx: Float, sy: Float, sz: Float) {
        val proxy = SessionManager.proxy ?: return
        if (rotationMode.value == RotationMode.PACKET) injectRotation(t, sx, sy, sz)
        proxy.injectC2S(PacketFactory.buildAttack(t.runtimeId, hotbarSlot, sx, sy, sz, t.x, t.y, t.z))
        if (!noSwing.value) proxy.injectC2S(PacketFactory.buildSwingArm(SessionManager.entityTracker.selfId))
        t.lastAttackedMs = System.currentTimeMillis()
    }

    private fun injectRotation(t: TrackedEntity, sx: Float, sy: Float, sz: Float) {
        val dx = t.x - sx; val dy = t.y + 1.62f - (sy + 1.62f); val dz = t.z - sz
        val dist = sqrt((dx*dx + dz*dz).toDouble()).toFloat()
        val yaw   = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy.toDouble(), dist.toDouble()))).toFloat()
        SessionManager.proxy?.injectC2S(
            PacketFactory.buildMovePlayer(SessionManager.entityTracker.selfId, sx, sy, sz, yaw, pitch, yaw, true))
    }

    private fun parseAuthInput(event: PacketEvent) {
        val b = BinaryUtils.wrap(event.payload)
        BinaryUtils.skipVarInt(b)
        try {
            val newPitch = BinaryUtils.readLEFloat(b)
            val newYaw   = BinaryUtils.readLEFloat(b)
            playerPitch = newPitch; playerYaw = newYaw

            if (rotationMode.value == RotationMode.SILENT && currentTarget != null) {
                val t = currentTarget!!; val et = SessionManager.entityTracker
                val dx = t.x - et.selfX; val dy = t.y+1.62f-(et.selfY+1.62f); val dz = t.z-et.selfZ
                val d  = sqrt((dx*dx+dz*dz).toDouble()).toFloat()
                val sy = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
                val sp = (-Math.toDegrees(atan2(dy.toDouble(), d.toDouble()))).toFloat()
                val mod = event.payload.copyOf()
                val mb = java.nio.ByteBuffer.wrap(mod).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                BinaryUtils.skipVarInt(mb); mb.putFloat(sp); mb.putFloat(sy)
                event.setPayload(mod)
            }
        } catch (_: Exception) {}
    }

    private fun isValid(e: TrackedEntity): Boolean {
        if (!e.isAlive() || e.invisible) return false
        if (!attackPlayers.value && e.isPlayer()) return false
        if (!attackMobs.value    && e.isMob())    return false
        return true
    }
    private fun inFov(e: TrackedEntity, sx: Float, sy: Float, sz: Float): Boolean {
        val half = fov.value / 2f
        if (half >= 180f) return true
        val dx = e.x-sx; val dz = e.z-sz
        val ty = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        var d  = abs(ty - playerYaw) % 360f
        if (d > 180f) d = 360f - d
        return d <= half
    }
    private fun selectTarget(list: List<TrackedEntity>) = when (priority.value) {
        Priority.NEAREST   -> list.minByOrNull { it.cachedDistSq }!!
        Priority.LOWEST_HP -> list.minByOrNull { it.health }!!
    }
    private fun randomizeCps() {
        val lo = cpsMin.value.coerceAtMost(cpsMax.value)
        val hi = cpsMax.value.coerceAtLeast(lo)
        currentCps = lo + (hi - lo) * Math.random().toFloat()
    }

    enum class AttackMode  { SINGLE, MULTI, SWITCH }
    enum class RotationMode { NONE, PACKET, SILENT }
    enum class Priority    { NEAREST, LOWEST_HP }
}
