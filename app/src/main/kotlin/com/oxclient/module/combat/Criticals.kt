package com.oxclient.module.combat

import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.CritLock
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir"
) {
    enum class CritMode { Vanilla, MovePacket, Jump, TPJump, Packet }

    private val mode     = enum ("Mode",      CritMode.MovePacket)
    private val cooldown = int  ("Cooldown",   0, 0, 500)
    private val shortcut = bool ("Shortcut",   false)

    @Volatile private var lastCritMs = 0L

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? InventoryTransactionPacket ?: return

        val isAttack = pkt.transactionType == InventoryTransactionType.ITEM_USE_ON_ENTITY &&
                       pkt.actionType == 1

        if (!isAttack) return

        val now = System.currentTimeMillis()
        if (now - lastCritMs < cooldown.value) return
        lastCritMs = now

        val session = PacketEventBus.currentSession ?: return

        // ÖNEMLİ FIX: eskiden bütün sahte düşüş paketleri 0ms arayla art arda
        // gönderiliyor, sonra orijinal saldırı paketi hemen forward ediliyordu.
        // Sunucu bu paketleri aynı tick içinde işlediği için hiçbir zaman
        // gerçek bir "düşüyor" durumu oluşmuyordu, dolayısıyla kritik hiç
        // tetiklenmiyordu. Artık orijinal paketi iptal edip, düşüş paketlerini
        // gerçek zaman aralıklarıyla (delay ile) gönderiyoruz, en son da
        // orijinal saldırı paketini kendimiz iletiyoruz.
        event.cancel()

        scope.launch {
            CritLock.tryRun {
                when (mode.value) {
                    CritMode.Vanilla    -> injectVanilla(session)
                    CritMode.MovePacket -> injectMovePacket(session)
                    CritMode.Jump       -> injectJump(session)
                    CritMode.TPJump     -> injectTPJump(session)
                    CritMode.Packet     -> injectPacket(session)
                }
            }
            session.serverBound(pkt)
        }
    }

    private suspend fun injectVanilla(s: com.oxclient.core.relay.OxRelaySession) {
        listOf(0.42f, 0.33f, 0.24f, 0.16f, 0.09f, 0.03f, 0f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = false)
            delay(25L)
        }
    }

    private suspend fun injectMovePacket(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.11f, onGround = false)
        delay(30L)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f, onGround = false)
    }

    private suspend fun injectJump(s: com.oxclient.core.relay.OxRelaySession) {
        listOf(0.0625f, 0f, 0.0625f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = false)
            delay(25L)
        }
    }

    private suspend fun injectTPJump(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false, teleport = true)
        delay(30L)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,    onGround = false, teleport = true)
    }

    private suspend fun injectPacket(s: com.oxclient.core.relay.OxRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0001f, onGround = false)
        delay(15L)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0f,      onGround = false)
    }
}
