package com.rubidiumclient.module.misc

import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ChatAdvertiser : BaseModule(
    name        = "ChatAdvertiser",
    category    = ModuleCategory.MISC,
    description = "Chat Spam (TPA/TpaHere/PVP/Ads)"
) {
    enum class Mode { TPA, TpaHere, PVP, Ads, Spammer }

    private val mode              = enum("Mode", Mode.PVP)
    private val shortcut          = bool("Shortcut", false)

    private val adsMessages = listOf(
        "> @here Use Best Mobile Client | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client",
        "> @here Best PvP Client for Bedrock | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client",
        "> @here Free Download Rubidium Client | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client",
        "> @here Join our Discord for updates | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client",
        "> @here Rubidium Client trusted by hundreds | discord.gg\\At5VHua7ZP | %RANDOM% | Rubidium Client"
    )

    private val pvpMessages = listOf(
        "> @here tpa pvp 1v1 little kiddos | %RANDOM% | Rubidium Client",
        "> @here 1v1 tpa pvp all ez | %RANDOM% | Rubidium Client",
        "> @here tpa to pvp nns | %RANDOM% | Rubidium Client",
        "> @here tpa for pvp all EZZ | %RANDOM% | Rubidium Client",
        "> @here tpa 1v1 im bored fr | %RANDOM% | Rubidium Client",
        "> @here anyone tpa pvp cant be that scared | %RANDOM% | Rubidium Client",
        "> @here tpa pvp free win here | %RANDOM% | Rubidium Client",
        "> @here tpa 1v1 no crystal easy | %RANDOM% | Rubidium Client",
        "> @here tpa pvp lets go who wants smoke | %RANDOM% | Rubidium Client",
        "> @here tpa all cracked pvpers welcome | %RANDOM% | Rubidium Client",
        "> @here tpa pvp best client wins obviously | %RANDOM% | Rubidium Client",
        "> @here tpa 1v1 quick fight nobody scared right | %RANDOM% | Rubidium Client",
        "> @here tpa pvp bring your best totem | %RANDOM% | Rubidium Client",
        "> @here tpa pvp all skill issue if you decline | %RANDOM% | Rubidium Client"
    )

    private val spammerMessages = listOf(
        "> nobody in this lobby can touch my pvp | Rubidium v1.4 | %RANDOM%",
        "> crystal pvp on point today | Rubidium v1.4 | %RANDOM%",
        "> combos hitting different with Rubidium | Rubidium v1.4 | %RANDOM%",
        "> clean w keys clean hits | Rubidium v1.4 | %RANDOM%",
        "> outplaying everyone in this server | Rubidium v1.4 | %RANDOM%",
        "> best pvp client on bedrock hands down | Rubidium v1.4 | %RANDOM%",
        "> another easy win in the books | Rubidium v1.4 | %RANDOM%",
        "> totem popping never looked this smooth | Rubidium v1.4 | %RANDOM%",
        "> ping doesnt matter when your pvp is clean | Rubidium v1.4 | %RANDOM%",
        "> running this lobby with Rubidium | Rubidium v1.4 | %RANDOM%",
        "> knockback reads on point every fight | Rubidium v1.4 | %RANDOM%",
        "> tpa me if you think you can win | Rubidium v1.4 | %RANDOM%",
        "> crystal aura carrying every fight | Rubidium v1.4 | %RANDOM%",
        "> movement smooth combos smoother | Rubidium v1.4 | %RANDOM%",
        "> undefeated on this account today | Rubidium v1.4 | %RANDOM%",
        "> bedrock pvp solved with Rubidium | Rubidium v1.4 | %RANDOM%",
        "> another opponent another totem cancel | Rubidium v1.4 | %RANDOM%",
        "> this server hasnt seen pvp like this | Rubidium v1.4 | %RANDOM%",
        "> Rubidium users just built different | Rubidium v1.4 | %RANDOM%",
        "> come test your pvp against the best | Rubidium v1.4 | %RANDOM%"
    )

    private val junkChars = "abcdefghjklmnopqrstuvwxyz0123456789"
    private var scheduler: ScheduledExecutorService? = null
    private var lastSpammerMessageIndex = -1
    private var lastPvpMessageIndex = -1
    private var lastAdsMessageIndex = -1
    private var activeSession: RubidiumRelaySession? = null

    override fun onEnable() {
        super.onEnable()
        lastSpammerMessageIndex = -1
        lastPvpMessageIndex = -1
        lastAdsMessageIndex = -1

        scheduler = Executors.newSingleThreadScheduledExecutor().also { exec ->
            when (mode.value) {
                Mode.TPA -> {
                    exec.scheduleAtFixedRate({ sendTpaCommand() }, 0, 2000, TimeUnit.MILLISECONDS)
                }
                Mode.TpaHere -> {
                    exec.scheduleAtFixedRate({ sendTpaHereCommand() }, 0, 2000, TimeUnit.MILLISECONDS)
                }
                Mode.PVP -> {
                    exec.scheduleAtFixedRate({ sendPvpMessage() }, 0, 22000, TimeUnit.MILLISECONDS)
                }
                Mode.Ads -> {
                    exec.scheduleAtFixedRate({ sendAdsMessage() }, 0, 10000, TimeUnit.MILLISECONDS) // 10 saniyeye değiştirildi
                }
                Mode.Spammer -> {
                    exec.scheduleAtFixedRate({ sendSpammerMessage() }, 0, 25000, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        activeSession = event.session
    }

    private fun sendTpaCommand() {
        val session = activeSession ?: return
        val randomLetter = junkChars[Random.nextInt(junkChars.length)]
        val command = "/tpa $randomLetter"

        try {
            session.sendToServer(buildCommandPacket(command))
        } catch (e: Exception) {
        }
    }

    private fun sendTpaHereCommand() {
        val session = activeSession ?: return
        val randomLetter = junkChars[Random.nextInt(junkChars.length)]
        val command = "/tpahere $randomLetter"

        try {
            session.sendToServer(buildCommandPacket(command))
        } catch (e: Exception) {
        }
    }

    private fun sendPvpMessage() {
        val session = activeSession ?: return

        var index: Int
        do {
            index = Random.nextInt(pvpMessages.size)
        } while (index == lastPvpMessageIndex && pvpMessages.size > 1)
        lastPvpMessageIndex = index

        val message = pvpMessages[index].replace("%RANDOM%", randomJunk())

        try {
            session.sendToServer(buildTextPacket(message))
        } catch (e: Exception) {
        }
    }

    private fun sendAdsMessage() {
        val session = activeSession ?: return

        var index: Int
        do {
            index = Random.nextInt(adsMessages.size)
        } while (index == lastAdsMessageIndex && adsMessages.size > 1)
        lastAdsMessageIndex = index

        val message = adsMessages[index].replace("%RANDOM%", randomJunk())

        try {
            session.sendToServer(buildTextPacket(message))
        } catch (e: Exception) {
        }
    }

    private fun sendSpammerMessage() {
        val session = activeSession ?: return

        var index: Int
        do {
            index = Random.nextInt(spammerMessages.size)
        } while (index == lastSpammerMessageIndex && spammerMessages.size > 1)
        lastSpammerMessageIndex = index

        val message = spammerMessages[index].replace("%RANDOM%", randomJunk())

        try {
            session.sendToServer(buildTextPacket(message))
        } catch (e: Exception) {
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
        this.commandOriginData = CommandOriginData(
            CommandOriginType.PLAYER,
            UUID.randomUUID(),
            "",
            0L
        )
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