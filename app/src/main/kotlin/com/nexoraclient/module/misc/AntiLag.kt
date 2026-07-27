package com.nexoraclient.module.misc

import com.nexoraclient.core.proxy.EntityTracker
import com.nexoraclient.events.PacketEvent
import com.nexoraclient.module.*
import com.nexoraclient.utils.MathUtil
import org.cloudburstmc.protocol.bedrock.data.LevelEventType
import org.cloudburstmc.protocol.bedrock.data.SoundEvent
import org.cloudburstmc.protocol.bedrock.packet.AddParticleEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.ExplodePacket
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket
import org.cloudburstmc.protocol.bedrock.packet.SpawnParticleEffectPacket

class AntiLag : BaseModule(
    name        = "AntiLag",
    category    = ModuleCategory.MISC,
    description = "Gereksiz partikul, ses ve animasyon paketlerini filtreleyerek client tarafi gecikmeyi azaltir"
) {
    private val blockParticles   = bool("Block Particles",   true)
    private val explosionFx      = bool("Explosion FX",      true)
    private val weatherFx        = bool("Weather Particles", true)
    private val ambientSounds    = bool("Ambient Sounds",    false)
    private val mobSounds        = bool("Mob Sounds",        false)
    private val entityAnimations = bool("Entity Animations", false)
    private val customParticles  = bool("Custom Particles",  true)
    private val distanceCulling  = bool("Distance Culling",  false)
    private val cullRange        = float("Cull Range",       48f, 8f, 256f)

    private val blockedLevelEvents = setOf(
        LevelEventType.PARTICLE_DESTROY_BLOCK,
        LevelEventType.PARTICLE_CRIT,
        LevelEventType.PARTICLE_EXPLOSION,
        LevelEventType.PARTICLE_EXPLOSION_HUGE,
        LevelEventType.PARTICLE_BLOCK_FORCE_FIELD,
        LevelEventType.PARTICLE_PUNCH_BLOCK,
        LevelEventType.PARTICLE_EAT
    )

    private val weatherLevelEvents = setOf(
        LevelEventType.PARTICLE_RAIN_SPLASH,
        LevelEventType.PARTICLE_SNOWBALL_POOF
    )

    private val ambientSoundEvents = setOf(
        SoundEvent.AMBIENT,
        SoundEvent.UNDEFINED
    )

    private val mobSoundEvents = setOf(
        SoundEvent.HURT,
        SoundEvent.DEATH,
        SoundEvent.STEP
    )

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled || !event.isServerToClient) return

        when (val packet = event.packet) {
            is LevelEventPacket        -> handleLevelEvent(event, packet)
            is LevelSoundEventPacket   -> handleLevelSound(event, packet)
            is AddParticleEffectPacket -> if (customParticles.value) event.cancel()
            is SpawnParticleEffectPacket -> if (customParticles.value) event.cancel()
            is ExplodePacket            -> if (explosionFx.value) event.cancel()
            is AnimatePacket            -> handleAnimate(event, packet)
            else -> {}
        }
    }

    private fun handleAnimate(event: PacketEvent, packet: AnimatePacket) {
        if (entityAnimations.value) {
            event.cancel()
            return
        }
        if (distanceCulling.value && isBeyondRange(packet.runtimeEntityId)) {
            event.cancel()
        }
    }

    private fun isBeyondRange(runtimeEntityId: Long): Boolean {
        val entity = EntityTracker.getById(runtimeEntityId) ?: return false
        return EntityTracker.distanceTo(entity) > cullRange.value
    }

    private fun handleLevelEvent(event: PacketEvent, packet: LevelEventPacket) {
        val type = packet.type
        if (blockParticles.value && type in blockedLevelEvents) {
            event.cancel()
            return
        }
        if (weatherFx.value && type in weatherLevelEvents) {
            event.cancel()
        }
    }

    private fun handleLevelSound(event: PacketEvent, packet: LevelSoundEventPacket) {
        val sound = packet.sound
        if (ambientSounds.value && sound in ambientSoundEvents) {
            event.cancel()
            return
        }
        if (mobSounds.value && sound in mobSoundEvents) {
            event.cancel()
            return
        }
        if (distanceCulling.value) {
            val pos = packet.position
            val dist = MathUtil.dist3(pos.x, pos.y, pos.z, EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            if (dist > cullRange.value) event.cancel()
        }
    }
}
