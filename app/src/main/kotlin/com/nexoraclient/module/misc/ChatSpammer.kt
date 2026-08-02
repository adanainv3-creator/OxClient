package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
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
        private const val VERSION      = "v1.2"
        private const val TAG_LINE     = "Rubidium Client $VERSION"
        private const val QUEUE_DELAY_MS = 600L
        private const val MAX_QUEUE_SIZE = 30
        private const val LOGOUT_RANGE = 256f
        private const val SELF_HIT_WINDOW_MS = 3000L
        private const val SNAPSHOT_INTERVAL_MS = 1000L

        private val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyz0123456789"
        private val JUNK_RANGE = 12..22
    }

    // ---------- Modül seçenekleri ----------
    private val shortcut    = bool("Shortcut", false)      // (Şu an kullanılmıyor, ileride kısayol için)
    private val killSpammer = bool("KillSpammer", true)    // Öldürme mesajlarını aç/kapa

    // ---------- Durum tabloları ----------
    private data class HitInfo(val name: String, val timeMs: Long)

    private val recentDeathMs    = ConcurrentHashMap<Long, Long>()
    private val recentHitsByMe   = ConcurrentHashMap<Long, HitInfo>()   // vuruş anındaki ismi de saklıyoruz — ölüm anında entity tracker'dan silinmiş olabilir
    private val recentLogoutMs   = ConcurrentHashMap<Long, Long>()
    private val knownPlayerNames = ConcurrentHashMap<Long, String>()

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

            // ---------- InventoryTransaction (Kendi vuruşumuzu takip) ----------
            is InventoryTransactionPacket -> {
                if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
                if (p.transactionType != InventoryTransactionType.ITEM_USE_ON_ENTITY) return
                if (p.actionType != 1) return

                // İsim burada, vuruş anında yakalanıyor — ölüm event'i geldiğinde
                // entity zaten tracker'dan silinmiş olabiliyor.
                val target = EntityTracker.getById(p.runtimeEntityId)
                val name   = target?.name?.takeIf { it.isNotEmpty() } ?: return
                recentHitsByMe[p.runtimeEntityId] = HitInfo(name, System.currentTimeMillis())
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
        recentHitsByMe.clear()
        knownPlayerNames.clear()
        playerSnapshots.clear()
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
        recentHitsByMe.clear()
        knownPlayerNames.clear()
        playerSnapshots.clear()
        messageQueue.clear()

        scheduler?.shutdownNow()
        scheduler = null
    }

    // ---------- Yardımcı fonksiyonlar ----------
    private fun refreshPlayerSnapshots() {
        EntityTracker.getPlayers().forEach { e ->
            if (e.runtimeId == EntityTracker.selfRuntimeId) return@forEach
            playerSnapshots[e.uniqueId] = PlayerSnapshot(e.name, e.x, e.y, e.z, e.isFriendEntity)
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
    private fun handleDeath(runtimeId: Long) {
        if (!killSpammer.value) return

        // Vuruş kaydı yoksa (ya da penceresi geçmişse) bu ölüm bize ait değil.
        val hit = recentHitsByMe.remove(runtimeId) ?: return
        val now = System.currentTimeMillis()
        if (now - hit.timeMs > SELF_HIT_WINDOW_MS) return

        val last = recentDeathMs[runtimeId]
        if (last != null && now - last < 1500L) return
        recentDeathMs[runtimeId] = now

        // Entity hâlâ tracker'daysa arkadaş kontrolünü yap; silinmişse
        // (çoğu ölüm durumunda olduğu gibi) vuruş anındaki isimle devam et.
        val entity = EntityTracker.getById(runtimeId)
        if (entity?.isFriendEntity == true) return

        enqueue("> @here EZ @${hit.name} killed by Rubidium Client | ${randomJunk()}")
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
