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

class GamingPacketListener : OxPacketListener {

    companion object {
        private const val TAG = "GamingPacketListener"
    }

    override val priority: Int = 100

    @Volatile private var sessionActive = false

    override fun onSessionStart(session: OxRelaySession) {
        sessionActive = true
        OverlayLogger.i(TAG, "Gaming listener aktif: ${session.clientAddress}")
    }

    override fun onSessionEnd(session: OxRelaySession) {
        sessionActive = false
        OverlayLogger.i(TAG, "Gaming listener sonlandı: ${session.clientAddress}")
    }

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        if (!sessionActive) return true
        when (packet) {
            is MovePlayerPacket        -> { /* yüksek frekanslı — loglama yok */ }
            is PlayerAuthInputPacket   -> { /* yüksek frekanslı — loglama yok */ }
            is PlayerActionPacket      -> Log.v(TAG, "PlayerAction: ${packet.action}")
            is InteractPacket          -> Log.v(TAG, "Interact: ${packet.action} → entity ${packet.runtimeEntityId}")
            is InventoryTransactionPacket -> Log.v(TAG, "InventoryTransaction: ${packet.transactionType}")
            is CommandRequestPacket    -> Log.d(TAG, "Command: ${packet.command}")
            is TextPacket              -> Log.d(TAG, "Chat (C→S): ${packet.message}")
            is DisconnectPacket        -> OverlayLogger.i(TAG, "Client disconnect talebi: ${packet.kickMessage}")
        }
        return true
    }

    override fun onServerPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        if (!sessionActive) return true
        when (packet) {

            // ── StartGamePacket ───────────────────────────────────────────
            // Item/block definitionlarını hem client hem server codec helper'a
            // set etmek zorunlu — aksi hâlde 1.21+ sürümlerde bağlantı
            // "çok oyuncuya bağlanılıyor" ekranında kalıp timeout'a düşer.
            is StartGamePacket -> {
                OverlayLogger.i(TAG, "StartGame alındı — entityId=${packet.runtimeEntityId}")

                try {
                    // Item definitions
                    val itemDefs = SimpleDefinitionRegistry.builder<ItemDefinition>()
                        .addAll(packet.itemDefinitions)
                        .build()

                    session.clientSession.peer.codecHelper.itemDefinitions = itemDefs
                    session.serverSession?.peer?.codecHelper?.itemDefinitions = itemDefs

                    // Block definitions — sunucu hashed ID kullanıyor mu?
                    if (packet.isBlockNetworkIdsHashed) {
                        // Hashed modda blockDefinitionsHashed gerekir.
                        // Bu değer Definitions objesinden veya NBT'den gelir.
                        // Şimdilik server'dan gelen codec helper'ın mevcut
                        // blockDefinitions'ını kopyalıyoruz (en güvenli yol).
                        val hashedDefs = session.serverSession?.peer?.codecHelper?.blockDefinitions
                        if (hashedDefs != null) {
                            session.clientSession.peer.codecHelper.blockDefinitions = hashedDefs
                        }
                        Log.d(TAG, "Block definitions: HASHED mod")
                    } else {
                        val normalDefs = session.serverSession?.peer?.codecHelper?.blockDefinitions
                        if (normalDefs != null) {
                            session.clientSession.peer.codecHelper.blockDefinitions = normalDefs
                        }
                        Log.d(TAG, "Block definitions: normal mod")
                    }

                    Log.i(TAG, "Definitions set edildi — itemDefs=${packet.itemDefinitions.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "StartGame definitions hatası: ${e.message}", e)
                }

                ConnectionManager.onGameStarted()
            }

            // ── CameraPresetsPacket ───────────────────────────────────────
            // Camera definitions set edilmezse 1.21.20+ sürümlerde crash olur.
            is CameraPresetsPacket -> {
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

                    Log.d(TAG, "CameraPresets set edildi: ${packet.presets.size} preset")
                } catch (e: Exception) {
                    Log.e(TAG, "CameraPresets hatası: ${e.message}", e)
                }
            }

            is RespawnPacket          -> Log.d(TAG, "Respawn: ${packet.position}, state=${packet.state}")
            is DisconnectPacket       -> OverlayLogger.w(TAG, "Server disconnect: ${packet.kickMessage}")
            is TextPacket             -> Log.v(TAG, "Chat (S→C): ${packet.message}")
            is SetHealthPacket        -> Log.v(TAG, "Health: ${packet.health}")
            is UpdateAttributesPacket -> { /* yüksek frekanslı */ }
            is MoveEntityAbsolutePacket -> { /* yüksek frekanslı */ }
            is AddEntityPacket        -> Log.v(TAG, "AddEntity: type=${packet.identifier}, id=${packet.runtimeEntityId}")
            is RemoveEntityPacket     -> Log.v(TAG, "RemoveEntity: id=${packet.uniqueEntityId}")
            is PlayerListPacket       -> Log.v(TAG, "PlayerList: action=${packet.action}, count=${packet.entries.size}")
            is ChangeDimensionPacket  -> OverlayLogger.i(TAG, "ChangeDimension → dim=${packet.dimension}")
        }
        return true
    }
}
