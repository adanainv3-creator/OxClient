package com.rubidiumclient.module.movement

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.*
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.math.cos
import kotlin.math.sin

// Her ortam (su/yer/hava) için AC'nin beklediği hareket paternine yakın kalacak
// şekilde ayrı motion profili uygular. Ability tabanlı native uçuş (CreativeFly)
// yerine MotionFly'daki gibi SetEntityMotionPacket enjeksiyonu kullanılıyor —
// gerçek istemci fiziği pozisyonu kendi hesapladığı için PlayerAuthInputPacket
// üzerinden sunucuya giden konum daima istemcinin kendi fiziğinden geliyor,
// TPAura'daki gibi ayrı bir MovePlayerPacket ile çakışma riski yok.
class BypassFly : BaseModule(
    name        = "BypassFly",
    category    = ModuleCategory.MOVEMENT,
    description = "Ortama göre (Su/Yer/Hava) ayrı motion profiliyle uçuş"
) {
    enum class FlyMode { Water, Ground, Air }

    private val mode              = enum ("Mode",                 FlyMode.Air)
    private val horizontalSpeed   = float("Horizontal Speed",     4.0f,  0.5f, 10.0f)
    private val airVerticalSpeed  = float("Air Vertical Speed",   1.6f,  0.2f, 5.0f)
    private val waterVerticalSpeed= float("Water Vertical Speed", 0.9f,  0.1f, 3.0f)
    private val waterBob          = float("Water Bob",            0.02f, 0.0f, 0.1f)
    private val airJitter         = float("Air Jitter",           0.05f, 0.0f, 0.3f)
    private val motionInterval    = int  ("Delay",                 55,   15,  150)
    private val grantAbilities    = bool ("Grant Fly Ability",     true)
    private val shortcut          = bool ("Shortcut",              false)

    @Volatile private var lastMotionMs = 0L
    @Volatile private var jitterState  = false
    @Volatile private var abilitiesOn  = false
    @Volatile private var lastSession  : RubidiumRelaySession? = null

    override fun onEnable() {
        super.onEnable()
        lastMotionMs = 0L
        jitterState  = false
        abilitiesOn  = false
    }

    override fun onDisable() {
        super.onDisable()
        lastSession?.let { applyAbilities(it, false) }
        abilitiesOn = false
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        val pkt = event.packet
        if (pkt !is PlayerAuthInputPacket) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return

        lastSession = event.session

        // KRİTİK: Ground modunda MAY_FLY/FLYING yetkisi ASLA verilmiyor.
        // Amaç sunucuya "uçuyor" durumu hiç bildirmemek — sadece yatay hız
        // artışı, dikey hiçbir müdahale yok. Su/Hava modunda gerçek istemci
        // WANT_UP/WANT_DOWN input'unu doğru yorumlasın diye yetki veriliyor.
        val wantAbilities = grantAbilities.value && mode.value != FlyMode.Ground
        if (wantAbilities != abilitiesOn) applyAbilities(event.session, wantAbilities)

        val now = System.currentTimeMillis()
        if (now - lastMotionMs < motionInterval.value) return
        lastMotionMs = now

        val yaw    = Math.toRadians(pkt.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        val inputX = pkt.motion.x
        val inputZ = pkt.motion.y
        val strafe  = inputX * horizontalSpeed.value
        val forward = inputZ * horizontalSpeed.value
        val motionX = strafe * cosYaw - forward * sinYaw
        val motionZ = forward * cosYaw + strafe * sinYaw

        val wantUp   = pkt.inputData.contains(PlayerAuthInputData.WANT_UP)
        val wantDown = pkt.inputData.contains(PlayerAuthInputData.WANT_DOWN)

        val motionY = when (mode.value) {
            FlyMode.Ground -> 0f
            FlyMode.Water  -> when {
                wantUp   ->  waterVerticalSpeed.value
                wantDown -> -waterVerticalSpeed.value
                else     -> flip() * waterBob.value
            }
            FlyMode.Air    -> when {
                wantUp   ->  airVerticalSpeed.value
                wantDown -> -airVerticalSpeed.value
                else     -> flip() * airJitter.value
            }
        }

        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = EntityTracker.selfRuntimeId
            motion = Vector3f.from(motionX, motionY, motionZ)
        }
        event.session.clientBound(motionPacket)
    }

    private fun flip(): Float {
        jitterState = !jitterState
        return if (jitterState) 1f else -1f
    }

    private fun applyAbilities(session: RubidiumRelaySession, enabled: Boolean) {
        val packet = UpdateAbilitiesPacket().apply {
            playerPermission  = if (enabled) PlayerPermission.OPERATOR else PlayerPermission.VISITOR
            commandPermission = if (enabled) CommandPermission.OWNER  else CommandPermission.ANY
            uniqueEntityId    = EntityTracker.selfUniqueId
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())

                val values = mutableListOf(
                    Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                    Ability.FLY_SPEED, Ability.WALK_SPEED, Ability.VERTICAL_FLY_SPEED
                )
                if (enabled) {
                    values += Ability.MAY_FLY
                    values += Ability.FLYING
                }
                abilityValues.addAll(values.toTypedArray())

                walkSpeed        = 0.1f
                flySpeed         = if (enabled) 0.3f else 0.05f
                verticalFlySpeed = if (enabled) 0.25f else 0.05f
            })
        }
        session.clientBound(packet)
        abilitiesOn = enabled
    }
}
