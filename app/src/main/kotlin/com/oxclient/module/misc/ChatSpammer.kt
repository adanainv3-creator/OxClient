package com.oxclient.module.misc

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class ChatSpammer : BaseModule(
    name        = "ChatSpammer",
    category    = ModuleCategory.MISC,
    description = "Chat prefix + totem pop sayacı"
) {
    companion object {
        private const val VERSION  = "v1.2"
        private const val TAG_LINE = "OxClient $VERSION"
        private const val PVP_TAIL = "by OxClient | Best Mobile Client"
        private val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyz0123456789"
        private val JUNK_RANGE = 12..22
    }

    private val shortcut = bool("Shortcut", false)

    private val popCounts = ConcurrentHashMap<String, Int>()

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val p = event.packet) {

            is TextPacket -> {
                if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
                if (p.sourceName == "__ox_internal__") return

                val raw = p.message?.trim() ?: return
                if (raw.isEmpty() || raw.startsWith("/")) return

                val formatted = "> $raw | $TAG_LINE"
                event.cancelAndReplace(buildTextPacket(formatted))
            }

            is EntityEventPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
                if (p.runtimeEntityId == EntityTracker.selfRuntimeId) return

                val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (!typeStr.contains("TOTEM")) return

                val entity = EntityTracker.getById(p.runtimeEntityId)
                val name  = entity?.name?.takeIf { it.isNotEmpty() } ?: "unknown"
                val count = (popCounts[name] ?: 0) + 1
                popCounts[name] = count

                val session = PacketEventBus.currentSession
                if (session == null || !session.isServerReady) return

                val message = "> @$name Popped $count Totem $PVP_TAIL | ${randomJunk()}"
                session.sendToServer(buildTextPacket(message))
            }
        }
    }

    override fun onEnable() {
        super.onEnable()
        popCounts.clear()
    }

    override fun onDisable() {
        super.onDisable()
        popCounts.clear()
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

    private fun randomJunk(): String {
        val len = Random.nextInt(JUNK_RANGE.first, JUNK_RANGE.last + 1)
        return buildString(len) { repeat(len) { append(JUNK_CHARS[Random.nextInt(JUNK_CHARS.length)]) } }
    }
}
