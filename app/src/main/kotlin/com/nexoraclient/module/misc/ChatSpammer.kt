package com.nexoraclient.module.misc

import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.events.PacketEvent
import com.nexoraclient.events.PacketEventBus
import com.nexoraclient.module.BaseModule
import com.nexoraclient.module.ModuleCategory
import com.nexoraclient.module.social.isFriendEntity
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class ChatSpammer : BaseModule(
    name        = "ChatSpammer",
    category    = ModuleCategory.MISC,
    description = "Chat prefix + totem pop sayacı"
) {
    companion object {
        private const val VERSION      = "v1.2"
        private const val TAG_LINE     = "Nexora Client $VERSION"
        private const val PVP_TAIL     = "by Nexora Client | Best Mobile Client"
        private const val QUEUE_DELAY_MS = 600L
        private const val MAX_QUEUE_SIZE = 30
        private const val LOGOUT_RANGE = 256f
        private const val TOTEM_EVENT_RADIUS = 3f
        private const val SELF_HIT_WINDOW_MS = 3000L
        private const val SNAPSHOT_INTERVAL_MS = 1000L
        private val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyz0123456789"
        private val JUNK_RANGE = 12..22

        private val POP_MESSAGES = listOf(
            "> @here @{name} Popped {count} Totem $PVP_TAIL | {junk}",
            "> @here @{name} is actually totemfag | {count} Popped | {junk} | Nexora Client",
            "> @here @{name} popped {count}x already lmao | {junk} | $TAG_LINE",
            "> @here bro @{name} needs {count} totems just to survive | {junk}",
            "> @here @{name} totem #{count} down, ez clap | {junk} | Nexora Client"
        )
    }

    private val shortcut = bool("Shortcut", false)

    private val popCounts = ConcurrentHashMap<String, Int>()
    private val recentPopMs = ConcurrentHashMap<Long, Long>()
    private val pendingRegen      = ConcurrentHashMap<Long, Long>()
    private val pendingAbsorption = ConcurrentHashMap<Long, Long>()
    private val recentDeathMs  = ConcurrentHashMap<Long, Long>()
    private val recentHitsByMe = ConcurrentHashMap<Long, Long>()
    private val recentLogoutMs = ConcurrentHashMap<Long, Long>()
    private val knownPlayerNames = ConcurrentHashMap<Long, String>()

    private val messageQueue = ConcurrentLinkedQueue<String>()
    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var activeSession: com.nexoraclient.core.relay.NexoraRelaySession? = null

    private data class PlayerSnapshot(val name: String, val x: Float, val y: Float, val z: Float, val isFriend: Boolean)
    private val playerSnapshots = ConcurrentHashMap<Long, PlayerSnapshot>()

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        activeSession = event.session

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

                if (typeStr.contains("DEATH")) {
                    handleDeath(p.runtimeEntityId)
                    return
                }

                if (!typeStr.contains("TOTEM")) return
                handleTotemPop(p.runtimeEntityId)
            }

            is LevelEventPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return

                val typeStr = runCatching { p.type?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (!typeStr.contains("TOTEM")) return

                val pos = p.position ?: return

                val nearest = EntityTracker.getAll()
                    .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
                    .minByOrNull { e ->
                        val dx = e.x - pos.x; val dy = e.y - pos.y; val dz = e.z - pos.z
                        dx * dx + dy * dy + dz * dz
                    } ?: return

                val dx = nearest.x - pos.x; val dy = nearest.y - pos.y; val dz = nearest.z - pos.z
                val nearestDist = sqrt(dx * dx + dy * dy + dz * dz)
                if (nearestDist > TOTEM_EVENT_RADIUS) return

                handleTotemPop(nearest.runtimeId)
            }

            is MobEffectPacket -> {
                if (event.direction != PacketEvent.Direction.SERVER_TO_CLIENT) return
                if (p.runtimeEntityId == EntityTracker.selfRuntimeId) return

                val eventStr = runCatching { p.event?.toString()?.uppercase() ?: "" }.getOrElse { "" }
                if (!eventStr.contains("ADD")) return

                val now = System.currentTimeMillis()
                val rid = p.runtimeEntityId

                when (p.effectId) {
                    10 -> pendingRegen[rid] = now
                    22 -> pendingAbsorption[rid] = now
                }

                val regenAt  = pendingRegen[rid]
                val absorbAt = pendingAbsorption[rid]
                if (regenAt != null && absorbAt != null && abs(regenAt - absorbAt) < 500L) {
                    pendingRegen.remove(rid)
                    pendingAbsorption.remove(rid)
                    handleTotemPop(rid)
                }
            }

            is InventoryTransactionPacket -> {
                if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
                if (p.transactionType != InventoryTransactionType.ITEM_USE_ON_ENTITY) return
                if (p.actionType != 1) return
                recentHitsByMe[p.runtimeEntityId] = System.currentTimeMillis()
            }

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

                    // tracked genelde burada zaten null oluyor: RemoveEntityPacket, PlayerListPacket'ten
                    // önce gelip entity'yi EntityTracker'dan siliyor. O yüzden mesafe/isim/friend
                    // kontrolü için periyodik snapshot'a düşüyoruz, tracked'e zorunlu bağımlı değiliz.
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

    override fun onEnable() {
        super.onEnable()
        popCounts.clear()
        recentPopMs.clear()
        pendingRegen.clear()
        pendingAbsorption.clear()
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
        popCounts.clear()
        recentPopMs.clear()
        pendingRegen.clear()
        pendingAbsorption.clear()
        recentDeathMs.clear()
        recentLogoutMs.clear()
        recentHitsByMe.clear()
        knownPlayerNames.clear()
        playerSnapshots.clear()
        messageQueue.clear()

        scheduler?.shutdownNow()
        scheduler = null
    }

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

    private fun handleTotemPop(runtimeId: Long) {
        val entity = EntityTracker.getById(runtimeId)
        if (entity?.isFriendEntity == true) return

        val now = System.currentTimeMillis()
        val last = recentPopMs[runtimeId]
        if (last != null && now - last < 1500L) return
        recentPopMs[runtimeId] = now

        val name  = entity?.name?.takeIf { it.isNotEmpty() } ?: "unknown"
        val count = (popCounts[name] ?: 0) + 1
        popCounts[name] = count

        val text = POP_MESSAGES[Random.nextInt(POP_MESSAGES.size)]
            .replace("{name}", name)
            .replace("{count}", count.toString())
            .replace("{junk}", randomJunk())

        enqueue(text)
    }

    private fun handleDeath(runtimeId: Long) {
        val entity = EntityTracker.getById(runtimeId) ?: return
        if (!entity.isPlayer) return
        if (entity.isFriendEntity) return

        val now = System.currentTimeMillis()
        val last = recentDeathMs[runtimeId]
        if (last != null && now - last < 1500L) return
        recentDeathMs[runtimeId] = now

        val name = entity.name.takeIf { it.isNotEmpty() } ?: return

        val hitAt = recentHitsByMe.remove(runtimeId) ?: return
        if (now - hitAt > SELF_HIT_WINDOW_MS) return

        enqueue("> @here EZ @$name killed by Nexora Client | ${randomJunk()}")
    }

    private fun handleLogout(uniqueId: Long, name: String) {
        val now = System.currentTimeMillis()
        val last = recentLogoutMs[uniqueId]
        if (last != null && now - last < 1500L) return
        recentLogoutMs[uniqueId] = now

        enqueue("> @$name Ez Logged | ${randomJunk()}")
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
