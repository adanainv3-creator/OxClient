
package com.nexoraclient.module.misc

import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.core.relay.NexoraRelaySession
import com.nexoraclient.events.PacketEvent
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.BaseModule
import com.nexoraclient.module.ModuleCategory
import com.nexoraclient.module.social.isFriendEntity
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ChatAdvertiser : BaseModule(
    name        = "ChatAdvertiser",
    category    = ModuleCategory.MISC,
    description = "Chat Spam (TPA/PVP/PVPx/KillStreak)"
) {
    enum class Mode { TPA, PVP, PVPx, KillStreak }

    private val mode              = enum("Mode", Mode.PVP)
    private val shortcut          = bool("Shortcut", false)

    private val pvpMessages = listOf(
        "> @here tpa pvp 1v1 little kiddos | %RANDOM% | Nexora Client",
        "> @here 1v1 tpa pvp all ez | %RANDOM% | Nexora Client",
        "> @here tpa to pvp nns | %RANDOM% | Nexora Client",
        "> @here tpa for pvp all EZZ | %RANDOM% | Nexora Client",
        "> @here tpa 1v1 im bored fr | %RANDOM% | Nexora Client",
        "> @here anyone tpa pvp cant be that scared | %RANDOM% | Nexora Client",
        "> @here tpa pvp free win here | %RANDOM% | Nexora Client",
        "> @here tpa 1v1 no crystal easy | %RANDOM% | Nexora Client",
        "> @here tpa pvp lets go who wants smoke | %RANDOM% | Nexora Client",
        "> @here tpa all cracked pvpers welcome | %RANDOM% | Nexora Client",
        "> @here tpa pvp best client wins obviously | %RANDOM% | Nexora Client",
        "> @here tpa 1v1 quick fight nobody scared right | %RANDOM% | Nexora Client",
        "> @here tpa pvp bring your best totem | %RANDOM% | Nexora Client",
        "> @here tpa pvp all skill issue if you decline | %RANDOM% | Nexora Client"
    )

    private val junkChars = "abcdefghjklmnopqrstuvwxyz0123456789"
    private var scheduler: ScheduledExecutorService? = null
    private var currentPvpMessageIndex = 0
    private var activeSession: NexoraRelaySession? = null

    private val playerLastDistances = ConcurrentHashMap<Long, Float>()
    private val playerLastNotifiedMs = ConcurrentHashMap<Long, Long>()

    private val recentHitsByMe = ConcurrentHashMap<Long, Long>()
    private val recentKillHandled = ConcurrentHashMap<Long, Long>()
    @Volatile private var killStreak = 0
    private val SELF_HIT_WINDOW_MS = 3000L
    private val KILL_DEBOUNCE_MS = 1500L

    override fun onEnable() {
        super.onEnable()
        playerLastDistances.clear()
        playerLastNotifiedMs.clear()
        recentHitsByMe.clear()
        recentKillHandled.clear()
        killStreak = 0
        currentPvpMessageIndex = 0

        if (mode.value == Mode.KillStreak) return // tamamen paket tetiklemeli, scheduler gerekmiyor

        scheduler = Executors.newSingleThreadScheduledExecutor().also { exec ->
            when (mode.value) {
                Mode.TPA -> {
                    exec.scheduleAtFixedRate({ sendTpaCommand() }, 0, 2000, TimeUnit.MILLISECONDS)
                }
                Mode.PVP -> {
                    exec.scheduleAtFixedRate({ sendPvpMessage() }, 0, 3000, TimeUnit.MILLISECONDS)
                }
                Mode.PVPx -> {
                    exec.scheduleAtFixedRate({ checkRunningPlayers() }, 100, 100, TimeUnit.MILLISECONDS)
                }
                Mode.KillStreak -> {}
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        scheduler?.shutdownNow()
        scheduler = null
        playerLastDistances.clear()
        playerLastNotifiedMs.clear()
        recentHitsByMe.clear()
        recentKillHandled.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        activeSession = event.session

        if (mode.value != Mode.KillStreak) return

        when (val p = event.packet) {
            is InventoryTransactionPacket -> {
                if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
                if (p.transactionType != InventoryTransactionType.ITEM_USE_ON_ENTITY) return
                if (p.actionType != 1) return
                recentHitsByMe[p.runtimeEntityId] = System.currentTimeMillis()
            }
            is EntityEventPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
                val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (!typeStr.contains("DEATH")) return
                if (p.runtimeEntityId == EntityTracker.selfRuntimeId) handleSelfDeath()
                else handleKill(p.runtimeEntityId)
            }
            is UpdateAttributesPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
                val health = p.attributes.find { it.name == "minecraft:health" } ?: return
                if (health.value > 0f) return
                if (p.runtimeEntityId == EntityTracker.selfRuntimeId) handleSelfDeath()
                else handleKill(p.runtimeEntityId)
            }
            else -> {}
        }
    }

    private fun handleKill(runtimeId: Long) {
        val entity = EntityTracker.getById(runtimeId) ?: return
        if (!entity.isPlayer) return
        if (entity.isFriendEntity) return

        val now = System.currentTimeMillis()
        val last = recentKillHandled[runtimeId]
        if (last != null && now - last < KILL_DEBOUNCE_MS) return
        recentKillHandled[runtimeId] = now

        val hitAt = recentHitsByMe.remove(runtimeId) ?: return
        if (now - hitAt > SELF_HIT_WINDOW_MS) return

        val name = entity.name.takeIf { it.isNotEmpty() } ?: "unknown"
        killStreak++

        val session = activeSession ?: return
        val msg = if (killStreak % 5 == 0) {
            "> @here $killStreak KILLSTREAK!! @$name just died | Nexora Client | ${randomJunk()}"
        } else {
            "> @here EZ @$name killed ($killStreak streak) | Nexora Client | ${randomJunk()}"
        }
        try { session.sendToServer(buildTextPacket(msg)) } catch (e: Exception) {}
    }

    private fun handleSelfDeath() {
        if (killStreak < 2) { killStreak = 0; return } // tek kill'de "streak bozuldu" demek anlamsız
        val broken = killStreak
        killStreak = 0
        val session = activeSession ?: return
        val msg = "> @here streak of $broken ended | still goated | Nexora Client | ${randomJunk()}"
        try { session.sendToServer(buildTextPacket(msg)) } catch (e: Exception) {}
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
                            "> @${player.name} Dont Run Little NN | Nexora Client | %RANDOM%",
                            "> @here @${player.name} is definitely runfag | random | Nexora Client | %RANDOM%"
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
