package com.oxclient.module.misc

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket.Type
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class ChatSpammer : BaseModule(
    name = "ChatSpammer",
    category = ModuleCategory.MISC,
    description = "Spam ve PVP totem sayacı"
) {

    companion object {
        private const val JUNK_CHARS      = "abcdefghjklmnopqrstuvwxyzıış0123456789"
        private const val DEFAULT_MESSAGES = "Mobile PvP Tpa"
        private const val DEFAULT_TAG      = "OxClient"
    }

    // ── Ortak ayarlar ────────────────────────────────────────────────────────
    private val modeVal    = string("Mode", "SPAMMER")   // SPAMMER | PVP
    private val tag        = string("Tag", DEFAULT_TAG)
    private val prefix     = string("Prefix", ">")

    // ── Spammer ayarları ─────────────────────────────────────────────────────
    private val intervalMs   = int("Interval",     5000, 1000, 10000)
    private val junkMin      = int("JunkMinLength", 18,   4,   64)
    private val junkMax      = int("JunkMaxLength", 28,   4,   64)
    private val messagesRaw  = string("Messages", DEFAULT_MESSAGES)

    // ── PVP ayarları ─────────────────────────────────────────────────────────
    // Boş bırakılırsa en yakın oyuncuyu izler
    private val pvpTarget    = string("PvpTarget", "")
    // Her totem patladığında chat at
    private val pvpSendOnPop = string("PvpSendOnPop", "true")

    // ── Runtime state ────────────────────────────────────────────────────────
    private var tickJob             : kotlinx.coroutines.Job? = null
    private var loopIntervalCache   = -1
    private val totemPopCounts      = ConcurrentHashMap<String, Int>()
    private val pvpListener         = PvpPacketListener()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onEnable() {
        super.onEnable()
        totemPopCounts.clear()
        if (currentMode() == Mode.PVP) {
            PacketEventBus.register(pvpListener)
        } else {
            restartLoop()
        }
    }

    override fun onDisable() {
        super.onDisable()
        tickJob?.cancel()
        tickJob = null
        PacketEventBus.unregister(pvpListener)
        totemPopCounts.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mode
    // ─────────────────────────────────────────────────────────────────────────

    private enum class Mode { SPAMMER, PVP }

    private fun currentMode(): Mode =
        if (modeVal.value.trim().uppercase() == "PVP") Mode.PVP else Mode.SPAMMER

    // ─────────────────────────────────────────────────────────────────────────
    // Spammer
    // ─────────────────────────────────────────────────────────────────────────

    private fun restartLoop() {
        tickJob?.cancel()
        loopIntervalCache = intervalMs.value
        tickJob = launchTickLoop(intervalMs.value.toLong()) {
            if (intervalMs.value != loopIntervalCache) {
                restartLoop()
                return@launchTickLoop
            }
            sendSpamMessage()
        }
    }

    private fun currentMessages(): List<String> =
        messagesRaw.value.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(DEFAULT_MESSAGES) }

    private fun randomJunk(): String {
        val lo  = minOf(junkMin.value, junkMax.value)
        val hi  = maxOf(junkMin.value, junkMax.value)
        val len = if (lo == hi) lo else Random.nextInt(lo, hi + 1)
        return (1..len).map { JUNK_CHARS[Random.nextInt(JUNK_CHARS.length)] }.joinToString("")
    }

    private fun buildSpamMessage(): String {
        val list = currentMessages()
        val base = list[Random.nextInt(list.size)]
        val junk = randomJunk()
        return "${prefix.value} $base | $junk | ${tag.value}"
    }

    private fun sendSpamMessage() {
        val session = PacketEventBus.currentSession
        if (session == null || !session.isServerReady) return
        sendChat(session, buildSpamMessage())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PVP — totem pop mesajı
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildPvpMessage(playerName: String, popCount: Int): String {
        val junk = randomJunk()
        return "${prefix.value} @$playerName Popped $popCount ${if (popCount == 1) "totem" else "totems"} he is literally Totemfag | $junk | ${tag.value}"
    }

    private fun onTotemPopped(runtimeId: Long) {
        val session = PacketEventBus.currentSession
        if (session == null || !session.isServerReady) return

        val entity = EntityTracker.getById(runtimeId) ?: return
        if (!entity.isPlayer) return

        // Hedef filtresi
        val targetName = pvpTarget.value.trim()
        if (targetName.isNotEmpty() && !entity.name.equals(targetName, ignoreCase = true)) return

        // Pop sayısını artır
        val name  = entity.name.ifEmpty { "unknown" }
        val count = (totemPopCounts[name] ?: 0) + 1
        totemPopCounts[name] = count

        if (pvpSendOnPop.value.trim().lowercase() == "true") {
            sendChat(session, buildPvpMessage(name, count))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chat gönderme
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendChat(session: com.oxclient.core.relay.OxRelaySession, message: String) {
        try {
            val packet = TextPacket().apply {
                type              = Type.CHAT
                isNeedsTranslation = false
                sourceName        = ""
                xuid              = ""
                platformChatId    = ""
            }
            packet.setMessage(message)
            packet.setFilteredMessage("")
            session.sendToServer(packet)
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet listener — totem pop yakalama
    // ─────────────────────────────────────────────────────────────────────────

    private inner class PvpPacketListener : PacketEventBus.PacketListener {
        override fun onPacket(event: PacketEvent) {
            val p = event.packet
            if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
            if (p !is EntityEventPacket) return
            if (p.type == EntityEventType.CONSUME_TOTEM) {
                onTotemPopped(p.runtimeEntityId)
            }
        }
    }
}
