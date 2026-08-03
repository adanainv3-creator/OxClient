package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.BlockTracker
import com.rubidiumclient.utils.BlockTracker.TrackedBlockType
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.UUID
import kotlin.math.sqrt

class AutoTravel : BaseModule(
    name        = "AutoTravel",
    category    = ModuleCategory.MISC,
    description = "Belirlenen Y seviyesine cikar, hedef X/Z koordinatina ucar, varinca /sethome atar. Opsiyonel Base Hunting: hareket etmeden, gecerken depolama bloklari bulunca ayrica /sethome atar"
) {
    enum class Stage { ASCEND, TRAVEL, ARRIVED }

    private val targetX      = string("Target X",      "0")
    private val targetZ      = string("Target Z",      "0")
    private val targetY      = string("Target Y",      "120")
    private val flySpeed     = float ("Fly Speed",     1.0f, 0.1f,         3f)
    private val ascendSpeed  = float ("Ascend Speed",  0.9f, 0.1f,         3f)
    private val arriveRadius = float ("Arrive Radius", 2f,   0.5f,         10f)
    private val homeName     = string("Home Name",     "target")
    private val shortcut     = bool  ("Shortcut",      false)

    private val baseHunting         = bool ("Base Hunting",         false)
    private val detectBarrel        = bool ("Barrel",               true)
    private val detectEnderChest    = bool ("Ender Chest",          true)
    private val detectShulker       = bool ("Shulker",              true)
    private val huntScanRange       = float("Hunt Scan Range",      48f, 8f,   256f)
    private val huntCooldownMinutes = float("Hunt Cooldown (min)",  3f,  0.5f, 30f)

    companion object {
        private const val TICK_MS = 250L
        private const val Y_BAND  = 1.5f
    }

    @Volatile private var tickJob: kotlinx.coroutines.Job? = null
    @Volatile private var stage: Stage = Stage.ASCEND
    @Volatile private var huntCounter = 1
    @Volatile private var lastHuntMs  = 0L

    override fun onEnable() {
        super.onEnable()
        stage = Stage.ASCEND
        huntCounter = 1
        lastHuntMs  = 0L
        tickJob = launchTickLoop(TICK_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        super.onDisable()
    }

    private fun tick() {
        val session = PacketEventBus.currentSession ?: return
        when (stage) {
            Stage.ASCEND  -> tickAscend(session)
            Stage.TRAVEL  -> tickTravel(session)
            Stage.ARRIVED -> tickArrived(session)
        }
        if (baseHunting.value) scanForBases(session)
    }

    // FIX: bazi mobil klavyeler "akilli noktalama" ile ASCII '-' yerine farkli
    // bir tire karakteri (unicode minus '\u2212', en dash '\u2013', em dash
    // '\u2014') basiyor. toFloatOrNull() bunlari taniyamiyor, parse null
    // donuyor ve kod sessizce 0f/120f varsayilanina dusuyordu — yani girilen
    // negatif koordinat sessizce "+ versiyonuna" (varsayilana) donusuyordu.
    // Once bu karakterleri ASCII '-'ya, ondalik icin ',' -> '.' ceviriyoruz.
    private fun parseCoord(raw: String, fallback: Float): Float {
        val cleaned = raw.trim()
            .replace('\u2212', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace(',', '.')
        return cleaned.toFloatOrNull() ?: fallback
    }

    private fun parsedTargetX(): Float = parseCoord(targetX.value, 0f)
    private fun parsedTargetZ(): Float = parseCoord(targetZ.value, 0f)
    private fun parsedTargetY(): Float = parseCoord(targetY.value, 120f)

    private fun tickAscend(session: RubidiumRelaySession) {
        val dy = parsedTargetY() - EntityTracker.selfY
        if (kotlin.math.abs(dy) <= Y_BAND) {
            stage = Stage.TRAVEL
            return
        }
        val liftY = if (dy > 0f) ascendSpeed.value else -ascendSpeed.value
        sendMotion(session, 0f, liftY, 0f)
    }

    private fun tickTravel(session: RubidiumRelaySession) {
        val dx = parsedTargetX() - EntityTracker.selfX
        val dz = parsedTargetZ() - EntityTracker.selfZ
        val dist = sqrt(dx * dx + dz * dz)

        if (dist <= arriveRadius.value) {
            stage = Stage.ARRIVED
            onArrived(session)
            return
        }

        val dirX = dx / dist
        val dirZ = dz / dist

        val dy = parsedTargetY() - EntityTracker.selfY
        val liftY = when {
            dy >  Y_BAND -> ascendSpeed.value * 0.5f
            dy < -Y_BAND -> -ascendSpeed.value * 0.5f
            else         -> 0f
        }

        sendMotion(session, dirX * flySpeed.value, liftY, dirZ * flySpeed.value)
    }

    private fun tickArrived(session: RubidiumRelaySession) {
        sendMotion(session, 0f, 0f, 0f)
    }

    private fun onArrived(session: RubidiumRelaySession) {
        sendMotion(session, 0f, 0f, 0f)
        session.serverBound(buildCommandPacket("/sethome ${homeName.value}"))
        announceLocal(session, "§a[AutoPositionGoing] /sethome ${homeName.value} => Successful")
    }

    private fun sendMotion(session: RubidiumRelaySession, x: Float, y: Float, z: Float) {
        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(x, y, z)
        })
    }

    private fun scanForBases(session: RubidiumRelaySession) {
        val now = System.currentTimeMillis()
        val cooldownMs = (huntCooldownMinutes.value * 60_000L).toLong()
        if (now - lastHuntMs < cooldownMs) return

        val cx = EntityTracker.selfX
        val cy = EntityTracker.selfY
        val cz = EntityTracker.selfZ

        val found = BlockTracker.getAllInRange(cx, cy, cz, huntScanRange.value)
            .firstOrNull { isWantedHuntType(it.type) } ?: return

        lastHuntMs = now
        val huntHomeName = "base$huntCounter"
        huntCounter++

        session.serverBound(buildCommandPacket("/sethome $huntHomeName"))
        announceLocal(session, "§a[AutoPositionGoing] /sethome $huntHomeName => Successful")
    }

    private fun isWantedHuntType(type: TrackedBlockType): Boolean = when (type) {
        TrackedBlockType.BARREL      -> detectBarrel.value
        TrackedBlockType.ENDER_CHEST -> detectEnderChest.value
        TrackedBlockType.SHULKER_BOX -> detectShulker.value
        else -> false
    }

    private fun buildCommandPacket(command: String): CommandRequestPacket = CommandRequestPacket().apply {
        this.command = command
        this.commandOriginData = CommandOriginData(
            CommandOriginType.PLAYER,
            UUID.randomUUID(),
            "",
            0L
        )
        isInternal = false
    }

    private fun announceLocal(session: RubidiumRelaySession, message: String) {
        session.clientBound(TextPacket().apply {
            type               = TextPacket.Type.SYSTEM
            isNeedsTranslation = false
            sourceName         = ""
            xuid               = ""
            platformChatId     = ""
            setMessage(message)
            setFilteredMessage("")
        })
    }
}
