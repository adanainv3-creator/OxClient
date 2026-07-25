package com.oxclient.module.movement

import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

class Timer : BaseModule(
    name        = "Timer",
    category    = ModuleCategory.MOVEMENT,
    description = "Server'a giden hareket packetlerini çoğaltarak hız kazandırır"
) {
    companion object {
        private const val MAX_EXTRA_TICKS = 5
        private const val DELTA_EPSILON   = 1e-4f
    }

    private val speed = float("Speed", 1.5f, 1.0f, 5.0f)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        val packet  = event.packet as? PlayerAuthInputPacket ?: return
        val basePos = packet.position ?: return
        val delta   = packet.delta ?: return

        if (isNegligible(delta)) return

        val extraTicks = resolveExtraTicks()
        if (extraTicks <= 0) return

        injectTicks(event.session, packet, basePos, delta, packet.tick, extraTicks)
    }

    private fun resolveExtraTicks(): Int {
        val overshoot = speed.value - 1f
        if (overshoot <= 0f) return 0

        val whole = floor(overshoot).toInt().coerceIn(0, MAX_EXTRA_TICKS)
        val fraction = overshoot - whole

        return if (fraction > 0f && Random.nextFloat() < fraction) {
            (whole + 1).coerceAtMost(MAX_EXTRA_TICKS)
        } else {
            whole
        }
    }

    private fun injectTicks(
        session : OxRelaySession,
        source  : PlayerAuthInputPacket,
        basePos : Vector3f,
        delta   : Vector3f,
        baseTick: Long,
        count   : Int
    ) {
        for (step in 1..count) {
            val extrapolated = source.clone().apply {
                position = Vector3f.from(
                    basePos.x + delta.x * step,
                    basePos.y + delta.y * step,
                    basePos.z + delta.z * step
                )
                tick = baseTick + step
            }
            runCatching { session.sendToServer(extrapolated) }
        }
    }

    private fun isNegligible(v: Vector3f): Boolean =
        abs(v.x) < DELTA_EPSILON && abs(v.y) < DELTA_EPSILON && abs(v.z) < DELTA_EPSILON
}
