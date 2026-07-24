package com.oxclient.module.misc

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ChatAdvertiser : BaseModule(
    name        = "ChatAdvertiser",
    category    = ModuleCategory.MISC,
    description = "Chat Spam (TPA/PVP/PVPx)"
) {
    enum class Mode { TPA, PVP, PVPx }

    private val mode              = enum("Mode", Mode.PVP)
    private val shortcut          = bool("Shortcut", false)

    private val pvpMessages = listOf(
        "> @here tpa pvp 1v1 little kiddos | %RANDOM% | OxClient",
        "> @here 1v1 tpa pvp all ez | %RANDOM% | OxClient",
        "> @here tpa to pvp nns | %RANDOM% | OxClient",
        "> @here tpa for pvp all EZZ | %RANDOM% | OxClient",
        "> @here tpa 1v1 im bored fr | %RANDOM% | OxClient",
        "> @here anyone tpa pvp cant be that scared | %RANDOM% | OxClient",
        "> @here tpa pvp free win here | %RANDOM% | OxClient",
        "> @here tpa 1v1 no crystal easy | %RANDOM% | OxClient",
        "> @here tpa pvp lets go who wants smoke | %RANDOM% | OxClient",
        "> @here tpa all cracked pvpers welcome | %RANDOM% | OxClient",
        "> @here tpa pvp best client wins obviously | %RANDOM% | OxClient",
        "> @here tpa 1v1 quick fight nobody scared right | %RANDOM% | OxClient",
        "> @here tpa pvp bring your best totem | %RANDOM% | OxClient",
        "> @here tpa pvp all skill issue if you decline | %RANDOM% | OxClient"
    )

    private val junkChars = "abcdefghjklmnopqrstuvwxyz0123456789"
    private var scheduler: ScheduledExecutorService? = null
    private var currentPvpMessageIndex = 0
    private var activeSession: OxRelaySession? = null

    private val playerLastDistances = ConcurrentHashMap<Long, Float>()
    private val playerLastNotifiedMs = ConcurrentHashMap<Long, Long>()

    override fun onEnable() {
        super.onEnable()
        playerLastDistances.clear()
        playerLastNotifiedMs.clear()
        currentPvpMessageIndex = 0

        scheduler = Executors.newSingleThreadScheduledExecutor().also { exec ->
            when (mode.value) {
                Mode.TPA -> {
                    exec.scheduleAtFixedRate({ sendTpaCommand() }, 0, 500, TimeUnit.MILLISECONDS)
                }
                Mode.PVP -> {
                    exec.scheduleAtFixedRate({ sendPvpMessage() }, 0, 3000, TimeUnit.MILLISECONDS)
                }
                Mode.PVPx -> {
                    exec.scheduleAtFixedRate({ checkRunningPlayers() }, 100, 100, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        scheduler?.shutdownNow()
        scheduler = null
        playerLastDistances.clear()
        playerLastNotifiedMs.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        activeSession = event.session
    }

    private fun sendTpaCommand() {
        val session = activeSession ?: return
        val randomLetter = junkChars[Random.nextInt(junkChars.length)]
        val command = "tpa $randomLetter"

        try {
            session.sendToServer(buildCommandPacket(command))
        } catch (e: Exception) {
        }
    }

    private fun sendPvpMessage() {
        val session = activeSession ?: return
        val message = pvpMessages[currentPvpMessageIndex]
            .replace("%RANDOM%", randomJunk())
        currentPvpMessageIndex = (currentPvpMessageIndex + 1) % pvpMessages.size

        try {
            session.sendToServer(buildTextPacket(message))
        } catch (e: Exception) {
        }
    }

    private fun checkRunningPlayers() {
        val session = activeSession ?: return
        val now = System.currentTimeMillis()

        EntityTracker.getAll()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId && it.isPlayer }
            .forEach { player ->
                val currentDist = EntityTracker.distanceTo(player)
                val lastDist = playerLastDistances[player.runtimeId] ?: currentDist

                playerLastDistances[player.runtimeId] = currentDist

                // Oyuncu 2 blok+ uzaklaşıyorsa "kaçıyor"
                if (currentDist > lastDist + 2f) {
                    val lastNotif = playerLastNotifiedMs[player.runtimeId] ?: 0L
                    if (now - lastNotif > 5000L) {  // 5 saniyede bir
                        playerLastNotifiedMs[player.runtimeId] = now

                        val messages = listOf(
                            "> @${player.name} Dont Run Little NN | OxClient | %RANDOM%",
                            "> @here @${player.name} is definitely runfag | random | OxClient | %RANDOM%"
                        )

                        val selectedMsg = messages[Random.nextInt(messages.size)]
                            .replace("%RANDOM%", randomJunk())

                        try {
                            session.sendToServer(buildTextPacket(selectedMsg))
                        } catch (e: Exception) {
                        }
                    }
                }
            }
    }

    private fun buildTextPacket(message: String): TextPacket = TextPacket().apply {
        type               = TextPacket.Type.CHAT
        isNeedsTranslation = false
        sourceName         = "__ox_internal__"
        xuid               = ""
        platformChatId     = ""
        setMessage(message)
        setFilteredMessage("")
    }

    private fun buildCommandPacket(command: String): CommandRequestPacket = CommandRequestPacket().apply {
        this.command = command
        this.commandOriginData = CommandOriginData().apply {
            type = CommandOriginType.PLAYER
            uuid = UUID.randomUUID()
            requestId = ""
        }
        isInternal = false
    }

    private fun randomJunk(): String {
        val len = Random.nextInt(12, 23)
        return buildString(len) {
            repeat(len) {
                append(junkChars[Random.nextInt(junkChars.length)])
            }
        }
    }
}
