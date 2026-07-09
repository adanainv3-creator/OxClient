package com.oxclient.module.misc

import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import com.oxclient.ui.overlay.OverlayLogger
import org.cloudburstmc.protocol.bedrock.data.TextPacketType
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import kotlin.random.Random

/**
 * ChatSpammer — her tick'te sabit bir ŞABLONA göre mesaj üretip sunucuya
 * TextPacket olarak gönderir.
 *
 * Şablon (her mesaj birebir bu formatta):
 *   "> {mesaj} | {rastgele çöp string} | {marka/tag}"
 *
 * Örnek çıktı:
 *   "> Test | skejdkj6hkıdeosuj38jkeşedn | OxClient v1 Best Mobile Client"
 *
 * - {mesaj}   → "Messages" ayarındaki listeden (| ile ayrılmış) rastgele seçilir.
 * - {çöp}     → sunucunun aynı mesajı tekrar tekrar filtrelemesini engellemek
 *               için her seferinde farklı, rastgele üretilen alfanümerik dizi.
 * - {marka}   → "Tag" ayarında sabit, her mesajda aynı kalır.
 */
class ChatSpammer : BaseModule(
    name = "ChatSpammer",
    category = ModuleCategory.MISC,
    description = "Şablon formatlı rastgele chat spam"
) {

    companion object {
        private const val TAG = "ChatSpammer"

        // Örnekteki gibi Türkçe karakterleri de içeren geniş bir havuz
        private const val JUNK_CHARS = "abcdefghijklmnopqrstuvwxyzıışğüöç0123456789"

        private const val DEFAULT_MESSAGES = "Test"
        private const val DEFAULT_TAG      = "OxClient v1 Best Mobile Client"
    }

    // ── Ayarlar ─────────────────────────────────────────────────────────────

    private val intervalMs  = int("Interval", 5000, 1000, 30000)
    private val junkMin     = int("JunkMinLength", 18, 4, 64)
    private val junkMax     = int("JunkMaxLength", 28, 4, 64)
    private val messagesRaw = string("Messages", DEFAULT_MESSAGES)
    private val tag         = string("Tag", DEFAULT_TAG)
    private val prefix      = string("Prefix", ">")

    // ── Tick loop ─────────────────────────────────────────────────────────

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
            // Interval çalışırken değiştiyse loop'u yeni değerle yeniden kur
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
        // min > max girilse bile crash olmasın diye güvenli aralık
        val lo = minOf(junkMin.value, junkMax.value)
        val hi = maxOf(junkMin.value, junkMax.value)
        val len = if (lo == hi) lo else Random.nextInt(lo, hi + 1)
        return (1..len).map { JUNK_CHARS[Random.nextInt(JUNK_CHARS.length)] }.joinToString("")
    }

    private fun buildMessage(): String {
        val list = currentMessages()
        val base = list[Random.nextInt(list.size)]
        val junk = randomJunk()
        // "> Test | skejdkj6hkıdeosuj38jkeşedn | OxClient v1 Best Mobile Client"
        return "${prefix.value} $base | $junk | ${tag.value}"
    }

    private fun sendSpamMessage() {
        val session = PacketEventBus.currentSession
        if (session == null || !session.isServerReady) return

        val finalMessage = buildMessage()

        try {
            val packet = TextPacket().apply {
                type = TextPacketType.CHAT
                isNeedsTranslation = false
                sourceName = ""
                message = finalMessage
                xuid = ""
                platformChatId = ""
                filteredMessage = ""
            }
            session.sendToServer(packet)
            OverlayLogger.d(TAG, "Gönderildi: $finalMessage")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Mesaj gönderilemedi: ${e.message}", e)
        }
    }
}
