package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.ConnectionManager
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.ui.overlay.OverlayLogger
import org.cloudburstmc.protocol.bedrock.packet.*

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
            is MovePlayerPacket -> {
            }
            is PlayerAuthInputPacket -> {
            }
            is PlayerActionPacket -> {
                Log.v(TAG, "PlayerAction: ${packet.action}")
            }
            is InteractPacket -> {
                Log.v(TAG, "Interact: ${packet.action} → entity ${packet.runtimeEntityId}")
            }
            is InventoryTransactionPacket -> {
                Log.v(TAG, "InventoryTransaction: ${packet.transactionType}")
            }
            is CommandRequestPacket -> {
                Log.d(TAG, "Command: ${packet.command}")
            }
            is TextPacket -> {
                Log.d(TAG, "Chat (C→S): ${packet.message}")
            }
            is DisconnectPacket -> {
                OverlayLogger.i(TAG, "Client disconnect talebi: ${packet.kickMessage}")
            }
        }
        return true
    }

    override fun onServerPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        if (!sessionActive) return true
        when (packet) {
            is StartGamePacket -> {
                OverlayLogger.i(TAG, "StartGame alındı — oyun içi başladı (entityId=${packet.runtimeEntityId})")
                ConnectionManager.onGameStarted()
            }
            is RespawnPacket -> {
                Log.d(TAG, "Respawn: ${packet.position}, state=${packet.state}")
            }
            is DisconnectPacket -> {
                OverlayLogger.w(TAG, "Server disconnect: ${packet.kickMessage}")
            }
            is TextPacket -> {
                Log.v(TAG, "Chat (S→C): ${packet.message}")
            }
            is SetHealthPacket -> {
                Log.v(TAG, "Health: ${packet.health}")
            }
            is UpdateAttributesPacket -> {
            }
            is MoveEntityAbsolutePacket -> {
            }
            is AddEntityPacket -> {
                Log.v(TAG, "AddEntity: type=${packet.identifier}, id=${packet.runtimeEntityId}")
            }
            is RemoveEntityPacket -> {
                Log.v(TAG, "RemoveEntity: id=${packet.uniqueEntityId}")
            }
            is PlayerListPacket -> {
                Log.v(TAG, "PlayerList: action=${packet.action}, count=${packet.entries.size}")
            }
            is ChangeDimensionPacket -> {
                OverlayLogger.i(TAG, "ChangeDimension → dim=${packet.dimension}")
            }
        }
        return true
    }
}
