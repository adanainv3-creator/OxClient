package com.rubidiumclient.module.combat

import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.CritLock
import kotlinx.coroutines.*
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket

class Criticals : BaseModule(
    name        = "Criticals",
    category    = ModuleCategory.COMBAT,
    description = "Her vuruşu kritik hale getirir (ULTRA HIZLI)"
) {
    enum class CritMode { 
        Vanilla,      // Standart 7-packet
        Fast,         // 3-packet hızlı (ÖNERİLEN)
        UltraFast,    // 2-packet çok hızlı
        Packet        // Minimal packet
    }

    private val mode     = enum ("Mode",      CritMode.Fast)
    private val cooldown = int  ("Cooldown",  0, 0, 100)
    private val shortcut = bool ("Shortcut",  false)

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
        event.cancel()

        scope.launch {
            CritLock.tryRun {
                when (mode.value) {
                    CritMode.Vanilla    -> injectVanilla(session)
                    CritMode.Fast       -> injectFast(session)      // ÖNERİLEN
                    CritMode.UltraFast  -> injectUltraFast(session)
                    CritMode.Packet     -> injectPacket(session)
                }
            }
            session.serverBound(pkt)
        }
    }

    // FAST: Sadece 3 packet, minimal gecikme
    private suspend fun injectFast(s: com.rubidiumclient.core.relay.RubidiumRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false)
        delay(10L)  // 25ms -> 10ms
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = false)
        delay(5L)   // Ekstra küçük delay
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = true)
    }

    // ULTRA FAST: 2 packet, max hız
    private suspend fun injectUltraFast(s: com.rubidiumclient.core.relay.RubidiumRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.42f, onGround = false)
        delay(5L)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = true)
    }

    private suspend fun injectVanilla(s: com.rubidiumclient.core.relay.RubidiumRelaySession) {
        // Opsiyonel: sadece 3 packet'e düşürüldü
        listOf(0.42f, 0.1f, 0.0f).forEach { dy ->
            PacketUtil.sendMoveAtSelf(s, dyOffset = dy, onGround = false)
            delay(8L)
        }
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = true)
    }

    private suspend fun injectPacket(s: com.rubidiumclient.core.relay.RubidiumRelaySession) {
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.001f, onGround = false)
        delay(2L)
        PacketUtil.sendMoveAtSelf(s, dyOffset = 0.0f, onGround = true)
    }
}
