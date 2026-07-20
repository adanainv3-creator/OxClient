package com.oxclient.module.misc

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.BaseModule
import com.oxclient.module.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
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
import kotlin.random.Random

class ChatSpammer : BaseModule(
    name        = "ChatSpammer",
    category    = ModuleCategory.MISC,
    description = "Chat prefix + totem pop sayacı"
) {
    companion object {
        private const val VERSION      = "v1.2"
        private const val TAG_LINE     = "OxClient $VERSION"
        private const val PVP_TAIL     = "by OxClient | Best Mobile Client"
        private const val QUEUE_DELAY_MS = 2000L
        private const val MAX_QUEUE_SIZE = 20
        private val JUNK_CHARS = "abcdefghjklmnopqrstuvwxyz0123456789"
        private val JUNK_RANGE = 12..22
    }

    private val shortcut = bool("Shortcut", false)

    private val popCounts = ConcurrentHashMap<String, Int>()
    private val recentPopMs = ConcurrentHashMap<Long, Long>()
    private val pendingRegen      = ConcurrentHashMap<Long, Long>()
    private val pendingAbsorption = ConcurrentHashMap<Long, Long>()
    private val recentDeathMs  = ConcurrentHashMap<Long, Long>()
    private val recentLogoutMs = ConcurrentHashMap<Long, Long>()
    private val knownPlayerNames = ConcurrentHashMap<Long, String>()

    private val messageQueue = ConcurrentLinkedQueue<String>()
    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var activeSession: com.oxclient.core.relay.OxRelaySession? = null

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
                val nearestDistSq = dx * dx + dy * dy + dz * dz
                if (nearestDistSq > 9f) return

                val selfDx = EntityTracker.selfX - pos.x
                val selfDy = EntityTracker.selfY - pos.y
                val selfDz = EntityTracker.selfZ - pos.z
                val selfDistSq = selfDx * selfDx + selfDy * selfDy + selfDz * selfDz
                if (selfDistSq <= nearestDistSq) return

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
                    val name = EntityTracker.getByUniqueId(entry.entityId)?.name?.takeIf { it.isNotEmpty() }
                        ?: knownPlayerNames[entry.entityId]
                        ?: return@forEach
                    knownPlayerNames.remove(entry.entityId)
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
        knownPlayerNames.clear()
        messageQueue.clear()

        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ flushQueue() }, 0, QUEUE_DELAY_MS, TimeUnit.MILLISECONDS)
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
        knownPlayerNames.clear()
        messageQueue.clear()

        scheduler?.shutdownNow()
        scheduler = null
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
        val now = System.currentTimeMillis()
        val last = recentPopMs[runtimeId]
        if (last != null && now - last < 1500L) return
        recentPopMs[runtimeId] = now

        val entity = EntityTracker.getById(runtimeId)
        val name  = entity?.name?.takeIf { it.isNotEmpty() } ?: "unknown"
        val count = (popCounts[name] ?: 0) + 1
        popCounts[name] = count

        enqueue("> @here @$name Popped $count Totem $PVP_TAIL | ${randomJunk()}")
    }

    private fun handleDeath(runtimeId: Long) {
        val now = System.currentTimeMillis()
        val last = recentDeathMs[runtimeId]
        if (last != null && now - last < 1500L) return
        recentDeathMs[runtimeId] = now

        val entity = EntityTracker.getById(runtimeId) ?: return
        if (!entity.isPlayer) return
        val name = entity.name.takeIf { it.isNotEmpty() } ?: return

        enqueue("> @here GGS @$name killed by OxClient | ${randomJunk()}")
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
