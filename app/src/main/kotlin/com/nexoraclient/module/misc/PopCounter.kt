package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.InventoryUtil
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * PopCounter — offhand-polling tabanlı totem pop sayacı.
 *
 * ChatSpammer'daki eski "Best Counter Mode" seçeneğinin ayrı modül haline
 * getirilmiş hali. Event tabanlı totem algılama yollarından (EntityEventPacket/
 * LevelEventPacket/MobEffectPacket) BİLEREK vazgeçildi — sadece her tick
 * oyuncuların offhand slotunu okuyup totem varken/yoksa durumuna bakarak
 * "pop" sayıyor.
 *
 * BİLİNEN KISITLAMA: Oyuncu totemi patlatmadan elle offhand'den çıkarıp
 * başka bir item takarsa (veya item'ı düşürürse) bu da yanlışlıkla "pop"
 * sayılır — çapraz doğrulama yapılmıyor.
 */
class PopCounter : BaseModule(
    name        = "PopCounter",
    category    = ModuleCategory.MISC,
    description = "Offhand-polling tabanlı totem pop sayacı"
) {
    companion object {
        private const val VERSION      = "v1.3"
        private const val TAG_LINE     = "Rubidium Client $VERSION"
        private const val PVP_TAIL     = "by Rubidium Client | Best Mobile Client"
        private const val QUEUE_DELAY_MS = 600L
        private const val MAX_QUEUE_SIZE = 30

        private const val POLL_INTERVAL_MS = 150L
        private const val DEBOUNCE_MS      = 1200L
        private const val RANGE            = 100f

        private val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyz0123456789"
        private val JUNK_RANGE = 12..22

        // 15 mesaj şablonu — rastgele seçiliyor
        private val POP_MESSAGES = listOf(
            "> @here @{name} Popped {count} Totem $PVP_TAIL | {junk}",
            "> @here @{name} is actually totemfag | {count} Popped | {junk} | Rubidium Client",
            "> @here @{name} popped {count}x already lmao | {junk} | $TAG_LINE",
            "> @here bro @{name} needs {count} totems just to survive | {junk}",
            "> @here @{name} totem #{count} down, ez clap | {junk} | Rubidium Client",
            "> @here @{name} another totem gone, {count} total now | {junk} | $TAG_LINE",
            "> @here @{name} is farming totems fr | {count}x popped | {junk}",
            "> @here L totem @{name}, {count} down already | {junk} | Rubidium Client",
            "> @here @{name} totem #{count} confirmed dead | {junk} | $TAG_LINE",
            "> @here bro @{name} popped {count} and still losing | {junk}",
            "> @here @{name} thats {count} totems wasted for nothing | {junk} | Rubidium Client",
            "> @here @{name} totem economy crashing, {count} popped | {junk}",
            "> @here @{name} {count}x totem pop detected | {junk} | $TAG_LINE",
            "> @here @{name} keeps popping ({count}) but still cooked | {junk}",
            "> @here @{name} totem #{count} — nice try | {junk} | Rubidium Client"
        )
    }

    // ---------- Modül seçenekleri ----------
    private val sendChat = bool("Send Chat", false)

    // ---------- Durum tabloları ----------
    private val lastHadTotem = ConcurrentHashMap<Long, Boolean>()
    private val popCounts    = ConcurrentHashMap<String, Int>()
    private val recentPopMs  = ConcurrentHashMap<Long, Long>()

    private val messageQueue = ConcurrentLinkedQueue<String>()
    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var activeSession: com.rubidiumclient.core.relay.RubidiumRelaySession? = null

    // ---------- Paket işleyici ----------
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        activeSession = event.session

        val p = event.packet
        // Kendi ölümüm — state'i (respawn sonrası) temizle
        if (p is EntityEventPacket && event.direction == PacketEvent.Direction.SERVER_TO_CLIENT) {
            if (p.runtimeEntityId == EntityTracker.selfRuntimeId) {
                val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (typeStr.contains("DEATH")) resetState()
            }
        }
    }

    // ---------- Modül yaşam döngüsü ----------
    override fun onEnable() {
        super.onEnable()
        lastHadTotem.clear()
        popCounts.clear()
        recentPopMs.clear()
        messageQueue.clear()

        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ flushQueue() }, 0, QUEUE_DELAY_MS, TimeUnit.MILLISECONDS)
            it.scheduleWithFixedDelay({ pollTotems() }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun onDisable() {
        super.onDisable()
        lastHadTotem.clear()
        popCounts.clear()
        recentPopMs.clear()
        messageQueue.clear()

        scheduler?.shutdownNow()
        scheduler = null
    }

    // ---------- Offhand-polling tabanlı totem algılama ----------
    private fun pollTotems() {
        if (!isEnabled) return

        val activeRuntimeIds = HashSet<Long>()

        EntityTracker.getPlayers().forEach { e ->
            if (e.runtimeId == EntityTracker.selfRuntimeId) return@forEach

            if (e.isFriendEntity) {
                lastHadTotem.remove(e.runtimeId)
                return@forEach
            }

            val dist = EntityTracker.distanceTo(e)
            if (dist > RANGE) {
                lastHadTotem.remove(e.runtimeId)
                return@forEach
            }

            activeRuntimeIds.add(e.runtimeId)

            val hasTotem = runCatching {
                InventoryUtil.isTotem(e.offHandItem)
            }.getOrElse { false }

            val had = lastHadTotem[e.runtimeId] ?: false
            if (had && !hasTotem) {
                val now = System.currentTimeMillis()
                val last = recentPopMs[e.runtimeId]
                if (last == null || now - last >= DEBOUNCE_MS) {
                    recentPopMs[e.runtimeId] = now

                    val name = e.name.takeIf { it.isNotEmpty() } ?: "unknown"
                    val count = (popCounts[name] ?: 0) + 1
                    popCounts[name] = count

                    if (sendChat.value) {
                        val text = POP_MESSAGES[Random.nextInt(POP_MESSAGES.size)]
                            .replace("{name}", name)
                            .replace("{count}", count.toString())
                            .replace("{junk}", randomJunk())
                        enqueue(text)
                    }
                }
            }
            lastHadTotem[e.runtimeId] = hasTotem
        }

        // Artık menzilde/görünür olmayan oyuncuların state'ini temizle
        lastHadTotem.keys.retainAll(activeRuntimeIds)
        recentPopMs.keys.retainAll(activeRuntimeIds)
    }

    private fun resetState() {
        lastHadTotem.clear()
        popCounts.clear()
        recentPopMs.clear()
    }

    // ---------- Yardımcı fonksiyonlar ----------
    private fun flushQueue() {
        val session = activeSession ?: return
        if (!session.isServerReady) return
        val msg = messageQueue.poll() ?: return
        runCatching { session.sendToServer(buildTextPacket(msg)) }
    }

    private fun enqueue(message: String) {
        if (messageQueue.size >= MAX_QUEUE_SIZE) messageQueue.poll()
        messageQueue.offer(message)
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

    // ---------- Rastgele saçma karakter üretici ----------
    private fun randomJunk(): String {
        val len = Random.nextInt(JUNK_RANGE.first, JUNK_RANGE.last + 1)
        return buildString(len) { repeat(len) { append(JUNK_CHARS[Random.nextInt(JUNK_CHARS.length)]) } }
    }
}
