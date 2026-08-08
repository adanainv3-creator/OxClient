package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.InventoryUtil
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AutoDupe : BaseModule(
    name = "AutoDupe",
    category = ModuleCategory.MISC,
    description = "2b2tpe için paket manipülasyonu ile eşya kopyalama"
) {
    enum class DupeMode { CHEST, PACKET }

    private val mode = enum("Dupe Mode", DupeMode.CHEST)
    private val delay = int("Tick Delay (ms)", 200, 50, 2000)
    private val chestRange = float("Chest Range", 5f, 1f, 10f)
    private val packetSpamCount = int("Packet Spam Count", 5, 1, 20)

    private data class TrackedChest(
        val pos: Vector3i,
        var windowId: Int,
        var contents: List<ItemData> = emptyList(),
        var open: Boolean = false,
        var takeCount: Int = 0
    )
    
    private val openContainers = ConcurrentHashMap<Int, TrackedChest>()
    private val requestIdCounter = AtomicInteger(0)
    private var tickJob: kotlinx.coroutines.Job? = null
    private var packetSpamCounter = 0

    override fun onEnable() {
        super.onEnable()
        openContainers.clear()
        packetSpamCounter = 0
        tickJob = launchTickLoop(delay.value.toLong()) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        openContainers.values.forEach { if (it.open) closeChest(it.windowId) }
        openContainers.clear()
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (mode.value) {
            DupeMode.CHEST -> handleChestDupe(event)
            DupeMode.PACKET -> handlePacketDupe(event)
        }
    }

    private fun handleChestDupe(event: PacketEvent) {
        val session = event.session ?: return
        when (val p = event.packet) {
            is ContainerOpenPacket -> {
                val windowId = p.id.toInt()
                val pos = p.blockPosition ?: return
                val dx = pos.x + 0.5f - EntityTracker.selfX
                val dy = pos.y + 0.5f - EntityTracker.selfY
                val dz = pos.z + 0.5f - EntityTracker.selfZ
                val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (dist <= chestRange.value) {
                    openContainers[windowId] = TrackedChest(pos, windowId, open = true)
                }
            }
            
            is InventoryContentPacket -> {
                val windowId = p.containerId
                val chest = openContainers[windowId]
                if (chest != null) {
                    chest.contents = p.contents.toList()
                }
            }
            
            is InventoryTransactionPacket -> {
                if (p.transactionType != InventoryTransactionType.NORMAL) return
                val actions = p.actions
                var chestAction = false
                val takenItems = mutableListOf<ItemData>()
                
                for (action in actions) {
                    val source = action.source
                    if (source.type == InventorySource.Type.CONTAINER) {
                        val containerId = source.containerId
                        val chest = openContainers[containerId]
                        if (chest != null && chest.open) {
                            chestAction = true
                            if (!InventoryUtil.isEmpty(action.fromItem) && InventoryUtil.isEmpty(action.toItem)) {
                                takenItems.add(action.fromItem)
                            }
                        }
                    }
                }
                
                if (chestAction && takenItems.isNotEmpty()) {
                    val chest = openContainers.values.firstOrNull { it.open }
                    if (chest != null) {
                        event.cancel()
                        val closePacket = ContainerClosePacket().apply {
                            id = chest.windowId.toByte()
                            type = ContainerType.CONTAINER
                            setServerInitiated(false) // use setter
                        }
                        session.sendToServer(closePacket)
                        chest.open = false
                        openContainers.remove(chest.windowId)
                        
                        for (item in takenItems) {
                            val restorePacket = InventoryContentPacket().apply {
                                containerId = chest.windowId
                                contents = listOf(item)
                            }
                            session.sendToClient(restorePacket)
                        }
                    }
                }
            }
            
            is ContainerClosePacket -> {
                val windowId = p.id.toInt()
                val chest = openContainers[windowId]
                if (chest != null && chest.open) {
                    if (!p.isServerInitiated()) { // use getter
                        openContainers.remove(windowId)
                    }
                }
            }
        }
    }

    private fun handlePacketDupe(event: PacketEvent) {
        val session = event.session ?: return
        val p = event.packet
        
        when (p) {
            is InventoryTransactionPacket -> {
                if (p.transactionType == InventoryTransactionType.NORMAL) {
                    val actions = p.actions
                    var hasContainerAction = false
                    
                    for (action in actions) {
                        val source = action.source
                        if (source.type == InventorySource.Type.CONTAINER) {
                            hasContainerAction = true
                            break
                        }
                    }
                    
                    if (hasContainerAction && packetSpamCounter < packetSpamCount.value) {
                        packetSpamCounter++
                        val clone = p.clone()
                        session.sendToServer(clone)
                        event.cancel()
                    } else {
                        packetSpamCounter = 0
                    }
                }
            }
            
            is ItemStackRequestPacket -> {
                val requests = p.requests
                if (requests.isNotEmpty() && packetSpamCounter < packetSpamCount.value) {
                    packetSpamCounter++
                    val clone = p.clone()
                    session.sendToServer(clone)
                    event.cancel()
                } else {
                    packetSpamCounter = 0
                }
            }
        }
    }

    private fun closeChest(windowId: Int) {
        val session = com.rubidiumclient.events.PacketEventBus.currentSession ?: return
        val packet = ContainerClosePacket().apply {
            id = windowId.toByte()
            type = ContainerType.CONTAINER
            setServerInitiated(false) // use setter
        }
        session.sendToServer(packet)
    }

    private fun tick() {
        if (mode.value == DupeMode.PACKET) {
            packetSpamCounter = 0
        }
    }
}