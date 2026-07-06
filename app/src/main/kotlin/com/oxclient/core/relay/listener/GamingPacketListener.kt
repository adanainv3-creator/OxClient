package com.oxclient.core.relay.listener

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.ConnectionManager
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.BlockTracker
import com.oxclient.utils.ChunkParser
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleNamedDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
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
                // ✅ FIX: Blok paleti artık ESP modülünün enable durumundan BAĞIMSIZ,
                // oturum seviyesinde her zaman yükleniyor. Önceden bu çağrı sadece
                // ESP.onPacket() içindeydi — StartGamePacket bağlantı başında BİR KERE
                // geldiği için, ESP oyuna girdikten sonra açılırsa palet asla yüklenmiyor
                // ve ESP sürekli eski/statik BLOCK_ID_MAP'e (eşleşmeyen id'ler) düşüyordu.
                BlockTracker.loadPalette(packet)
                ConnectionManager.onGameStarted()
            }

            // ── Blok takibi (ESP için veri kaynağı) ───────────────────────
            // Aşağıdaki üç paket de artık modül enable durumundan bağımsız,
            // oturum boyunca her zaman BlockTracker'a besleniyor (EntityTracker'ın
            // entity verisini her zaman toplaması ile aynı mantık). ESP modülü
            // sadece BlockTracker'daki veriyi kendi ayarlarına göre FİLTRELEYİP
            // render ediyor — artık veri toplama işini yapmıyor.
            is UpdateBlockPacket -> handleUpdateBlock(packet)
            is BlockEntityDataPacket -> handleBlockEntity(packet)
            is LevelChunkPacket -> handleChunk(packet)

            // ── CameraPresetsPacket ───────────────────────────────────────
            // MC 1.21.20+ sürümlerde CameraPreset definitions yoksa crash.
            is CameraPresetsPacket -> {
                applyCameraDefinitions(packet, session)
            }

            // ── CreativeContentPacket ──────────────────────────────────────
            // 2b2tpe.org gibi bazı sunucular StartGamePacket.itemDefinitions
            // alanını BOŞ gönderiyor. Bu durumda codec'in fallback registry'si
            // devreye giriyor ama definition → identifier çözümlemesi
            // (isTotem(), inventory karşılaştırmaları vb.) çalışmıyor.
            //
            // CreativeContentPacket, yaratıcı moddaki TÜM item'ların
            // ItemData'sını (ve dolayısıyla ItemDefinition'ını) içerir —
            // StartGame'in aksine bu paket sunucudan HER ZAMAN dolu gelir
            // (aksi halde client'ın yaratıcı envanteri boş kalırdı).
            // Bu yüzden ItemComponentPacket'i parse etmek yerine bu paketi
            // yakalayıp gerçek bir ItemDefinition registry'si kuruyoruz.
            is CreativeContentPacket -> {
                applyCreativeItemDefinitions(packet, session)
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

    // ── Block Tracking Helpers (BlockTracker'ın veri kaynağı) ─────────────

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
        // Chunk ilk yüklendiğinde zaten var olan block-entity'leri (sandık/spawner/vb.)
        // yakalamak için. Parse başarısız olursa sessizce atlanır — relay passthrough'u
        // etkilemez, o blok UpdateBlockPacket/BlockEntityDataPacket geldiğinde yine yakalanır.
        val entities = try { ChunkParser.extractBlockEntities(pkt) } catch (e: Exception) {
            OverlayLogger.v(TAG, "Chunk parse hatası: ${e.message}")
            return
        }
        if (entities.isEmpty()) return
        for (be in entities) {
            val id = be.tag.getString("id") ?: continue
            val type = BlockTracker.resolveBlockName(id) ?: continue
            BlockTracker.add(be.x, be.y, be.z, type)
        }
    }

    // ── Definition Helpers ────────────────────────────────────────────────

    private fun applyStartGameDefinitions(packet: StartGamePacket, session: OxRelaySession) {
        // Item definitions
        try {
            // ✅ DEBUG: StartGame'den ÖNCE codec'e hangi itemDefinitions registry'si
            // set edilmiş durumda (AutoCodecListener/varsayılan codec paleti) — bunu
            // baseline olarak logluyoruz, çünkü boşsa CreativeContent de zaten
            // bu bozuk baseline üstünden decode ediliyor demektir.
            val preExistingClient = runCatching { session.clientSession.peer.codecHelper.itemDefinitions }.getOrNull()
            val preExistingServer = runCatching { session.serverSession?.peer?.codecHelper?.itemDefinitions }.getOrNull()
            OverlayLogger.i(TAG, "StartGame ÖNCESİ itemDefinitions: client=${if (preExistingClient != null) "set (${preExistingClient})" else "null"} server=${if (preExistingServer != null) "set" else "null"}")

            if (packet.itemDefinitions.isNotEmpty()) {
                val itemRegistry = SimpleDefinitionRegistry.builder<ItemDefinition>()
                    .addAll(packet.itemDefinitions)
                    .build()

                session.clientSession.peer.codecHelper.itemDefinitions = itemRegistry
                session.serverSession?.peer?.codecHelper?.itemDefinitions = itemRegistry
                OverlayLogger.d(TAG, "ItemDefinitions set: ${packet.itemDefinitions.size} item")

                // ✅ DEBUG: totem burada var mı diye direkt kontrol et
                val totemDef = packet.itemDefinitions.firstOrNull { it.identifier == "minecraft:totem_of_undying" }
                OverlayLogger.i(TAG, "  StartGame.itemDefinitions içinde totem_of_undying: ${if (totemDef != null) "BULUNDU rid=${totemDef.runtimeId}" else "YOK"}")
            } else {
                OverlayLogger.w(TAG, "StartGame.itemDefinitions boş — mevcut codec item paleti korunuyor (üzerine yazılmadı)")
                OverlayLogger.w(TAG, "  → Baseline registry=${preExistingClient ?: "null"}; bu palet doğruysa sorun yok, değilse totem hiç çözülemeyecek")
            }
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

    /**
     * CreativeContentPacket içindeki her ItemData zaten kendi ItemDefinition'ını
     * taşır (netId decode edilirken codec bunu dolduruyor). StartGamePacket'in
     * aksine bu paket sunucudan asla boş gelmez, bu yüzden burdan çıkardığımız
     * definition seti StartGame'in boş bıraktığı durumda güvenilir bir fallback.
     *
     * air / null identifier'lı girişler (boş slot placeholder'ları) elenir,
     * runtimeId'ye göre dedupe edilir ve hem client hem server codecHelper'ına
     * aynı registry set edilir (WRelay'deki GamingPacketHandler mantığıyla aynı).
     */
    private fun applyCreativeItemDefinitions(packet: CreativeContentPacket, session: OxRelaySession) {
        try {
            val contents = packet.contents
            if (contents.isEmpty()) {
                OverlayLogger.w(TAG, "CreativeContent: contents boş — atlanıyor")
                return
            }

            // ✅ DEBUG: İlk 5 girişi HAM haliyle logla — definition null mu,
            // identifier blank mı, yoksa gerçekten air mi eleniyor tam olarak görelim.
            // Bu satır olmadan "kullanılabilir definition bulunamadı" derken NEDEN
            // bulunamadığını (def==null vs identifier boş vs hepsi air) ayırt edemiyoruz.
            contents.take(5).forEachIndexed { i, creativeItem ->
                val itemData = creativeItem.item
                val def = runCatching { itemData.definition }.getOrNull()
                OverlayLogger.d(TAG, "  [creative-debug $i] netId=${itemData.netId} count=${itemData.count} " +
                    "def=${if (def == null) "NULL" else "OK"} identifier=${def?.identifier ?: "N/A"} runtimeId=${def?.runtimeId ?: "N/A"} " +
                    "itemDataClass=${itemData::class.simpleName}")
            }

            var nullDefCount = 0
            var blankIdCount = 0
            var airCount = 0
            val byRuntimeId = LinkedHashMap<Int, ItemDefinition>()
            for (creativeItem in contents) {
                val itemData = creativeItem.item
                // ✅ FIX: itemData.definition Java tarafında @NotNull işaretli ama pratikte
                // null dönebiliyor (CreativeContent'teki bu sunucu/codec'te). Doğrudan
                // atama Kotlin'in platform-type null-assertion'ını tetikleyip crash atıyordu
                // (ilk null'da IllegalStateException, döngü hiç tamamlanamıyordu).
                // runCatching bunu güvenli şekilde null'a çeviriyor, döngü tüm 1455'i tarayabiliyor.
                val def = runCatching { itemData.definition }.getOrNull()
                if (def == null) { nullDefCount++; continue }
                val identifier = def.identifier
                if (identifier.isNullOrBlank()) { blankIdCount++; continue }
                if (identifier == "minecraft:air") { airCount++; continue }
                byRuntimeId.putIfAbsent(def.runtimeId, def)
            }
            OverlayLogger.i(TAG, "CreativeContent tarama sonucu: toplam=${contents.size} nullDef=$nullDefCount blankId=$blankIdCount air=$airCount kullanılabilir=${byRuntimeId.size}")

            if (byRuntimeId.isEmpty()) {
                OverlayLogger.w(TAG, "CreativeContent: kullanılabilir ItemDefinition bulunamadı (${contents.size} giriş tarandı) — atlanıyor")
                return
            }

            val itemRegistry = SimpleDefinitionRegistry.builder<ItemDefinition>()
                .addAll(byRuntimeId.values)
                .build()

            session.clientSession.peer.codecHelper.itemDefinitions = itemRegistry
            session.serverSession?.peer?.codecHelper?.itemDefinitions = itemRegistry

            OverlayLogger.i(TAG, "ItemDefinitions CreativeContent'ten set edildi ✓ (${byRuntimeId.size} benzersiz / ${contents.size} toplam item)")
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "CreativeContent ItemDefinitions hatası: ${e.message}", e)
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

