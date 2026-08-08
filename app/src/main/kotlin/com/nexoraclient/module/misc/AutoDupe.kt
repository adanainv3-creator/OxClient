package com.rubidiumclient.module.misc

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.utils.InventoryUtil
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction
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
    enum class DupeMode { CHEST, DROP, ITEM_STACK, PISTON, ANVIL }

    private val mode = enum("Dupe Mode", DupeMode.CHEST)
    private val delay = int("Tick Delay (ms)", 200, 50, 2000)
    private val autoClose = bool("Auto Close Chest", false)
    private val chestRange = float("Chest Range", 5f, 1f, 10f)
    private val dropCount = int("Drop Count", 1, 1, 64)
    private val stackCount = int("Stack Count", 64, 1, 127)

    private data class TrackedChest(val pos: Vector3i, var contents: List<ItemData> = emptyList(), var open: Boolean = false)
    private val openContainers = ConcurrentHashMap<Int, TrackedChest>()
    private val requestIdCounter = AtomicInteger(0)
    private var tickJob: kotlinx.coroutines.Job? = null

    override fun onEnable() {
        super.onEnable()
        openContainers.clear()
        tickJob = launchTickLoop(delay.value.toLong()) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        tickJob = null
        openContainers.values.forEach { if (it.open) closeChest(it.pos) }
        openContainers.clear()
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (mode.value) {
            DupeMode.CHEST -> handleChest(event)
            DupeMode.DROP -> handleDrop(event)
            DupeMode.ITEM_STACK -> handleItemStack(event)
            DupeMode.PISTON -> handlePiston(event)
            DupeMode.ANVIL -> handleAnvil(event)
        }
    }

    private fun handleChest(event: PacketEvent) {
        val session = event.session ?: return
        when (val p = event.packet) {
            is ContainerOpenPacket -> {
                val windowId = p.id.toInt()
                val pos = p.blockPosition ?: return
                if (EntityTracker.distanceTo(pos.x + 0.5f, pos.y + 0.5f, pos.z + 0.5f) <= chestRange.value) {
                    openContainers[windowId] = TrackedChest(pos, open = true)
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
                if (p.transactionType == InventoryTransactionType.ITEM_USE_ON_ENTITY) return
                val actions = p.actions
                var modified = false
                for (action in actions) {
                    val source = action.source
                    if (source.type == InventorySource.Type.CONTAINER) {
                        val containerId = source.containerId
                        if (containerId in openContainers.keys) {
                            val fromItem = action.fromItem
                            val toItem = action.toItem
                            if (!InventoryUtil.isEmpty(fromItem) && InventoryUtil.isEmpty(toItem)) {
                                modified = true
                                break
                            }
                        }
                    }
                }
                if (modified) {
                    event.cancel()
                    session.sendToClient(p)
                }
            }
            is ContainerClosePacket -> {
                val windowId = p.id.toInt()
                val chest = openContainers[windowId]
                if (chest != null && chest.open) {
                    if (!autoClose.value) {
                        event.cancel()
                    } else {
                        openContainers.remove(windowId)
                    }
                }
            }
        }
    }

    private fun handleDrop(event: PacketEvent) {
        if (event.packet !is ItemStackRequestPacket) return
        val session = event.session ?: return
        val request = event.packet.requests.firstOrNull() ?: return
        val actions = request.actions
        var dropAction: DropAction? = null
        var slot = -1
        for (action in actions) {
            if (action is DropAction) {
                dropAction = action
                slot = action.source.slot
                break
            }
        }
        if (dropAction == null || slot < 0) return
        val item = EntityTracker.getInventoryItem(slot) ?: return
        if (InventoryUtil.isEmpty(item)) return

        val newRequest = ItemStackRequest(
            requestIdCounter.decrementAndGet(),
            arrayOf(
                PlaceAction(
                    dropCount.value,
                    ItemStackRequestSlotData(
                        ContainerSlotType.HOTBAR_AND_INVENTORY,
                        slot,
                        item.netId,
                        FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null)
                    ),
                    ItemStackRequestSlotData(
                        ContainerSlotType.ANVIL,
                        0,
                        0,
                        FullContainerName(ContainerSlotType.ANVIL, null)
                    )
                )
            ),
            arrayOf()
        )
        val packet = ItemStackRequestPacket().apply { requests.add(newRequest) }
        session.sendToServer(packet)
        event.cancel()
    }

    private fun handleItemStack(event: PacketEvent) {
        if (event.packet !is ItemStackRequestPacket) return
        val session = event.session ?: return
        val request = event.packet.requests.firstOrNull() ?: return
        val actions = request.actions
        var swapAction: SwapAction? = null
        for (action in actions) {
            if (action is SwapAction) {
                swapAction = action
                break
            }
        }
        if (swapAction == null) return
        val src = swapAction.source
        val dst = swapAction.destination
        val srcItem = EntityTracker.getInventoryItem(src.slot) ?: return
        if (InventoryUtil.isEmpty(srcItem)) return
        val dstItem = EntityTracker.getInventoryItem(dst.slot) ?: ItemData.AIR
        if (!InventoryUtil.isEmpty(dstItem)) return

        val newSrcItem = srcItem.toBuilder().count(stackCount.value).build()
        val newDstItem = dstItem.toBuilder().count(stackCount.value).build()
        val newRequest = ItemStackRequest(
            requestIdCounter.decrementAndGet(),
            arrayOf(
                SwapAction(
                    ItemStackRequestSlotData(src.container, src.slot, newSrcItem.netId, src.fullContainerName),
                    ItemStackRequestSlotData(dst.container, dst.slot, newDstItem.netId, dst.fullContainerName)
                )
            ),
            arrayOf()
        )
        val packet = ItemStackRequestPacket().apply { requests.add(newRequest) }
        session.sendToServer(packet)
        event.cancel()
    }

    private fun handlePiston(event: PacketEvent) {
    }

    private fun handleAnvil(event: PacketEvent) {
    }

    private fun closeChest(pos: Vector3i) {
        val session = com.rubidiumclient.events.PacketEventBus.currentSession ?: return
        for ((windowId, chest) in openContainers) {
            if (chest.pos == pos && chest.open) {
                session.sendToServer(ContainerClosePacket().apply {
                    id = windowId.toByte()
                    isServerInitiated = false
                })
                chest.open = false
                break
            }
        }
    }

    private fun tick() {
        if (autoClose.value) {
            openContainers.values.forEach { chest ->
                if (chest.open) closeChest(chest.pos)
            }
            openContainers.clear()
        }
    }
}
