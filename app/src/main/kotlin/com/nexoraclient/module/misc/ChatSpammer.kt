package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ChatSpammer : BaseModule(
    name        = "ChatSpammer",
    category    = ModuleCategory.MISC,
    description = "Chat prefix + öldürme/logout spam"
) {
    companion object {
        private const val VERSION      = "v1.4"
        private const val TAG_LINE     = "Rubidium  $VERSION"
        private const val QUEUE_DELAY_MS = 600L
        private const val MAX_QUEUE_SIZE = 30
        private const val LOGOUT_RANGE = 256f
        private const val SNAPSHOT_INTERVAL_MS = 1000L

        private val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyz0123456789"
        private val JUNK_RANGE = 12..22
    }

    // ---------- Modül seçenekleri ----------
    private val shortcut    = bool("Shortcut", false)      // (Şu an kullanılmıyor, ileride kısayol için)
    private val killSpammer = bool("KillSpammer", true)    // Öldürme mesajlarını aç/kapa

    // ---------- Durum tabloları ----------
    private val recentDeathMs    = ConcurrentHashMap<Long, Long>()
    private val recentLogoutMs   = ConcurrentHashMap<Long, Long>()
    private val knownPlayerNames = ConcurrentHashMap<Long, String>()
    private val runtimeIdNames   = ConcurrentHashMap<Long, String>() // entity tracker'dan silinmiş olsa bile isim çözebilmek için

    private val messageQueue = ConcurrentLinkedQueue<String>()
    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var activeSession: com.rubidiumclient.core.relay.RubidiumRelaySession? = null

    private data class PlayerSnapshot(val name: String, val x: Float, val y: Float, val z: Float, val isFriend: Boolean)
    private val playerSnapshots = ConcurrentHashMap<Long, PlayerSnapshot>()

    // ---------- Paket işleyici ----------
    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        activeSession = event.session

        when (val p = event.packet) {

            // ---------- Chat mesajlarına prefix ekle ----------
            is TextPacket -> {
                if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
                if (p.sourceName == "__ox_internal__") return

                val raw = p.message?.trim() ?: return
                if (raw.isEmpty() || raw.startsWith("/")) return

                val formatted = "> $raw | $TAG_LINE | ${randomJunk()}"
                event.cancelAndReplace(buildTextPacket(formatted))
            }

            // ---------- EntityEventPacket (Ölüm) ----------
            is EntityEventPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
                if (p.runtimeEntityId == EntityTracker.selfRuntimeId) return

                val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (typeStr.contains("DEATH")) handleDeath(p.runtimeEntityId)
            }

            // ---------- PlayerListPacket (Oyuncu çıkışı – logout) ----------
            is PlayerListPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

                if (p.action == PlayerListPacket.Action.ADD) {
                    p.entries.forEach { entry ->
                        val name = entry.name ?: return@forEach
                        if (name.isNotEmpty()) knownPlayerNames[entry.entityId] = name
                    }
                    return
                }

                p.entries.forEach { entry ->
                    if (entry.entityId == EntityTracker.selfUniqueId) return@forEach

                    val tracked = EntityTracker.getByUniqueId(entry.entityId)
                    val snap    = playerSnapshots[entry.entityId]

                    val isFriend = tracked?.isFriendEntity ?: snap?.isFriend ?: false
                    if (isFriend) {
                        knownPlayerNames.remove(entry.entityId)
                        playerSnapshots.remove(entry.entityId)
                        return@forEach
                    }

                    val dist = when {
                        tracked != null -> EntityTracker.distanceTo(tracked)
                        snap    != null -> EntityTracker.distanceTo(snap.x, snap.y, snap.z)
                        else            -> Float.MAX_VALUE
                    }
                    if (dist > LOGOUT_RANGE) {
                        knownPlayerNames.remove(entry.entityId)
                        playerSnapshots.remove(entry.entityId)
                        return@forEach
                    }

                    val name = tracked?.name?.takeIf { it.isNotEmpty() }
                        ?: snap?.name?.takeIf { it.isNotEmpty() }
                        ?: knownPlayerNames[entry.entityId]
                        ?: return@forEach

                    knownPlayerNames.remove(entry.entityId)
                    playerSnapshots.remove(entry.entityId)
                    handleLogout(entry.entityId, name)
                }
            }
        }
    }

    // ---------- Modül yaşam döngüsü ----------
    override fun onEnable() {
        super.onEnable()
        recentDeathMs.clear()
        recentLogoutMs.clear()
        knownPlayerNames.clear()
        playerSnapshots.clear()
        runtimeIdNames.clear()
        messageQueue.clear()

        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ flushQueue() }, 0, QUEUE_DELAY_MS, TimeUnit.MILLISECONDS)
            it.scheduleWithFixedDelay({ refreshPlayerSnapshots() }, 0, SNAPSHOT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun onDisable() {
        super.onDisable()
        recentDeathMs.clear()
        recentLogoutMs.clear()
        knownPlayerNames.clear()
        playerSnapshots.clear()
        runtimeIdNames.clear()
        messageQueue.clear()

        scheduler?.shutdownNow()
        scheduler = null
    }

    // ---------- Yardımcı fonksiyonlar ----------
    private fun refreshPlayerSnapshots() {
        EntityTracker.getPlayers().forEach { e ->
            if (e.runtimeId == EntityTracker.selfRuntimeId) return@forEach
            playerSnapshots[e.uniqueId] = PlayerSnapshot(e.name, e.x, e.y, e.z, e.isFriendEntity)
            if (e.name.isNotEmpty()) runtimeIdNames[e.runtimeId] = e.name
        }
    }

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

    // ---------- Ölüm işleyicisi ----------
    // Not: Artık "bu ölüm bize mi ait" filtresi yok — sunucudan gelen HER
    // oyuncu ölümünde (kim öldürürse öldürsün) mesaj atılır. Bu filtre
    // kaldırılmadan önce mesaj sadece InventoryTransactionPacket ile
    // yakaladığımız kendi vuruşumuza bağlıydı; bazı sunucularda vuruşlar artık
    // PlayerAuthInputPacket üzerinden geldiği için bu paket hiç gelmiyor ve
    // ölüm mesajı asla tetiklenmiyordu.
    private fun handleDeath(runtimeId: Long) {
        if (!killSpammer.value) return

        val now = System.currentTimeMillis()
        val last = recentDeathMs[runtimeId]
        if (last != null && now - last < 1500L) return
        recentDeathMs[runtimeId] = now

        val entity = EntityTracker.getById(runtimeId)
        if (entity?.isFriendEntity == true) return

        val name = entity?.name?.takeIf { it.isNotEmpty() }
            ?: runtimeIdNames[runtimeId]
            ?: return

        enqueue("> GGS @$name  | ${randomJunk()}")
    }

    // ---------- Logout işleyicisi ----------
    private fun handleLogout(uniqueId: Long, name: String) {
        val now = System.currentTimeMillis()
        val last = recentLogoutMs[uniqueId]
        if (last != null && now - last < 1500L) return
        recentLogoutMs[uniqueId] = now

        enqueue("> @$name Ez Logged | ${randomJunk()}")
    }

    // ---------- Paket oluşturucu ----------
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
