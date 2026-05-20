package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.ConnectionManager
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.ui.overlay.OverlayLogger
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleNamedDefinition
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry

/**
 * GamingPacketListener — Oyun içi paket işleme.
 *
 * ═══════════════════════════════════════════════════════════════════
 * KRİTİK: StartGamePacket gelince item/block/camera definitions
 * her iki tarafa da set edilmezse 1.21+ sunucularında bağlantı
 * "Çok oyuncuya bağlanılıyor" ekranında kalıp timeout'a düşer.
 *
 * WRelay GamingPacketHandler referansıyla yazılmıştır.
 * ═══════════════════════════════════════════════════════════════════
 */
class GamingPacketListener : OxPacketListener {

    companion object {
        private const val TAG = "GamingPacketListener"
    }

    override val priority: Int = 100

    @Volatile private var active = false

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onSessionStart(session: OxRelaySession) {
        active = true
        Log.i(TAG, "Gaming listener aktif: ${session.clientAddress}")
    }

    override fun onSessionEnd(session: OxRelaySession) {
        active = false
        EntityTracker.reset()
        Log.i(TAG, "Gaming listener sonlandı: ${session.clientAddress}")
    }

    // ── Client → Server ───────────────────────────────────────────────────

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        if (!active) return true
        when (packet) {
            is MovePlayerPacket          -> { /* yüksek frekanslı */ }
            is PlayerAuthInputPacket     -> { /* yüksek frekanslı */ }
            is PlayerActionPacket        -> Log.v(TAG, "PlayerAction: ${packet.action}")
            is InteractPacket            -> Log.v(TAG, "Interact: ${packet.action} e=${packet.runtimeEntityId}")
            is InventoryTransactionPacket-> Log.v(TAG, "InventoryTx: ${packet.transactionType}")
            is CommandRequestPacket      -> Log.d(TAG, "Command: ${packet.command}")
            is TextPacket                -> Log.d(TAG, "Chat C→S: ${packet.message}")
            is AnimatePacket             -> Log.v(TAG, "Animate: ${packet.action}")
            is DisconnectPacket          -> OverlayLogger.i(TAG, "Client disconnect: ${packet.kickMessage}")
        }
        return true
    }

    // ── Server → Client ───────────────────────────────────────────────────

    override fun onServerPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        if (!active) return true
        when (packet) {

            // ── StartGamePacket ───────────────────────────────────────────
            // Item + Block definitions her iki tarafa set edilmeli.
            // Aksi hâlde client inventory paketlerini decode edemez → timeout.
            is StartGamePacket -> {
                OverlayLogger.i(TAG, "StartGame → entityId=${packet.runtimeEntityId} dim=${packet.dimensionId}")
                applyStartGameDefinitions(packet, session)
                ConnectionManager.onGameStarted()
            }

            // ── CameraPresetsPacket ───────────────────────────────────────
            // MC 1.21.20+ sürümlerde CameraPreset definitions yoksa crash.
            is CameraPresetsPacket -> {
                applyCameraDefinitions(packet, session)
            }

            // ── Entity tracking ───────────────────────────────────────────
            is AddPlayerPacket -> {
                Log.v(TAG, "AddPlayer: ${packet.username} eid=${packet.runtimeEntityId}")
                EntityTracker.addPlayer(packet)
            }
            is AddEntityPacket -> {
                Log.v(TAG, "AddEntity: ${packet.identifier} eid=${packet.runtimeEntityId}")
                EntityTracker.addEntity(packet)
            }
            is RemoveEntityPacket -> {
                Log.v(TAG, "RemoveEntity: uid=${packet.uniqueEntityId}")
                EntityTracker.removeEntity(packet.uniqueEntityId)
            }
            is MoveEntityAbsolutePacket -> {
                EntityTracker.updatePosition(packet)
            }
            is MovePlayerPacket -> {
                EntityTracker.updatePlayerPosition(packet)
            }

            // ── Diğer oyun paketleri ──────────────────────────────────────
            is RespawnPacket          -> Log.d(TAG, "Respawn: ${packet.position} state=${packet.state}")
            is SetHealthPacket        -> Log.v(TAG, "Health: ${packet.health}")
            is UpdateAttributesPacket -> { /* yüksek frekanslı */ }
            is PlayerListPacket       -> Log.v(TAG, "PlayerList: ${packet.action} count=${packet.entries.size}")
            is ChangeDimensionPacket  -> OverlayLogger.i(TAG, "ChangeDimension → dim=${packet.dimension}")
            is TextPacket             -> Log.v(TAG, "Chat S→C: ${packet.message}")
            is DisconnectPacket       -> OverlayLogger.w(TAG, "Server Disconnect: ${packet.kickMessage}")
            is TransferPacket         -> OverlayLogger.i(TAG, "Transfer → ${packet.address}:${packet.port}")
        }
        return true
    }

    // ── Definition Helpers ────────────────────────────────────────────────

    private fun applyStartGameDefinitions(packet: StartGamePacket, session: OxRelaySession) {
        // Item definitions
        try {
            val itemRegistry = SimpleDefinitionRegistry.builder<ItemDefinition>()
                .addAll(packet.itemDefinitions)
                .build()

            session.clientSession.peer.codecHelper.itemDefinitions = itemRegistry
            session.serverSession?.peer?.codecHelper?.itemDefinitions = itemRegistry
            Log.d(TAG, "ItemDefinitions set: ${packet.itemDefinitions.size} item")
        } catch (e: Exception) {
            Log.e(TAG, "ItemDefinitions hatası: ${e.message}", e)
        }

        // Block definitions — hashed vs normal
        try {
            if (packet.isBlockNetworkIdsHashed) {
                // Hashed mod: server'ın mevcut blockDefinitions'ını kopyala
                val serverDefs = session.serverSession?.peer?.codecHelper?.blockDefinitions
                if (serverDefs != null) {
                    session.clientSession.peer.codecHelper.blockDefinitions = serverDefs
                    Log.d(TAG, "BlockDefinitions set: HASHED mod (server'dan kopyalandı)")
                } else {
                    Log.w(TAG, "BlockDefinitions: server defs null — atlanıyor")
                }
            } else {
                // Normal mod: server'ın mevcut blockDefinitions'ını kopyala
                val serverDefs = session.serverSession?.peer?.codecHelper?.blockDefinitions
                if (serverDefs != null) {
                    session.clientSession.peer.codecHelper.blockDefinitions = serverDefs
                    Log.d(TAG, "BlockDefinitions set: normal mod (server'dan kopyalandı)")
                } else {
                    Log.w(TAG, "BlockDefinitions: server defs null — atlanıyor")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BlockDefinitions hatası: ${e.message}", e)
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
            Log.d(TAG, "CameraDefinitions set: ${packet.presets.size} preset")
        } catch (e: Exception) {
            Log.e(TAG, "CameraDefinitions hatası: ${e.message}", e)
        }
    }
}
