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

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir"
) {
    enum class CritMode { Vanilla, MovePacket, Jump, TPJump, Packet }

    private val mode     = enum ("Mode",          CritMode.Vanilla)
    private val cooldown = int  ("Cooldown",       100, 0, 500)
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

        // FIX: transactionData + ItemUseOnEntityData yerine direkt packet field'ları kullanılıyor.
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
        when (mode.value) {
            CritMode.Vanilla    -> injectVanilla(session)
            CritMode.MovePacket -> injectMovePacket(session)
            CritMode.Jump       -> injectJump(session)
            CritMode.TPJump     -> injectTPJump(session)
            CritMode.Packet     -> injectPacket(session)
        }
    }

    private fun injectVanilla(s: com.oxclient.core.relay.OxRelaySession) {
        listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = dy == 0f)
        }
    }

    private fun injectMovePacket(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.11f, onGround = false)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = true)
    }

    private fun injectJump(s: com.oxclient.core.relay.OxRelaySession) {
        listOf(0.0625f, 0f, 0.0625f, 0f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = dy == 0f)
        }
    }

    private fun injectTPJump(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false, teleport = true)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = true,  teleport = true)
    }

    private fun injectPacket(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0001f, onGround = false)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,      onGround = false)
    }
}
