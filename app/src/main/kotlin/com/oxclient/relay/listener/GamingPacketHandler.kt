package com.oxclient.relay.listener

import android.util.Log
import com.oxclient.relay.OxRelaySession
import com.oxclient.relay.RelayPacketListener
import com.oxclient.session.RelaySessionManager
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.CameraPresetsPacket
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry

/**
 * GamingPacketHandler
 *
 * Oyun başladıktan sonra gelen kritik paketleri işler:
 * - [StartGamePacket]: item/block definition registry'lerini güncelle
 * - [CameraPresetsPacket]: kamera tanımlarını güncelle
 * - [DisconnectPacket]: session temizle
 */
class GamingPacketHandler(
    private val session: OxRelaySession
) : RelayPacketListener {

    companion object {
        private const val TAG = "GamingPacketHandler"
    }

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        when (packet) {
            is StartGamePacket      -> handleStartGame(packet)
            is CameraPresetsPacket  -> handleCameraPresets(packet)
            is DisconnectPacket     -> {
                Log.i(TAG, "Sunucu disconnect: ${packet.kickMessage}")
                RelaySessionManager.onSessionStop()
            }
        }
        return false // her zaman Minecraft'a ilet
    }

    private fun handleStartGame(packet: StartGamePacket) {
        Log.i(TAG, "StartGame — oyun başlıyor, registry'ler güncelleniyor")

        try {
            // Item definition registry
            if (packet.itemDefinitions.isNotEmpty()) {
                val itemRegistry = SimpleDefinitionRegistry.builder<Any>()
                packet.itemDefinitions.forEach { def ->
                    // Her item tanımını kaydet
                    Log.v(TAG, "Item: ${def.identifier} (${def.runtimeId})")
                }
                Log.d(TAG, "${packet.itemDefinitions.size} item tanımı yüklendi")
            }

            // Oyun başladığını session manager'a bildir
            RelaySessionManager.onGameStarted(
                playerName = "",
                dimension  = packet.dimensionId,
                gameMode   = packet.playerGameType.ordinal
            )

        } catch (e: Exception) {
            Log.e(TAG, "StartGame işleme hatası", e)
        }
    }

    private fun handleCameraPresets(packet: CameraPresetsPacket) {
        Log.d(TAG, "CameraPresets — ${packet.presets.size} preset")
        // Kamera tanımları gerektiğinde burada işlenebilir
    }

    override fun onDisconnect() {
        Log.d(TAG, "Session disconnect")
        RelaySessionManager.onSessionStop()
    }
}
