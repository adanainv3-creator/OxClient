package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import com.rubidiumclient.utils.PacketUtil
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket

/**
 * FreeCamera — bu bir relay/MITM client olduğu için gerçek Minecraft
 * uygulamasının render kamerasını doğrudan kontrol edemeyiz (bu, oyun
 * process'ine inject edilen bir internal cheat'in yapabileceği bir şey,
 * paket seviyesinde MITM'in değil). Bunun yerine standart "relay freecam"
 * paterni kullanılıyor:
 *
 * 1) İstemciye CLIENT-BOUND UpdateAbilitiesPacket ile NOCLIP + MAY_FLY +
 *    FLYING veriliyor (sunucuya HİÇ bildirilmiyor). Gerçek istemci artık
 *    kendi motorunda serbestçe uçup her yöne bakabiliyor.
 * 2) Etkinleştirildiği andaki GERÇEK pozisyon/rotasyon donduruluyor.
 *    Bundan sonra gelen her CLIENT_TO_SERVER PlayerAuthInputPacket iptal
 *    edilip sunucuya HİÇ gönderilmiyor — yani serbest uçuş sunucuya hiç
 *    yansımıyor. Bunun yerine belirli aralıklarla (Heartbeat) dondurulmuş
 *    pozisyon açık bir MovePlayerPacket ile tekrar gönderiliyor, hem
 *    timeout'u önlemek hem de diğer oyunculara "yerinde duruyor" görüntüsü
 *    vermek için.
 * 3) Kapatılınca abilities normale dönüyor, paket akışı normal forward'a geri döner.
 *
 * NOT: Ability.NO_CLIP ismi referans alınan başka bir cheat client
 * (mucheng/MuCute) kaynağından teyit edildi — kullandığın CloudburstMC
 * protocol sürümünde de aynı isimle mevcut olmalı.
 */
class FreeCamera : BaseModule(
    name        = "FreeCamera",
    category    = ModuleCategory.MOVEMENT,
    description = "Lokal NOCLIP+uçuş ile serbest kamera; sunucuya dondurulmuş pozisyon gönderilir"
) {
    private val heartbeatMs = int("Heartbeat", 200, 50, 1000)
    private val flySpeed    = float("Fly Speed", 0.6f, 0.1f, 2.0f)

    @Volatile private var active = false
    @Volatile private var frozenX = 0f
    @Volatile private var frozenY = 0f
    @Volatile private var frozenZ = 0f
    @Volatile private var frozenYaw = 0f
    @Volatile private var frozenPitch = 0f
    @Volatile private var lastHeartbeatMs = 0L
    @Volatile private var lastSession: RubidiumRelaySession? = null

    override fun onEnable() {
        super.onEnable()
        active = false
    }

    override fun onDisable() {
        // FIX: eskiden sadece abilities geri alınıyordu — gerçek istemci
        // freecam sırasında bloklardan geçip uzaklaşmış olabilir, ama sunucu
        // hâlâ donmuş pozisyonda olduğumuzu sanıyor. Abilities'i kaldırmadan
        // ÖNCE istemciyi CLIENT-BOUND bir TELEPORT MovePlayerPacket ile
        // donmuş pozisyona geri ışınlıyoruz, böylece bir sonraki gerçek
        // PlayerAuthInputPacket zaten sunucunun bildiği yerden başlıyor —
        // ani sıçrama/desync ya da duvar içinde sıkışma olmuyor.
        if (active) {
            lastSession?.let { session ->
                session.clientBound(MovePlayerPacket().apply {
                    runtimeEntityId       = EntityTracker.selfRuntimeId
                    position              = org.cloudburstmc.math.vector.Vector3f.from(frozenX, frozenY, frozenZ)
                    rotation              = org.cloudburstmc.math.vector.Vector3f.from(frozenPitch, frozenYaw, frozenYaw)
                    mode                  = MovePlayerPacket.Mode.TELEPORT
                    isOnGround            = true
                    ridingRuntimeEntityId = 0L
                })
            }
        }
        lastSession?.let { restoreAbilities(it) }
        active = false
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return

        // FIX: sunucudan gelen UpdateAbilitiesPacket, bizim client-bound
        // spoof ettiğimiz NOCLIP/FLYING durumunu ezip freecam'i anında
        // bozabilir — aktifken bunu engelliyoruz (NoClipModule'daki aynı fix).
        if (active && event.packet is UpdateAbilitiesPacket) {
            event.cancel()
            return
        }

        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        lastSession = event.session

        if (!active) {
            frozenX = EntityTracker.selfX
            frozenY = EntityTracker.selfY
            frozenZ = EntityTracker.selfZ
            frozenYaw = EntityTracker.selfYaw
            frozenPitch = EntityTracker.selfPitch
            grantAbilities(event.session)
            active = true
            lastHeartbeatMs = System.currentTimeMillis()
        }

        // Gerçek (freecam) hareketi sunucuya asla gitmiyor.
        event.cancel()

        val now = System.currentTimeMillis()
        if (now - lastHeartbeatMs >= heartbeatMs.value) {
            lastHeartbeatMs = now
            PacketUtil.sendMove(
                event.session,
                frozenX, frozenY, frozenZ,
                frozenYaw, frozenPitch,
                onGround = true
            )
        }
    }

    private fun grantAbilities(session: RubidiumRelaySession) {
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = PlayerPermission.OPERATOR
            commandPermission = CommandPermission.OWNER
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())
                abilityValues.addAll(
                    arrayOf(
                        Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                        Ability.OPEN_CONTAINERS, Ability.FLY_SPEED, Ability.WALK_SPEED,
                        Ability.MAY_FLY, Ability.FLYING, Ability.NO_CLIP
                    )
                )
                walkSpeed = 0.1f
                flySpeed  = this@FreeCamera.flySpeed.value
                verticalFlySpeed = this@FreeCamera.flySpeed.value
            })
        }
        session.clientBound(packet)
    }

    private fun restoreAbilities(session: RubidiumRelaySession) {
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = PlayerPermission.VISITOR
            commandPermission = CommandPermission.ANY
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())
                abilityValues.addAll(
                    arrayOf(
                        Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                        Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                        Ability.FLY_SPEED, Ability.WALK_SPEED
                    )
                )
                walkSpeed = 0.1f
                flySpeed  = 0.05f
                verticalFlySpeed = 0.05f
            })
        }
        session.clientBound(packet)
    }
}
