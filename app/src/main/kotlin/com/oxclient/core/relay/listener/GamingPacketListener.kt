package com.oxclient.core.relay.listener

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.ConnectionManager
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.utils.BlockTracker
import com.oxclient.utils.ChunkParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleNamedDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry

class GamingPacketListener : OxPacketListener {

    companion object {
        private const val TAG = "GamingPacketListener"
    }

    override val priority: Int = 100

    @Volatile private var active = false

    @Volatile private var itemComponentsApplied = false

    // Chunk parse işlemi ağır olabiliyor (özellikle ışınlanma sonrası gelen chunk
    // patlamasında) — bunu ağ paket işleme thread'inden ayırıp arka planda yapıyoruz
    // ki relay paket akışını bloklamasın.
    private val parserScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onSessionStart(session: OxRelaySession) {
        active = true
        itemComponentsApplied = false
    }

    override fun onSessionEnd(session: OxRelaySession) {
        active = false
        EntityTracker.reset()
        parserScope.coroutineContext[Job]?.cancelChildren()
    }

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        if (!active) return true
        when (packet) {
            is MovePlayerPacket          -> { }
            is PlayerAuthInputPacket     -> { }
            is PlayerActionPacket        -> { }
            is InteractPacket            -> { }
            is InventoryTransactionPacket-> { }
            is CommandRequestPacket      -> { }
            is TextPacket                -> { }
            is AnimatePacket             -> { }
            is DisconnectPacket          -> { }
        }
        return true
    }

    override fun onServerPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        if (!active) return true
        when (packet) {

            is StartGamePacket -> {
                applyStartGameDefinitions(packet, session)
                BlockTracker.loadPalette(packet)
                ConnectionManager.onGameStarted()
            }

            is UpdateBlockPacket -> handleUpdateBlock(packet)
            is BlockEntityDataPacket -> handleBlockEntity(packet)
            is LevelChunkPacket -> handleChunk(packet)

            is CameraPresetsPacket -> {
                applyCameraDefinitions(packet, session)
            }

            is ItemComponentPacket -> {
                applyItemComponents(packet, session)
            }

            is CreativeContentPacket -> {
                if (!itemComponentsApplied) {
                    applyCreativeItemDefinitions(packet, session)
                }
            }

            is AddPlayerPacket          -> { }
            is AddEntityPacket          -> { }
            is RemoveEntityPacket       -> { }
            is MoveEntityAbsolutePacket -> { }
            is MovePlayerPacket         -> { }

            is RespawnPacket          -> { }
            is SetHealthPacket        -> { }
            is UpdateAttributesPacket -> { }
            is PlayerListPacket       -> { }
            is ChangeDimensionPacket  -> { }
            is TextPacket             -> { }
            is DisconnectPacket       -> { }

            is TransferPacket -> {
                val newHost = packet.address
                val newPort = packet.port

                try {
                    session.relay.updateRemoteTarget(newHost, newPort)

                    val clientReconnectHost = (session.clientSession.peer.channel.localAddress()
                        as? java.net.InetSocketAddress)?.address?.hostAddress
                        ?: "127.0.0.1"

                    session.sendToClient(TransferPacket().apply {
                        address = clientReconnectHost
                        port    = session.relay.boundLocalPort
                    })
                } catch (e: Exception) {
                }

                return false
            }
        }
        return true
    }

    private fun handleUpdateBlock(pkt: UpdateBlockPacket) {
        val pos = pkt.blockPosition
        val runtimeId = pkt.definition?.runtimeId ?: return
        val type = BlockTracker.resolveBlockId(runtimeId)
        if (type != null) BlockTracker.add(pos.x, pos.y, pos.z, type)
        else BlockTracker.remove(pos.x, pos.y, pos.z)
    }

    private fun handleBlockEntity(pkt: BlockEntityDataPacket) {
        val pos = pkt.blockPosition
        val tag = pkt.data ?: return
        val id  = tag.getString("id") ?: return
        val type = BlockTracker.resolveBlockName(id) ?: return
        BlockTracker.add(pos.x, pos.y, pos.z, type)
    }

    private fun handleChunk(pkt: LevelChunkPacket) {
        // pkt.data, Netty'nin havuzlanmış ByteBuf'ı — bu handler döndükten sonra
        // pipeline onu serbest bırakabilir/yeniden kullanabilir. Arka planda güvenle
        // okuyabilmek için burada retain() ediyoruz, iş bitince release() ediyoruz.
        val buf = try { pkt.data } catch (_: Throwable) { null }
        if (buf == null) return
        buf.retain()

        parserScope.launch {
            try {
                val entities = try {
                    ChunkParser.extractBlockEntities(pkt)
                } catch (e: Exception) {
                    emptyList()
                }
                if (entities.isEmpty()) return@launch
                for (be in entities) {
                    val id = be.tag.getString("id") ?: continue
                    val type = BlockTracker.resolveBlockName(id) ?: continue
                    BlockTracker.add(be.x, be.y, be.z, type)
                }
            } finally {
                buf.release()
            }
        }
    }

    private fun applyStartGameDefinitions(packet: StartGamePacket, session: OxRelaySession) {
        try {
            if (packet.itemDefinitions.isNotEmpty()) {
                val itemRegistry = SimpleDefinitionRegistry.builder<ItemDefinition>()
                    .addAll(packet.itemDefinitions)
                    .build()

                session.clientSession.peer.codecHelper.itemDefinitions = itemRegistry
                session.serverSession?.peer?.codecHelper?.itemDefinitions = itemRegistry
            }
        } catch (e: Exception) {
        }

        try {
            if (packet.isBlockNetworkIdsHashed) {
                val serverDefs = session.serverSession?.peer?.codecHelper?.blockDefinitions
                if (serverDefs != null) {
                    session.clientSession.peer.codecHelper.blockDefinitions = serverDefs
                }
            } else {
                val serverDefs = session.serverSession?.peer?.codecHelper?.blockDefinitions
                if (serverDefs != null) {
                    session.clientSession.peer.codecHelper.blockDefinitions = serverDefs
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun applyItemComponents(packet: ItemComponentPacket, session: OxRelaySession) {
        try {
            val items = packet.items
            if (items.isEmpty()) {
                return
            }

            val registry = SimpleDefinitionRegistry.builder<ItemDefinition>()
                .addAll(items)
                .build()

            session.clientSession.peer.codecHelper.itemDefinitions = registry
            session.serverSession?.peer?.codecHelper?.itemDefinitions = registry
            itemComponentsApplied = true
        } catch (e: Exception) {
        }
    }

    private fun applyCreativeItemDefinitions(packet: CreativeContentPacket, session: OxRelaySession) {
        try {
            val contents = packet.contents
            if (contents.isEmpty()) {
                return
            }

            val byRuntimeId = LinkedHashMap<Int, ItemDefinition>()
            for (creativeItem in contents) {
                val itemData = creativeItem.item
                val def = runCatching { itemData.definition }.getOrNull()
                if (def == null) continue
                val identifier = def.identifier
                if (identifier.isNullOrBlank()) continue
                if (identifier == "minecraft:air") continue
                byRuntimeId.putIfAbsent(def.runtimeId, def)
            }

            if (byRuntimeId.isEmpty()) {
                return
            }

            val itemRegistry = SimpleDefinitionRegistry.builder<ItemDefinition>()
                .addAll(byRuntimeId.values)
                .build()

            session.clientSession.peer.codecHelper.itemDefinitions = itemRegistry
            session.serverSession?.peer?.codecHelper?.itemDefinitions = itemRegistry
        } catch (e: Exception) {
        }
    }

    private fun applyCameraDefinitions(packet: CameraPresetsPacket, session: OxRelaySession) {
        try {
            val cameraDefs = SimpleDefinitionRegistry.builder<NamedDefinition>()
                .addAll(
                    packet.presets.mapIndexed { i, preset ->
                        SimpleNamedDefinition(preset.identifier, i)
                    }
                )
                .build()

            session.clientSession.peer.codecHelper.cameraPresetDefinitions = cameraDefs
            session.serverSession?.peer?.codecHelper?.cameraPresetDefinitions = cameraDefs
        } catch (e: Exception) {
        }
    }
}
