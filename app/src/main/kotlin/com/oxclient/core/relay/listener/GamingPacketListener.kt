package com.oxclient.core.relay.listener

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
        OverlayLogger.i(TAG, "Gaming listener aktif: ${session.clientAddress}")
    }

    override fun onSessionEnd(session: OxRelaySession) {
        active = false
        EntityTracker.reset()
        OverlayLogger.i(TAG, "Gaming listener sonlandı: ${session.clientAddress}")
    }

    // ── Client → Server ───────────────────────────────────────────────────

    override fun onClientPacket(packet: BedrockPacket, session: OxRelaySession): Boolean {
        if (!active) return true
        when (packet) {
            is MovePlayerPacket          -> { /* yüksek frekanslı */ }
            is PlayerAuthInputPacket     -> { /* yüksek frekanslı */ }
            is PlayerActionPacket        -> OverlayLogger.v(TAG, "PlayerAction: ${packet.action}")
            is InteractPacket            -> OverlayLogger.v(TAG, "Interact: ${packet.action} e=${packet.runtimeEntityId}")
            is InventoryTransactionPacket-> OverlayLogger.v(TAG, "InventoryTx: ${packet.transactionType}")
            is CommandRequestPacket      -> OverlayLogger.d(TAG, "Command: ${packet.command}")
            is TextPacket                -> OverlayLogger.d(TAG, "Chat C→S: ${packet.message}")
            is AnimatePacket             -> OverlayLogger.v(TAG, "Animate: ${packet.action}")
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

            // Entity tracking PacketEventBus üzerinden EntityTracker tarafından
            // otomatik yapılıyor (EntityTracker.init() → PacketEventBus.register())
            // Burada sadece logluyoruz.
            is AddPlayerPacket          -> OverlayLogger.v(TAG, "AddPlayer: ${packet.username} eid=${packet.runtimeEntityId}")
            is AddEntityPacket          -> OverlayLogger.v(TAG, "AddEntity: ${packet.identifier} eid=${packet.runtimeEntityId}")
            is RemoveEntityPacket       -> OverlayLogger.v(TAG, "RemoveEntity: uid=${packet.uniqueEntityId}")
            is MoveEntityAbsolutePacket -> { /* yüksek frekanslı */ }
            is MovePlayerPacket         -> { /* yüksek frekanslı */ }

            // ── Diğer oyun paketleri ──────────────────────────────────────
            is RespawnPacket          -> OverlayLogger.d(TAG, "Respawn: ${packet.position} state=${packet.state}")
            is SetHealthPacket        -> OverlayLogger.v(TAG, "Health: ${packet.health}")
            is UpdateAttributesPacket -> { /* yüksek frekanslı */ }
            is PlayerListPacket       -> OverlayLogger.v(TAG, "PlayerList: ${packet.action} count=${packet.entries.size}")
            is ChangeDimensionPacket  -> OverlayLogger.i(TAG, "ChangeDimension → dim=${packet.dimension}")
            is TextPacket             -> OverlayLogger.v(TAG, "Chat S→C: ${packet.message}")
            is DisconnectPacket       -> OverlayLogger.w(TAG, "Server Disconnect: ${packet.kickMessage}")

            // ── TransferPacket ──────────────────────────────────────────
            // Server, oyuncuyu başka bir sunucuya yönlendirmek istiyor
            // (hub→game server mimarisi). Önceden: sadece loglanıp client'a
            // OLDUĞU GİBİ iletiliyordu → client relay'i bypass edip doğrudan
            // yeni sunucuya bağlanıyordu (MITM/modüller devre dışı kalıyordu).
            //
            // Şimdi: relay'in hedefini güncelliyoruz ve client'a KENDİ
            // adresimizi gönderiyoruz — client relay'e geri bağlanıyor,
            // relay de bir sonraki session'da yeni hedefe bağlanıyor.
            //
            // NOT: clientReconnectHost aşağıda client'ın session'a bağlanırken
            // kullandığı local adresten okunuyor. Eğer relay ile gerçek
            // Minecraft client'ı AYNI cihazda değilse (örn. relay Android'de,
            // client başka bir cihazda LAN'dan bağlanıyorsa) bu adresin senin
            // ağında doğru/erişilebilir olduğunu doğrulaman gerekir.
            is TransferPacket -> {
                val newHost = packet.address
                val newPort = packet.port
                OverlayLogger.i(TAG, "Transfer → $newHost:$newPort (relay üzerinden yönlendiriliyor)")

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
                    OverlayLogger.e(TAG, "Transfer yönlendirme hatası: ${e.message}", e)
                }

                return false // orijinal transfer'ı OLDUĞU GİBİ iletme
            }
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
            OverlayLogger.d(TAG, "ItemDefinitions set: ${packet.itemDefinitions.size} item")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "ItemDefinitions hatası: ${e.message}", e)
        }

        // Block definitions — hashed vs normal
        try {
            if (packet.isBlockNetworkIdsHashed) {
                // Hashed mod: server'ın mevcut blockDefinitions'ını kopyala
                val serverDefs = session.serverSession?.peer?.codecHelper?.blockDefinitions
                if (serverDefs != null) {
                    session.clientSession.peer.codecHelper.blockDefinitions = serverDefs
                    OverlayLogger.d(TAG, "BlockDefinitions set: HASHED mod (server'dan kopyalandı)")
                } else {
                    OverlayLogger.w(TAG, "BlockDefinitions: server defs null — atlanıyor")
                }
            } else {
                // Normal mod: server'ın mevcut blockDefinitions'ını kopyala
                val serverDefs = session.serverSession?.peer?.codecHelper?.blockDefinitions
                if (serverDefs != null) {
                    session.clientSession.peer.codecHelper.blockDefinitions = serverDefs
                    OverlayLogger.d(TAG, "BlockDefinitions set: normal mod (server'dan kopyalandı)")
                } else {
                    OverlayLogger.w(TAG, "BlockDefinitions: server defs null — atlanıyor")
                }
            }
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "BlockDefinitions hatası: ${e.message}", e)
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
            OverlayLogger.d(TAG, "CameraDefinitions set: ${packet.presets.size} preset")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "CameraDefinitions hatası: ${e.message}", e)
        }
    }
}
