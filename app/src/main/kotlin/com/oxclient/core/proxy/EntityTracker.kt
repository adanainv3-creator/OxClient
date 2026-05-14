package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.utils.MathUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

object EntityTracker : PacketEventBus.PacketListener {

    private const val TAG = "EntityTracker"

    enum class EntityType {
        PLAYER, MONSTER, ANIMAL, PASSIVE, PROJECTILE, ITEM, CRYSTAL, UNKNOWN
    }

    enum class EntityFlag {
        SNEAKING, SPRINTING, SWIMMING, GLIDING, RIDING, INVISIBLE, ON_FIRE, SLEEPING
    }

    data class TrackedEntity(
        val runtimeId    : Long,
        val uniqueId     : Long,
        val identifier   : String,
        val type         : EntityType,
        var x            : Float,
        var y            : Float,
        var z            : Float,
        var yaw          : Float      = 0f,
        var pitch        : Float      = 0f,
        var headYaw      : Float      = 0f,
        var velX         : Float      = 0f,
        var velY         : Float      = 0f,
        var velZ         : Float      = 0f,
        var health       : Float      = 20f,
        var maxHealth    : Float      = 20f,
        var absorbHealth : Float      = 0f,
        var armorValue   : Float      = 0f,
        var movSpeed     : Float      = 0.1f,
        var attackDmg    : Float      = 2f,
        var isOnGround   : Boolean    = true,
        var isRiding     : Boolean    = false,
        var ridingId     : Long       = 0L,
        var flags        : Set<EntityFlag> = emptySet(),
        var name         : String     = "",
        var gameMode     : Int        = -1,
        var pingMs       : Int        = 0,
        val spawnTime    : Long       = System.currentTimeMillis(),
        var lastUpdateMs : Long       = System.currentTimeMillis(),
        var prevX        : Float      = 0f,
        var prevY        : Float      = 0f,
        var prevZ        : Float      = 0f,
        var hurtTime     : Int        = 0,
        var deathAnim    : Boolean    = false
    ) {
        val speedXZ: Float
            get() = MathUtil.dist2(x, z, prevX, prevZ)

        val isMoving: Boolean
            get() = speedXZ > 0.01f

        val isCrystal: Boolean
            get() = type == EntityType.CRYSTAL || identifier.contains("crystal", ignoreCase = true)

        val isPlayer: Boolean
            get() = type == EntityType.PLAYER

        val isHostile: Boolean
            get() = type == EntityType.MONSTER

        val isSneaking: Boolean get() = EntityFlag.SNEAKING  in flags
        val isSprinting: Boolean get() = EntityFlag.SPRINTING in flags
        val isInvisible: Boolean get() = EntityFlag.INVISIBLE in flags
        val isSleeping: Boolean  get() = EntityFlag.SLEEPING  in flags

        val healthPercent: Float
            get() = if (maxHealth > 0f) health / maxHealth else 0f

        fun interpolatedPosition(partialTick: Float): Triple<Float, Float, Float> {
            val ix = prevX + (x - prevX) * partialTick
            val iy = prevY + (y - prevY) * partialTick
            val iz = prevZ + (z - prevZ) * partialTick
            return Triple(ix, iy, iz)
        }

        fun predictedPosition(ticksAhead: Float): Triple<Float, Float, Float> {
            return Triple(
                x + velX * ticksAhead,
                y + velY * ticksAhead,
                z + velZ * ticksAhead
            )
        }
    }

    private val entities = ConcurrentHashMap<Long, TrackedEntity>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    private val playerNames = ConcurrentHashMap<Long, String>()

    @Volatile var selfRuntimeId : Long  = 0L
    @Volatile var selfUniqueId  : Long  = 0L
    @Volatile var selfX         : Float = 0f
    @Volatile var selfY         : Float = 0f
    @Volatile var selfZ         : Float = 0f
    @Volatile var selfYaw       : Float = 0f
    @Volatile var selfPitch     : Float = 0f
    @Volatile var selfHealth    : Float = 20f
    @Volatile var selfMaxHealth : Float = 20f
    @Volatile var selfAbsorb    : Float = 0f
    @Volatile var selfArmor     : Float = 0f
    @Volatile var selfHunger    : Float = 20f
    @Volatile var selfSaturation: Float = 5f
    @Volatile var selfOnGround  : Boolean = true
    @Volatile var selfGameMode  : Int   = 0
    @Volatile var selfDimension : Int   = 0
    @Volatile var selfSpeedXZ  : Float = 0f

    private var prevSelfX = 0f
    private var prevSelfY = 0f
    private var prevSelfZ = 0f

    private val _entityCountFlow = MutableStateFlow(0)
    val entityCountFlow: StateFlow<Int> = _entityCountFlow.asStateFlow()

    private val _selfHealthFlow = MutableStateFlow(20f)
    val selfHealthFlow: StateFlow<Float> = _selfHealthFlow.asStateFlow()

    private val _entityUpdateFlow = MutableStateFlow(0L)
    val entityUpdateFlow: StateFlow<Long> = _entityUpdateFlow.asStateFlow()

    fun init() {
        PacketEventBus.register(this)
        Log.i(TAG, "EntityTracker başlatıldı")
    }

    fun reset() {
        entities.clear()
        uniqueToRuntime.clear()
        playerNames.clear()
        selfRuntimeId  = 0L
        selfUniqueId   = 0L
        selfX          = 0f; selfY = 0f; selfZ = 0f
        selfYaw        = 0f; selfPitch = 0f
        selfHealth     = 20f; selfMaxHealth = 20f
        selfAbsorb     = 0f; selfArmor = 0f
        selfHunger     = 20f; selfSaturation = 5f
        selfOnGround   = true
        selfGameMode   = 0
        selfDimension  = 0
        selfSpeedXZ    = 0f
        prevSelfX      = 0f; prevSelfY = 0f; prevSelfZ = 0f
        _entityCountFlow.value = 0
        _selfHealthFlow.value  = 20f
        Log.i(TAG, "EntityTracker sıfırlandı")
    }

    override fun onPacket(event: PacketEvent) {
        when (val pkt = event.packet) {
            is StartGamePacket        -> handleStartGame(pkt)
            is AddEntityPacket        -> handleAddEntity(pkt)
            is AddPlayerPacket        -> handleAddPlayer(pkt)
            is RemoveEntityPacket     -> handleRemoveEntity(pkt)
            is MoveEntityAbsolutePacket -> handleMoveAbsolute(pkt)
            is MoveEntityDeltaPacket  -> handleMoveDelta(pkt)
            is MovePlayerPacket       -> handleMovePlayer(pkt, event.direction)
            is SetEntityDataPacket    -> handleEntityData(pkt)
            is SetEntityMotionPacket  -> handleEntityMotion(pkt)
            is UpdateAttributesPacket -> handleAttributes(pkt)
            is PlayerListPacket       -> handlePlayerList(pkt)
            is EntityEventPacket      -> handleEntityEvent(pkt)
            is SetPlayerGameTypePacket-> handleGameType(pkt)
            is RespawnPacket          -> handleRespawn(pkt)
            is ChangeDimensionPacket  -> handleDimension(pkt)
            is SetEntityLinkPacket    -> handleEntityLink(pkt)
            is PlayerAuthInputPacket  -> handleAuthInput(pkt, event.direction)
            else -> {}
        }
    }

    private fun handleStartGame(pkt: StartGamePacket) {
        selfRuntimeId = pkt.runtimeEntityId
        selfUniqueId  = pkt.uniqueEntityId
        selfX         = pkt.playerPosition.x
        selfY         = pkt.playerPosition.y
        selfZ         = pkt.playerPosition.z
        selfYaw       = pkt.rotation.y
        selfPitch     = pkt.rotation.x
        selfGameMode  = pkt.playerGamemode.ordinal
        Log.i(TAG, "StartGame → runtimeId=$selfRuntimeId uniqueId=$selfUniqueId pos=(${selfX},${selfY},${selfZ})")
    }

    private fun handleAddEntity(pkt: AddEntityPacket) {
        if (pkt.runtimeEntityId == selfRuntimeId) return
        val type = resolveEntityType(pkt.identifier)
        val entity = TrackedEntity(
            runtimeId  = pkt.runtimeEntityId,
            uniqueId   = pkt.uniqueEntityId,
            identifier = pkt.identifier,
            type       = type,
            x          = pkt.position.x,
            y          = pkt.position.y,
            z          = pkt.position.z,
            yaw        = pkt.rotation.y,
            pitch      = pkt.rotation.x,
            headYaw    = pkt.rotation.z,
            velX       = pkt.motion.x,
            velY       = pkt.motion.y,
            velZ       = pkt.motion.z,
        )
        applyEntityMetadata(entity, pkt.metadata)
        entities[pkt.runtimeEntityId] = entity
        uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
        notifyUpdate()
    }

    private fun handleAddPlayer(pkt: AddPlayerPacket) {
        if (pkt.runtimeEntityId == selfRuntimeId) return
        val entity = TrackedEntity(
            runtimeId  = pkt.runtimeEntityId,
            uniqueId   = pkt.uniqueEntityId,
            identifier = "minecraft:player",
            type       = EntityType.PLAYER,
            x          = pkt.position.x,
            y          = pkt.position.y,
            z          = pkt.position.z,
            yaw        = pkt.rotation.y,
            pitch      = pkt.rotation.x,
            headYaw    = pkt.rotation.z,
            velX       = pkt.motion.x,
            velY       = pkt.motion.y,
            velZ       = pkt.motion.z,
            name       = pkt.username ?: "",
        )
        applyEntityMetadata(entity, pkt.metadata)
        entities[pkt.runtimeEntityId] = entity
        uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
        notifyUpdate()
    }

    private fun handleRemoveEntity(pkt: RemoveEntityPacket) {
        val rid = uniqueToRuntime.remove(pkt.uniquEntityId)
        if (rid != null) {
            entities.remove(rid)
            notifyUpdate()
        }
    }

    private fun handleMoveAbsolute(pkt: MoveEntityAbsolutePacket) {
        val e = entities[pkt.runtimeEntityId] ?: return
        e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z
        e.x     = pkt.position.x
        e.y     = pkt.position.y
        e.z     = pkt.position.z
        e.yaw   = pkt.rotation.y
        e.pitch = pkt.rotation.x
        e.headYaw    = pkt.rotation.z
        e.isOnGround = pkt.isOnGround
        e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleMoveDelta(pkt: MoveEntityDeltaPacket) {
        val e = entities[pkt.runtimeEntityId] ?: return
        e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z
        if (pkt.isHasX) e.x += pkt.x
        if (pkt.isHasY) e.y += pkt.y
        if (pkt.isHasZ) e.z += pkt.z
        if (pkt.isHasYaw)     e.yaw     = pkt.yaw
        if (pkt.isHasPitch)   e.pitch   = pkt.pitch
        if (pkt.isHasHeadYaw) e.headYaw = pkt.headYaw
        e.isOnGround = pkt.isOnGround
        e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleMovePlayer(pkt: MovePlayerPacket, direction: PacketEvent.Direction) {
        if (pkt.runtimeEntityId == selfRuntimeId) {
            if (direction == PacketEvent.Direction.CLIENT_TO_SERVER) {
                prevSelfX = selfX; prevSelfY = selfY; prevSelfZ = selfZ
                selfX     = pkt.position.x
                selfY     = pkt.position.y
                selfZ     = pkt.position.z
                selfYaw   = pkt.rotation.y
                selfPitch = pkt.rotation.x
                selfOnGround = pkt.isOnGround
                selfSpeedXZ  = MathUtil.dist2(selfX, selfZ, prevSelfX, prevSelfZ)
            }
            return
        }
        val e = entities[pkt.runtimeEntityId] ?: return
        e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z
        e.x     = pkt.position.x
        e.y     = pkt.position.y
        e.z     = pkt.position.z
        e.yaw   = pkt.rotation.y
        e.pitch = pkt.rotation.x
        e.isOnGround = pkt.isOnGround
        e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleAuthInput(pkt: PlayerAuthInputPacket, direction: PacketEvent.Direction) {
        if (direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        prevSelfX = selfX; prevSelfY = selfY; prevSelfZ = selfZ
        selfX     = pkt.position.x
        selfY     = pkt.position.y
        selfZ     = pkt.position.z
        selfYaw   = pkt.rotation.y
        selfPitch = pkt.rotation.x
        selfSpeedXZ = MathUtil.dist2(selfX, selfZ, prevSelfX, prevSelfZ)
    }

    private fun handleEntityMotion(pkt: SetEntityMotionPacket) {
        if (pkt.runtimeEntityId == selfRuntimeId) return
        val e = entities[pkt.runtimeEntityId] ?: return
        e.velX = pkt.motion.x
        e.velY = pkt.motion.y
        e.velZ = pkt.motion.z
    }

    private fun handleEntityData(pkt: SetEntityDataPacket) {
        val e = entities[pkt.runtimeEntityId] ?: return
        applyEntityMetadata(e, pkt.metadata)
        e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleAttributes(pkt: UpdateAttributesPacket) {
        val isSelf = pkt.runtimeEntityId == selfRuntimeId

        pkt.attributes.forEach { attr ->
            when (attr.name) {
                "minecraft:health" -> {
                    if (isSelf) {
                        selfHealth    = attr.value
                        selfMaxHealth = attr.maximum
                        _selfHealthFlow.value = selfHealth
                    } else {
                        entities[pkt.runtimeEntityId]?.let {
                            it.health    = attr.value
                            it.maxHealth = attr.maximum
                        }
                    }
                }
                "minecraft:absorption" -> {
                    if (isSelf) selfAbsorb = attr.value
                    else entities[pkt.runtimeEntityId]?.absorbHealth = attr.value
                }
                "minecraft:armor" -> {
                    if (isSelf) selfArmor = attr.value
                    else entities[pkt.runtimeEntityId]?.armorValue = attr.value
                }
                "minecraft:hunger" -> {
                    if (isSelf) selfHunger = attr.value
                }
                "minecraft:saturation" -> {
                    if (isSelf) selfSaturation = attr.value
                }
                "minecraft:movement" -> {
                    entities[pkt.runtimeEntityId]?.movSpeed = attr.value
                }
                "minecraft:attack_damage" -> {
                    entities[pkt.runtimeEntityId]?.attackDmg = attr.value
                }
            }
        }
    }

    private fun handlePlayerList(pkt: PlayerListPacket) {
        if (pkt.action == PlayerListPacket.Action.ADD) {
            pkt.entries.forEach { entry ->
                val rid = uniqueToRuntime[entry.entityId]
                val name = entry.name ?: return@forEach
                playerNames[entry.entityId] = name
                if (rid != null) {
                    entities[rid]?.name = name
                    entities[rid]?.pingMs = entry.latency
                }
            }
        } else {
            pkt.entries.forEach { entry ->
                playerNames.remove(entry.entityId)
            }
        }
    }

    private fun handleEntityEvent(pkt: EntityEventPacket) {
        val e = entities[pkt.runtimeEntityId] ?: return
        when (pkt.type) {
            2    -> { e.hurtTime  = 10 }
            3    -> { e.deathAnim = true }
            57   -> {}
            else -> {}
        }
    }

    private fun handleGameType(pkt: SetPlayerGameTypePacket) {
        selfGameMode = pkt.gamemode.ordinal
    }

    private fun handleRespawn(pkt: RespawnPacket) {
        if (pkt.state == RespawnPacket.State.SERVER_SEARCHING) {
            selfX = pkt.position.x
            selfY = pkt.position.y
            selfZ = pkt.position.z
        }
    }

    private fun handleDimension(pkt: ChangeDimensionPacket) {
        selfDimension = pkt.dimension
        selfX = pkt.position.x
        selfY = pkt.position.y
        selfZ = pkt.position.z
        val oldCount = entities.size
        entities.clear()
        uniqueToRuntime.clear()
        Log.i(TAG, "Boyut değişti → dim=$selfDimension, $oldCount entity temizlendi")
        notifyUpdate()
    }

    private fun handleEntityLink(pkt: SetEntityLinkPacket) {
        val riderRid  = uniqueToRuntime[pkt.action.riderId]  ?: return
        val riddenRid = uniqueToRuntime[pkt.action.riddenId] ?: return
        val rider = entities[riderRid] ?: return
        when (pkt.action.type) {
            org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.RIDE,
            org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.PASSENGER -> {
                rider.isRiding = true
                rider.ridingId = riddenRid
            }
            org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.REMOVE -> {
                rider.isRiding = false
                rider.ridingId = 0L
            }
            else -> {}
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyEntityMetadata(
        entity: TrackedEntity,
        metadata: Map<*, *>?
    ) {
        if (metadata == null) return
        try {
            val flags = mutableSetOf<EntityFlag>()
            metadata.forEach { (key, value) ->
                when (key) {
                    0    -> {
                        val bits = (value as? Long) ?: return@forEach
                        if (bits and (1L shl 1)  != 0L) flags.add(EntityFlag.ON_FIRE)
                        if (bits and (1L shl 2)  != 0L) flags.add(EntityFlag.SNEAKING)
                        if (bits and (1L shl 3)  != 0L) flags.add(EntityFlag.RIDING)
                        if (bits and (1L shl 4)  != 0L) flags.add(EntityFlag.SPRINTING)
                        if (bits and (1L shl 6)  != 0L) flags.add(EntityFlag.INVISIBLE)
                        if (bits and (1L shl 26) != 0L) flags.add(EntityFlag.SLEEPING)
                        if (bits and (1L shl 39) != 0L) flags.add(EntityFlag.SWIMMING)
                        if (bits and (1L shl 38) != 0L) flags.add(EntityFlag.GLIDING)
                    }
                    2    -> entity.name = (value as? String) ?: entity.name
                    7    -> entity.health = (value as? Float) ?: entity.health
                }
            }
            if (flags.isNotEmpty()) entity.flags = flags
        } catch (e: Exception) {
            Log.v(TAG, "Metadata parse hatası (${entity.identifier}): ${e.message}")
        }
    }

    private fun resolveEntityType(identifier: String): EntityType {
        val id = identifier.lowercase().removePrefix("minecraft:")
        return when {
            id == "player"                                                   -> EntityType.PLAYER
            id.contains("crystal")                                           -> EntityType.CRYSTAL
            id in MONSTER_IDS                                                -> EntityType.MONSTER
            id in ANIMAL_IDS                                                 -> EntityType.ANIMAL
            id in PASSIVE_IDS                                                -> EntityType.PASSIVE
            id in PROJECTILE_IDS                                             -> EntityType.PROJECTILE
            id == "item"                                                     -> EntityType.ITEM
            else                                                             -> EntityType.UNKNOWN
        }
    }

    private val MONSTER_IDS = setOf(
        "zombie", "skeleton", "creeper", "spider", "cave_spider", "enderman",
        "witch", "phantom", "drowned", "husk", "stray", "wither_skeleton",
        "blaze", "ghast", "magma_cube", "slime", "guardian", "elder_guardian",
        "shulker", "vindicator", "evoker", "vex", "pillager", "ravager",
        "hoglin", "piglin", "zoglin", "piglin_brute", "warden",
        "zombie_villager", "zombie_pigman", "zombified_piglin",
        "endermite", "silverfish", "elder_guardian_ghost"
    )

    private val ANIMAL_IDS = setOf(
        "cow", "pig", "sheep", "chicken", "horse", "donkey", "mule",
        "rabbit", "wolf", "cat", "ocelot", "panda", "polar_bear",
        "fox", "bee", "turtle", "salmon", "cod", "pufferfish",
        "tropical_fish", "dolphin", "squid", "glow_squid", "axolotl",
        "goat", "frog", "tadpole", "camel", "sniffer", "armadillo"
    )

    private val PASSIVE_IDS = setOf(
        "villager", "wandering_trader", "iron_golem", "snow_golem",
        "strider", "bat", "parrot", "mooshroom", "llama", "trader_llama",
        "allay", "chest_minecart", "minecart", "boat", "chest_boat"
    )

    private val PROJECTILE_IDS = setOf(
        "arrow", "spectral_arrow", "thrown_trident", "snowball",
        "egg", "ender_pearl", "eye_of_ender_signal", "fireball",
        "small_fireball", "fishing_hook", "llama_spit",
        "shulker_bullet", "dragon_fireball", "wither_skull",
        "fireworks_rocket", "wind_charge"
    )

    fun getAll(): Collection<TrackedEntity> = entities.values

    fun getById(runtimeId: Long): TrackedEntity? = entities[runtimeId]

    fun getByUniqueId(uniqueId: Long): TrackedEntity? {
        val rid = uniqueToRuntime[uniqueId] ?: return null
        return entities[rid]
    }

    fun getByName(name: String): TrackedEntity? =
        entities.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun getEntitiesInRange(range: Float): List<TrackedEntity> {
        val r2 = range * range
        return entities.values.filter { e ->
            MathUtil.dist3sq(e.x, e.y, e.z, selfX, selfY, selfZ) <= r2
        }
    }

    fun getEntitiesInRangeOfType(range: Float, vararg types: EntityType): List<TrackedEntity> {
        val r2 = range * range
        return entities.values.filter { e ->
            e.type in types &&
            MathUtil.dist3sq(e.x, e.y, e.z, selfX, selfY, selfZ) <= r2
        }
    }

    fun getPlayers(range: Float = Float.MAX_VALUE): List<TrackedEntity> =
        getEntitiesInRange(range).filter { it.isPlayer }

    fun getHostiles(range: Float = Float.MAX_VALUE): List<TrackedEntity> =
        getEntitiesInRange(range).filter { it.isHostile }

    fun getCrystals(range: Float = Float.MAX_VALUE): List<TrackedEntity> =
        getEntitiesInRange(range).filter { it.isCrystal }

    fun getNearestEntity(range: Float, vararg types: EntityType): TrackedEntity? =
        getEntitiesInRangeOfType(range, *types).minByOrNull { distanceTo(it) }

    fun getNearestPlayer(range: Float): TrackedEntity? =
        getPlayers(range).minByOrNull { distanceTo(it) }

    fun getNearestHostile(range: Float): TrackedEntity? =
        getHostiles(range).minByOrNull { distanceTo(it) }

    fun distanceTo(e: TrackedEntity): Float =
        MathUtil.dist3(e.x, e.y, e.z, selfX, selfY, selfZ)

    fun distanceTo(x: Float, y: Float, z: Float): Float =
        MathUtil.dist3(x, y, z, selfX, selfY, selfZ)

    fun distanceTo2D(e: TrackedEntity): Float =
        MathUtil.dist2(e.x, e.z, selfX, selfZ)

    fun angleToEntity(e: TrackedEntity): Float {
        val dx = e.x - selfX
        val dz = e.z - selfZ
        val yaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        val diff = abs(((selfYaw - yaw) % 360f + 540f) % 360f - 180f)
        return diff
    }

    fun isInFov(e: TrackedEntity, fovDegrees: Float): Boolean {
        if (fovDegrees >= 360f) return true
        return angleToEntity(e) <= fovDegrees / 2f
    }

    fun isBehindWall(e: TrackedEntity): Boolean {
        return false
    }

    fun getHealthPercent(): Float =
        if (selfMaxHealth > 0f) selfHealth / selfMaxHealth else 0f

    fun isLowHealth(threshold: Float = 6f): Boolean = selfHealth <= threshold

    fun isCriticalHealth(threshold: Float = 3f): Boolean = selfHealth <= threshold

    fun count(): Int = entities.size

    fun playerCount(): Int = entities.values.count { it.isPlayer }

    fun hostileCount(): Int = entities.values.count { it.isHostile }

    fun getEntitiesSnapshot(): List<TrackedEntity> = entities.values.toList()

    fun removeStale(maxAgeMs: Long = 30_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val stale = entities.entries.filter { it.value.lastUpdateMs < cutoff }
        stale.forEach { (rid, e) ->
            entities.remove(rid)
            uniqueToRuntime.remove(e.uniqueId)
        }
        if (stale.isNotEmpty()) {
            Log.d(TAG, "${stale.size} stale entity temizlendi")
            notifyUpdate()
        }
    }

    fun debugDump(): String = buildString {
        appendLine("=== EntityTracker Debug ===")
        appendLine("Self: id=$selfRuntimeId pos=(${selfX},${selfY},${selfZ}) hp=$selfHealth/$selfMaxHealth")
        appendLine("Entities: ${entities.size} total")
        entities.values.sortedBy { distanceTo(it) }.take(20).forEach { e ->
            appendLine("  [${e.type.name}] ${e.name.ifEmpty { e.identifier }} " +
                       "id=${e.runtimeId} pos=(${e.x.toInt()},${e.y.toInt()},${e.z.toInt()}) " +
                       "hp=${e.health}/${e.maxHealth} dist=${"%.1f".format(distanceTo(e))}")
        }
    }

    private fun notifyUpdate() {
        _entityCountFlow.value = entities.size
        _entityUpdateFlow.value = System.currentTimeMillis()
    }
}
