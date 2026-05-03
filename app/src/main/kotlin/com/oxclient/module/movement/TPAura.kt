package com.oxclient.module.movement

import android.util.Log
import com.oxclient.core.entity.TrackedEntity
import com.oxclient.core.proxy.MitmProxy
import com.oxclient.core.proxy.PacketFactory
import com.oxclient.events.PacketEvent
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import com.oxclient.session.SessionManager
import kotlin.math.*

class TPAura : BaseModule(
    name        = "TPAura",
    description = "Teleports around target entity via MovePlayer packets",
    category    = ModuleCategory.MOVEMENT
) {

    val mode            = enumSetting("Mode", TpMode.STRAFE, TpMode.values())
    val range           = floatSetting("Range", 0.5f, 5f, 1.5f, 0.05f)
    val yOffset         = floatSetting("Y Offset", -2f, 2f, 0f, 0.05f)
    val passive         = boolSetting("Passive", false)
    val horizontalSpeed = floatSetting("HorizontalSpeed", 0.5f, 20f, 6.11f, 0.1f)
    val verticalSpeed   = floatSetting("VerticalSpeed", 0.5f, 10f, 4f, 0.1f)
    val strafeSpeed     = floatSetting("StrafeSpeed", 1f, 50f, 20f, 1f)
    val shortcut        = boolSetting("Shortcut", true)

    private var strafeAngle = 0.0
    private var currentTarget: TrackedEntity? = null
    private var lastTpMs = 0L
    private val TP_INTERVAL_MS = 50L

    override fun onEnable() {
        strafeAngle = 0.0
        currentTarget = null
        Log.i("TPAura", "Enabled mode=${mode.value}")
    }

    override fun onDisable() {
        currentTarget = null
    }

    override fun onPacketReceive(event: PacketEvent) {
        SessionManager.entityTracker.onServerPacket(event)
    }

    override suspend fun onTick() {
        val now = System.currentTimeMillis()
        if (now - lastTpMs < TP_INTERVAL_MS) return
        lastTpMs = now

        val et = SessionManager.entityTracker
        // FIX: proxy tipi MitmProxy olarak düzeltildi (GameProxy → MitmProxy)
        val proxy: MitmProxy = SessionManager.proxy ?: return

        val selfId: Long = et.selfId   // selfId Long olarak kullanılıyor (PacketFactory Long bekliyor)
        val sx = et.selfX
        val sy = et.selfY
        val sz = et.selfZ

        val target = et.getNearest(sx, sy, sz, 10f) ?: run {
            currentTarget = null
            return
        }

        currentTarget = target

        val dist = target.distanceTo(sx, sy, sz)
        if (!passive.value && dist > range.value * 4) return

        val (destX, destY, destZ) = calcDestination(target, sx, sy, sz)

        val dx = target.x - destX
        val dz = target.z - destZ

        val yaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        val dy = (target.y + 1f) - (destY + 1.62f)
        val hDist = sqrt(dx * dx + dz * dz).toDouble()
        val pitch = (-Math.toDegrees(atan2(dy.toDouble(), hDist))).toFloat()

        buildAndInjectTeleport(
            proxy      = proxy,
            selfId     = selfId,
            destX      = destX,
            destY      = destY,
            destZ      = destZ,
            yaw        = yaw,
            pitch      = pitch,
            currentY   = sy,
            destYCheck = destY
        )

        strafeAngle = (strafeAngle + strafeSpeed.value) % 360.0

        Log.v("TPAura", "TP -> [$destX, $destY, $destZ]")
    }

    private fun buildAndInjectTeleport(
        proxy: MitmProxy,          // FIX: GameProxy → MitmProxy (hata satır 99)
        selfId: Long,   // PacketFactory.buildMovePlayer Long bekliyor
        destX: Float,
        destY: Float,
        destZ: Float,
        yaw: Float,
        pitch: Float,
        currentY: Float,
        destYCheck: Float
    ) {
        // FIX: selfId zaten Int, Long cast kaldırıldı (hata satır 110)
        val packet = PacketFactory.buildMovePlayer(
            selfId,
            destX,
            destY,
            destZ,
            yaw,
            pitch.coerceIn(-90f, 90f),
            yaw,
            destYCheck <= currentY + 0.1f
        )
        // FIX: MitmProxy.injectC2S(byte[]) doğrudan çağrılıyor (hata satır 119)
        proxy.injectC2S(packet)
    }

    private fun calcDestination(
        target: TrackedEntity,
        sx: Float,
        sy: Float,
        sz: Float
    ): Triple<Float, Float, Float> {

        val r = range.value
        val yo = yOffset.value
        val tx = target.x
        val ty = target.y
        val tz = target.z

        return when (mode.value) {

            TpMode.RANDOM -> {
                val angle = Math.random() * 2 * Math.PI
                val dx = (cos(angle) * r).toFloat()
                val dz = (sin(angle) * r).toFloat()
                Triple(tx + dx, ty + yo, tz + dz)
            }

            TpMode.STRAFE -> {
                val rad = Math.toRadians(strafeAngle)
                val dx = (cos(rad) * r).toFloat()
                val dz = (sin(rad) * r).toFloat()
                Triple(tx + dx, ty + yo, tz + dz)
            }

            TpMode.BEHIND -> {
                val tYawRad = Math.toRadians(target.yaw.toDouble())
                val dx = (sin(tYawRad) * r).toFloat()
                val dz = (cos(tYawRad) * r).toFloat()
                Triple(tx + dx, ty + yo, tz + dz)
            }

            TpMode.SPEED -> {
                val dx = tx - sx
                val dz = tz - sz
                val dist = sqrt((dx * dx + dz * dz).toDouble()).toFloat()

                if (dist <= r) {
                    Triple(sx, sy, sz)
                } else {
                    val step = minOf(horizontalSpeed.value * 0.05f, dist - r)
                    val nx = sx + (dx / dist) * step
                    val nz = sz + (dz / dist) * step

                    val dyRaw = ty + yo - sy
                    val stepY = verticalSpeed.value * 0.05f

                    val ny = if (abs(dyRaw) < stepY) ty + yo
                    else sy + if (dyRaw > 0) stepY else -stepY

                    Triple(nx, ny, nz)
                }
            }
        }
    }

    enum class TpMode {
        RANDOM, STRAFE, BEHIND, SPEED
    }
}
