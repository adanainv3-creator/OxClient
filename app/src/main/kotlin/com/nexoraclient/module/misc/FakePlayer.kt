package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.GameType
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket
import java.util.*

/**
 * FakePlayer — KillAura, CrystalAura vb. combat/utility modüllerini test etmek için
 * menzilde fakir oyuncu spawns et.
 *
 * Kullanım:
 * - Modülü aç → player etrafında fakeplayer spawns olur
 * - Count ayarıyla kaç tane olacağını belirt
 * - Distance ile ne kadar uzakta olacağını ayarla
 * - Health ile ne kadar can olacağını set et (cosmetic)
 * - Strafe aç kapak ile etrafında hareket ettir ya da durağan tut
 * - Modülü kapat → fake player'lar silinir
 */
class FakePlayer : BaseModule(
    name        = "FakePlayer",
    category    = ModuleCategory.MISC,
    description = "Test modülleri için sahte oyuncu spawns et (KillAura/CrystalAura testi)"
) {
    companion object {
        private const val TICK_INTERVAL_MS = 50L
        private var nextRuntimeId = 2000L
    }

    private val count           = int  ("Count",            1,    1,   10)
    private val distance        = float("Distance",         5f,  1f,  20f)
    private val health          = float("Health",           20f, 1f,  20f)
    private val strafe          = bool ("Strafe",           false)
    private val strafeRadius    = float("Strafe Radius",    3f,  1f,  10f)
    private val strafeSpeed     = float("Strafe Speed",     2f,  0.1f, 5f)

    @Volatile private var spawnedPlayers = mutableListOf<FakePlayerData>()
    private var tickJob: Job? = null

    data class FakePlayerData(
        val runtimeId: Long,
        val uuid: UUID,
        var posX: Float,
        var posY: Float,
        var posZ: Float,
        var yaw: Float,
        var pitch: Float,
        var angle: Float = 0f
    )

    override fun onEnable() {
        super.onEnable()
        spawnedPlayers.clear()
        PacketEventBus.register(this)
        tickJob = scope.launch {
            spawnFakePlayers()
            tickLoop()
        }
    }

    override fun onDisable() {
        tickJob?.cancel()
        despawnFakePlayers()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    private suspend fun spawnFakePlayers() {
        val session = PacketEventBus.currentSession ?: return
        repeat(count.value) { i ->
            val runtimeId = nextRuntimeId++
            val uuid = UUID.randomUUID()
            
            val angle = (i.toFloat() / count.value) * 360f
            val dist = distance.value
            val radians = Math.toRadians(angle.toDouble())
            val dx = (dist * kotlin.math.cos(radians)).toFloat()
            val dz = (dist * kotlin.math.sin(radians)).toFloat()

            val posX = EntityTracker.selfX + dx
            val posY = EntityTracker.selfY
            val posZ = EntityTracker.selfZ + dz

            val packet = AddPlayerPacket().apply {
                this.uuid = uuid
                this.username = "FakePlayer_$i"
                this.uniqueEntityId = runtimeId
                this.runtimeEntityId = runtimeId
                this.position = Vector3f.from(posX, posY, posZ)
                this.rotation = Vector3f.from(0f, angle, angle)
                this.motion = Vector3f.ZERO
                this.gameType = GameType.SURVIVAL
            }
            session.serverBound(packet)

            val fakeData = FakePlayerData(runtimeId, uuid, posX, posY, posZ, angle, 0f)
            spawnedPlayers.add(fakeData)

            try {
                delay(50L)
            } catch (_: Exception) {}
        }
    }

    private fun despawnFakePlayers() {
        val session = PacketEventBus.currentSession ?: return
        for (fake in spawnedPlayers) {
            val packet = RemoveEntityPacket().apply {
                uniqueEntityId = fake.runtimeId
            }
            session.serverBound(packet)
        }
        spawnedPlayers.clear()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                if (strafe.value) updateStrafing()
            }
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun updateStrafing() {
        val session = PacketEventBus.currentSession ?: return
        for (fake in spawnedPlayers) {
            fake.angle += strafeSpeed.value
            if (fake.angle >= 360f) fake.angle -= 360f

            val radians = Math.toRadians(fake.angle.toDouble())
            val radius = strafeRadius.value
            val centerX = EntityTracker.selfX
            val centerZ = EntityTracker.selfZ

            fake.posX = (centerX + radius * kotlin.math.cos(radians)).toFloat()
            fake.posZ = (centerZ + radius * kotlin.math.sin(radians)).toFloat()
            fake.yaw = fake.angle

            try {
                val updatePacket = org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket().apply {
                    runtimeEntityId = fake.runtimeId
                    position = Vector3f.from(fake.posX, fake.posY, fake.posZ)
                    rotation = Vector3f.from(fake.pitch, fake.yaw, fake.yaw)
                    isOnGround = true
                    isTeleported = false
                }
                session.serverBound(updatePacket)
            } catch (_: Exception) {}
        }
    }
}
