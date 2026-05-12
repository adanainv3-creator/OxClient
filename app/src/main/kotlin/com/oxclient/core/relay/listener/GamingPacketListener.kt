package com.oxclient.core.relay.listener

import android.util.Log
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.definition.Definitions
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleNamedDefinition
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry

/**
 * GamingPacketListener — Oyun sırasında gerekli tanım senkronizasyonu.
 * WRelay GamingPacketHandler'dan adapte edildi.
 *
 * StartGamePacket    → item/block tanımlarını güncelle
 * CameraPresetsPacket → kamera preset tanımlarını güncelle
 */
class GamingPacketListener(
    private val session: OxRelaySession
) : OxPacketListener {

    private val TAG = "GamingPacketListener"

    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        when (packet) {
            is StartGamePacket -> handleStartGame(packet)
            is CameraPresetsPacket -> handleCameraPresets(packet)
            is DisconnectPacket -> {
                Log.i(TAG, "Sunucu disconnect gönderdi: ${packet.kickMessage}")
            }
        }
        return false  // paketi durdurma, sadece yan etki
    }

    private fun handleStartGame(packet: StartGamePacket) {
        try {
            // Item tanımlarını güncelle
            Definitions.itemDefinitions = SimpleDefinitionRegistry.builder<ItemDefinition>()
                .addAll(packet.itemDefinitions)
                .build()

            val blockDefs = if (packet.isBlockNetworkIdsHashed)
                Definitions.blockDefinitionsHashed
            else
                Definitions.blockDefinitions

            session.client?.peer?.codecHelper?.apply {
                itemDefinitions  = Definitions.itemDefinitions
                blockDefinitions = blockDefs
            }
            session.server.peer.codecHelper.apply {
                itemDefinitions  = Definitions.itemDefinitions
                blockDefinitions = blockDefs
            }

            Log.i(TAG, "StartGame tanımları senkronize edildi (hashed=${packet.isBlockNetworkIdsHashed})")
        } catch (e: Exception) {
            Log.e(TAG, "StartGame tanım hatası: ${e.message}", e)
        }
    }

    private fun handleCameraPresets(packet: CameraPresetsPacket) {
        try {
            val defs = SimpleDefinitionRegistry.builder<NamedDefinition>()
                .addAll(packet.presets.mapIndexed { i, preset ->
                    SimpleNamedDefinition(preset.identifier, i)
                })
                .build()

            session.client?.peer?.codecHelper?.cameraPresetDefinitions = defs
            session.server.peer.codecHelper.cameraPresetDefinitions    = defs

            Log.d(TAG, "CameraPresets güncellendi (${packet.presets.size} preset)")
        } catch (e: Exception) {
            Log.e(TAG, "CameraPresets hatası: ${e.message}", e)
        }
    }
}
