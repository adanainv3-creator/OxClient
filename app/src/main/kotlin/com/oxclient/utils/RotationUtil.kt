package com.oxclient.utils

import com.oxclient.core.proxy.EntityTracker
import org.cloudburstmc.math.vector.Vector3f
import kotlin.math.*

object RotationUtil {

    data class Rotation(val yaw: Float, val pitch: Float)

    fun toEntity(e: EntityTracker.TrackedEntity): Rotation =
        toPoint(e.x, e.y + 1.62f, e.z)

    fun toPoint(tx: Float, ty: Float, tz: Float): Rotation {
        val dx = tx - EntityTracker.selfX
        val dy = ty - (EntityTracker.selfY + 1.62f)
        val dz = tz - EntityTracker.selfZ
        val dist = sqrt(dx * dx + dz * dz).toDouble()
        val yaw   = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        val pitch = Math.toDegrees(-atan2(dy.toDouble(), dist)).toFloat()
        return Rotation(yaw, pitch)
    }

    fun approximate(r: Rotation, yawJitter: Float = 2f, pitchJitter: Float = 1f): Rotation {
        val y = r.yaw   + (Math.random() * yawJitter * 2 - yawJitter).toFloat()
        val p = r.pitch + (Math.random() * pitchJitter * 2 - pitchJitter).toFloat()
        return Rotation(y.coerceIn(-180f, 180f), p.coerceIn(-90f, 90f))
    }

    fun normalize(yaw: Float): Float {
        var y = yaw % 360f
        if (y > 180f)  y -= 360f
        if (y < -180f) y += 360f
        return y
    }

    fun angleDiff(a: Float, b: Float): Float = abs(normalize(a - b))

    fun fovCheck(e: EntityTracker.TrackedEntity, fovDeg: Float): Boolean {
        if (fovDeg >= 360f) return true
        val r = toEntity(e)
        return angleDiff(r.yaw, EntityTracker.selfYaw) <= fovDeg / 2f
    }

    fun buildMovePacket(
        yaw: Float,
        pitch: Float,
        teleport: Boolean = false,
        onGround: Boolean = true
    ): org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket =
        org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket().apply {
            runtimeEntityId       = EntityTracker.selfRuntimeId
            position              = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            rotation              = Vector3f.from(pitch, yaw, yaw)
            mode                  = if (teleport) org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket.Mode.TELEPORT
                                    else          org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket.Mode.NORMAL
            isOnGround            = onGround
            ridingRuntimeEntityId = 0L
        }
}
