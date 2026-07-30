package com.nexoraclient.module.misc

import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.BaseModule
import com.nexoraclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

class CommandHelper : BaseModule(
    name        = "CommandHelper",
    category    = ModuleCategory.MISC,
    description = "Kaydettiğin komut veya yazıları tek dokunuşla chate gönder"
) {
    companion object {
        private const val ENTRY_DELIMITER = "||"
    }

    private val entriesSetting = string("Entries", "")

    val entries: List<String>
        get() = entriesSetting.value
            .split(ENTRY_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun addEntry(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = entries
        if (trimmed in current) return
        entriesSetting.value = (current + trimmed).joinToString(ENTRY_DELIMITER)
    }

    fun removeEntry(text: String) {
        entriesSetting.value = entries.filter { it != text }.joinToString(ENTRY_DELIMITER)
    }

    fun send(text: String) {
        val session = PacketEventBus.currentSession ?: return
        val message = text.trim()
        if (message.isEmpty()) return
        runCatching { session.sendToServer(buildTextPacket(message)) }
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
}
