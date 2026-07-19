package com.oxclient.module.misc

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
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
    // Aynı totem pop'unu hem EntityEventPacket hem LevelEventPacket'ten
    // iki kere saymamak için: entity runtimeId -> son sayım zamanı
    private val recentPopMs = ConcurrentHashMap<Long, Long>()
    // 3. yedek yol: Regeneration + Absorption neredeyse aynı anda gelirse totem say
    private val pendingRegen      = ConcurrentHashMap<Long, Long>()
    private val pendingAbsorption = ConcurrentHashMap<Long, Long>()

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val p = event.packet) {

            is TextPacket -> {
                if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
                if (p.sourceName == "__ox_internal__") return

                val raw = p.message?.trim() ?: return
                if (raw.isEmpty() || raw.startsWith("/")) return

                val formatted = "> $raw | $TAG_LINE | ${randomJunk()}"
                event.cancelAndReplace(buildTextPacket(formatted))
            }

            is EntityEventPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
                if (p.runtimeEntityId == EntityTracker.selfRuntimeId) return

                val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (!typeStr.contains("TOTEM")) return

                handleTotemPop(event.session, p.runtimeEntityId)
            }

            // Yedek tespit yolu: EntityEventPacket bir sebeple gelmezse/eşleşmezse,
            // totem patladığında sunucunun her zaman yaydığı SOUND_TOTEM_USED
            // ses olayından yakala (LevelEvent enum'unda "particle" değil "sound" olarak geçiyor).
            is LevelEventPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

                val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (!typeStr.contains("TOTEM")) return

                val pos = p.position ?: return

                // Önce bu bizim kendi totemimiz mi diye bak — patlama pozisyonu
                // bize rakipten daha yakınsa (ya da 3 blok içindeyse) muhtemelen
                // bizimdir, rakibe atfedip yanlış sayma.
                val selfDx = EntityTracker.selfX - pos.x
                val selfDy = EntityTracker.selfY - pos.y
                val selfDz = EntityTracker.selfZ - pos.z
                val selfDistSq = selfDx * selfDx + selfDy * selfDy + selfDz * selfDz
                if (selfDistSq <= 9f) return

                val nearest = EntityTracker.getAll()
                    .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
                    .minByOrNull { e ->
                        val dx = e.x - pos.x; val dy = e.y - pos.y; val dz = e.z - pos.z
                        dx * dx + dy * dy + dz * dz
                    } ?: return

                val dx = nearest.x - pos.x; val dy = nearest.y - pos.y; val dz = nearest.z - pos.z
                if (dx * dx + dy * dy + dz * dz > 9f) return // 3 blok içinde değilse eşleştirme

                handleTotemPop(event.session, nearest.runtimeId)
            }

            // 3. yedek yol: totem patladığında oyuncuya (neredeyse) aynı anda
            // Regeneration + Absorption efekti veriliyor — vanilla'da bu ikilinin
            // aynı anda gelmesi pratikte sadece totem tepkisinde olur.
            // MobEffectPacket'te effect bir enum değil, ham Bedrock effect ID'si (effectId):
            // 10 = Regeneration, 22 = Absorption.
            is MobEffectPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
                if (p.runtimeEntityId == EntityTracker.selfRuntimeId) return

                val eventStr = runCatching { p.event?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (!eventStr.contains("ADD")) return // sadece yeni eklenen efekt

                val now = System.currentTimeMillis()
                val rid = p.runtimeEntityId

                when (p.effectId) {
                    10 -> pendingRegen[rid] = now      // Regeneration
                    22 -> pendingAbsorption[rid] = now // Absorption
                }

                val regenAt  = pendingRegen[rid]
                val absorbAt = pendingAbsorption[rid]
                if (regenAt != null && absorbAt != null && abs(regenAt - absorbAt) < 500L) {
                    pendingRegen.remove(rid)
                    pendingAbsorption.remove(rid)
                    handleTotemPop(event.session, rid)
                }
            }
        }
    }

    override fun onEnable() {
        super.onEnable()
        popCounts.clear()
        recentPopMs.clear()
        pendingRegen.clear()
        pendingAbsorption.clear()
    }

    override fun onDisable() {
        super.onDisable()
        popCounts.clear()
        recentPopMs.clear()
        pendingRegen.clear()
        pendingAbsorption.clear()
    }

    private fun handleTotemPop(session: com.oxclient.core.relay.OxRelaySession, runtimeId: Long) {
        val now = System.currentTimeMillis()
        val last = recentPopMs[runtimeId]
        if (last != null && now - last < 1500L) return // aynı pop iki kaynaktan da geldiyse tekrar sayma
        recentPopMs[runtimeId] = now

        val entity = EntityTracker.getById(runtimeId)
        val name  = entity?.name?.takeIf { it.isNotEmpty() } ?: "unknown"
        val count = (popCounts[name] ?: 0) + 1
        popCounts[name] = count

        if (!session.isServerReady) return

        val message = "> @$name Popped $count Totem $PVP_TAIL | ${randomJunk()}"
        session.sendToServer(buildTextPacket(message))
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
