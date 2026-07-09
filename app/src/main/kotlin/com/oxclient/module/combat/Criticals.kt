package com.oxclient.module.combat

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.PacketUtil
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket

// FIX: ItemUseOnEntityData sınıfı yeni Cloudburst API'sinde (3.x) kaldırıldı.
// Saldırı tespiti artık InventoryTransactionPacket üzerinden yapılır:
//   - transactionType == ITEM_USE_ON_ENTITY  → entity'ye kullanım
//   - actionType == 1                        → ATTACK (0=Interact, 1=Attack, 2=ItemInteract)
//
// FIX (kritik gitmeme sorunu): Vanilla/Jump/MovePacket/TPJump modlarının HEPSİ
// enjeksiyon zincirinin SON adımında dy==0f olduğu için onGround=true
// gönderiyordu. Kritik vuruş şartı: fallDistance > 0 && !onGround.
// Yani attack paketi sunucuya gitmeden bir adım önce, biz kendi elimizle
// "artık yerdeyim" diyorduk ve sunucu attack'i işlerken oyuncuyu onGround=true
// görüp kriti asla vermiyordu. Şimdi her modda SON paket onGround=false
// kalıyor; "yere iniş" paketi hiç gönderilmiyor (doğal görünüm önemli değil).
class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir"
) {
    enum class CritMode { Vanilla, MovePacket, Jump, TPJump, Packet }

    private val mode     = enum ("Mode",          CritMode.MovePacket)
    private val cooldown = int  ("Cooldown",       0, 0, 500)
    private val shortcut = bool ("Shortcut",       false)

    private companion object { const val TAG = "Criticals" }

    @Volatile private var lastCritMs = 0L

    override fun onEnable() {
        super.onEnable()
        OverlayLogger.d(TAG, "Enabled: mode=${mode.value} cooldown=${cooldown.value}")
    }

    override fun onDisable() {
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled")
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? InventoryTransactionPacket ?: return

        // actionType: 0 = Interact, 1 = Attack, 2 = ItemInteract
        val isAttack = pkt.transactionType == InventoryTransactionType.ITEM_USE_ON_ENTITY &&
                       pkt.actionType == 1

        if (!isAttack) {
            OverlayLogger.v(TAG, "onPacket: InventoryTransactionPacket ama attack değil (transactionType=${pkt.transactionType} actionType=${pkt.actionType})")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastCritMs < cooldown.value) {
            OverlayLogger.v(TAG, "Attack tespit edildi ama cooldown içinde (kalan=${cooldown.value - (now - lastCritMs)}ms)")
            return
        }
        lastCritMs = now

        val session = PacketEventBus.currentSession ?: run {
            OverlayLogger.w(TAG, "onPacket: session null — relay bağlı değil")
            return
        }
        OverlayLogger.v(TAG, "Attack tespit edildi, crit enjekte ediliyor: mode=${mode.value}")
        // NOT: Bu enjeksiyonlar, event henüz iptal edilmediği (event.packet
        // forward edilmeden önce) çalıştığı için sunucuya orijinal attack
        // paketinden ÖNCE ulaşır. Bu yüzden zincirin EN SON adımı ne
        // gönderiyorsa, sunucu attack'i işlerken oyuncuyu o durumda görür.
        // Bu yüzden hiçbir zincir onGround=true ile bitmemeli.
        //
        // ANARŞI SERVER OPTİMİZASYON:
        // - Mode: MovePacket = minimal paket overhead, maksimum CPS
        // - Cooldown: 0 = hiçbir delay yok, her vuruş kritik
        // - Anti-cheat olmayan sunucularda en yüksek DPS
        when (mode.value) {
            CritMode.Vanilla    -> injectVanilla(session)
            CritMode.MovePacket -> injectMovePacket(session)
            CritMode.Jump       -> injectJump(session)
            CritMode.TPJump     -> injectTPJump(session)
            CritMode.Packet     -> injectPacket(session)
        }
    }

    // Gerçekçi düşüş arkı: yukarı sıçra, sonra gerçek Y'ye kadar düş —
    // ama ASLA onGround=true ile bitirme.
    private fun injectVanilla(s: com.oxclient.core.relay.OxRelaySession) {
        listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = false)
        }
        // Zincirin sonu: gerçek Y'ye dönüyoruz ama hâlâ havadayız (onGround=false)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f, onGround = false)
    }

    private fun injectMovePacket(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.11f, onGround = false)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = false)
    }

    private fun injectJump(s: com.oxclient.core.relay.OxRelaySession) {
        listOf(0.0625f, 0f, 0.0625f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = false)
        }
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f, onGround = false)
    }

    // En güvenilir mod: teleport ile ani yükseliş enjekte edip anti-cheat'in
    // "hız hilesi" olarak reddetmesini engelliyoruz, sonra normal düşüş paketiyle
    // gerçek Y'ye dönüyoruz — hep onGround=false.
    private fun injectTPJump(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false, teleport = true)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = false, teleport = true)
    }

    private fun injectPacket(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0001f, onGround = false)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,      onGround = false)
    }
}
