package com.oxclient.module.misc

import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket.Type
import kotlin.random.Random

class ChatSpammer : BaseModule(
    name = "ChatSpammer",
    category = ModuleCategory.MISC,
    description = "Şablon formatlı rastgele chat spam"
) {

    companion object {
        private const val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyzıış0123456789"

        private const val DEFAULT_MESSAGES = "Mobile PvP Tpa"
        private const val DEFAULT_TAG      = "OxClient v1 Best Mobile Client"
    }

    private val intervalMs  = int("Interval", 29000, 1000, 30000)
    private val junkMin     = int("JunkMinLength", 18, 4, 64)
    private val junkMax     = int("JunkMaxLength", 28, 4, 64)
    private val messagesRaw = string("Messages", DEFAULT_MESSAGES)
    private val tag         = string("Tag", DEFAULT_TAG)
    private val prefix      = string("Prefix", ">")

    private var tickJob: kotlinx.coroutines.Job? = null
    private var loopIntervalCache = -1

    override fun onEnable() {
        super.onEnable()
        restartLoop()
    }

    override fun onDisable() {
        super.onDisable()
        tickJob?.cancel()
        tickJob = null
    }

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
        val lo = minOf(junkMin.value, junkMax.value)
        val hi = maxOf(junkMin.value, junkMax.value)
        val len = if (lo == hi) lo else Random.nextInt(lo, hi + 1)
        return (1..len).map { JUNK_CHARS[Random.nextInt(JUNK_CHARS.length)] }.joinToString("")
    }

    private fun buildMessage(): String {
        val list = currentMessages()
        val base = list[Random.nextInt(list.size)]
        val junk = randomJunk()
        return "${prefix.value} $base | $junk | ${tag.value}"
    }

    private fun sendSpamMessage() {
        val session = PacketEventBus.currentSession
        if (session == null || !session.isServerReady) return

        val finalMessage = buildMessage()

        try {
            val packet = TextPacket().apply {
                type = Type.CHAT
                isNeedsTranslation = false
                sourceName = ""
                xuid = ""
                platformChatId = ""
            }
            packet.setMessage(finalMessage)
            packet.setFilteredMessage("")
            session.sendToServer(packet)
        } catch (e: Exception) {
        }
    }
}
