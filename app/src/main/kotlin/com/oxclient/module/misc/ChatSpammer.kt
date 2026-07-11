package com.oxclient.module.misc

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
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
        private const val VERSION   = "v1.2"
        private const val TAG_LINE  = "OxClient $VERSION"
        private const val PVP_TAIL  = "by OxClient | Best Mobile Client"

        private val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyz0123456789"
        private val JUNK_RANGE = 12..22
    }

    // ── Per-player totem pop sayacı ───────────────────────────────────────────
    private val popCounts = ConcurrentHashMap<String, Int>()

    // ── Listener ──────────────────────────────────────────────────────────────
    private val listener = object : PacketEventBus.PacketListener {

        override val priority: Int = 50   // Diğer modüllerden önce çalışsın

        override fun onPacket(event: PacketEvent) {
            when (val p = event.packet) {

                // ── Kullanıcının yazdığı mesajları prefix'le ─────────────────
                is TextPacket -> {
                    if (!event.isClientToServer) return
                    // Zaten bizim gönderdiğimiz paket mi? (sonsuz döngü önlemi)
                    if (p.sourceName == "__ox_internal__") return

                    val raw = p.message?.trim() ?: return
                    if (raw.isEmpty()) return

                    // ">" ile başlayan komutlara/mesajlara müdahale etme
                    if (raw.startsWith("/")) return

                    val formatted = "> $raw | $TAG_LINE"
                    val replacement = buildTextPacket(formatted)
                    event.cancelAndReplace(replacement)
                }

                // ── Totem pop yakalama ────────────────────────────────────────
                is EntityEventPacket -> {
                    if (!event.isServerToClient) return
                    if (p.type != EntityEventType.CONSUME_TOTEM) return

                    // Kendi totemimiz patlıyorsa sayma
                    if (p.runtimeEntityId == EntityTracker.selfRuntimeId) return

                    val entity = EntityTracker.getById(p.runtimeEntityId) ?: return
                    if (!entity.isPlayer) return

                    val name  = entity.name.ifEmpty { "unknown" }
                    val count = (popCounts[name] ?: 0) + 1
                    popCounts[name] = count

                    val session = PacketEventBus.currentSession
                    if (session == null || !session.isServerReady) return

                    val junk    = randomJunk()
                    val message = "> @$name Popped $count Totem $PVP_TAIL | $junk"
                    session.sendToServer(buildTextPacket(message))
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        super.onEnable()
        popCounts.clear()
        PacketEventBus.register(listener)
    }

    override fun onDisable() {
        super.onDisable()
        PacketEventBus.unregister(listener)
        popCounts.clear()
    }

    // ── Yardımcı ──────────────────────────────────────────────────────────────

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
        return buildString(len) {
            repeat(len) { append(JUNK_CHARS[Random.nextInt(JUNK_CHARS.length)]) }
        }
    }
}
