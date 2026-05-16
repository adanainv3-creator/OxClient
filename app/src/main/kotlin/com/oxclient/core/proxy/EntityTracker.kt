package com.oxclient.core.proxy

import android.util.Log
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.utils.MathUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

object EntityTracker : PacketEventBus.PacketListener {

    private const val TAG = "EntityTracker"

    enum class EntityType { PLAYER, MONSTER, ANIMAL, PASSIVE, PROJECTILE, ITEM, CRYSTAL, UNKNOWN }

    data class TrackedEntity(
        val runtimeId    : Long,
        val uniqueId     : Long,
        val identifier   : String,
        val type         : EntityType,
        var x            : Float,
        var y            : Float,
        var z            : Float,
        var yaw          : Float   = 0f,
        var pitch        : Float   = 0f,
        var headYaw      : Float   = 0f,
        var velX         : Float   = 0f,
        var velY         : Float   = 0f,
        var velZ         : Float   = 0f,
        var health       : Float   = 20f,
        var maxHealth    : Float   = 20f,
        var isOnGround   : Boolean = true,
        var isRiding     : Boolean = false,
        var ridingId     : Long    = 0L,
        var name         : String  = "",
        var lastUpdateMs : Long    = System.currentTimeMillis(),
        var prevX        : Float   = 0f,
        var prevY        : Float   = 0f,
        var prevZ        : Float   = 0f,
    ) {
        val isCrystal: Boolean get() = type == EntityType.CRYSTAL || identifier.contains("crystal", ignoreCase = true)
        val isPlayer : Boolean get() = type == EntityType.PLAYER
        val isHostile: Boolean get() = type == EntityType.MONSTER
    }

    private val entities        = ConcurrentHashMap<Long, TrackedEntity>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()

    @Volatile var selfRuntimeId : Long    = 0L
    @Volatile var selfUniqueId  : Long    = 0L
    @Volatile var selfX         : Float   = 0f
    @Volatile var selfY         : Float   = 0f
    @Volatile var selfZ         : Float   = 0f
    @Volatile var selfYaw       : Float   = 0f
    @Volatile var selfPitch     : Float   = 0f
    @Volatile var selfHealth    : Float   = 20f
    @Volatile var selfMaxHealth : Float   = 20f
    @Volatile var selfOnGround  : Boolean = true
    @Volatile var selfGameMode  : Int     = 0

    private var prevSelfX = 0f
    private var prevSelfZ = 0f

    private val _entityCountFlow  = MutableStateFlow(0)
    val entityCountFlow : StateFlow<Int>   = _entityCountFlow.asStateFlow()
    private val _selfHealthFlow   = MutableStateFlow(20f)
    val selfHealthFlow  : StateFlow<Float> = _selfHealthFlow.asStateFlow()

    fun init()  { PacketEventBus.register(this); Log.i(TAG, "EntityTracker başlatıldı") }

    fun reset() {
        entities.clear(); uniqueToRuntime.clear()
        selfRuntimeId = 0L; selfUniqueId = 0L
        selfX = 0f; selfY = 0f; selfZ = 0f
        selfYaw = 0f; selfPitch = 0f
        selfHealth = 20f; selfMaxHealth = 20f
        selfOnGround = true; selfGameMode = 0
        _entityCountFlow.value = 0; _selfHealthFlow.value = 20f
        Log.i(TAG, "EntityTracker sıfırlandı")
    }

    override fun onPacket(event: PacketEvent) {
        when (val p = event.packet) {
            is StartGamePacket          -> handleStartGame(p)
            is AddEntityPacket          -> handleAddEntity(p)
            is AddPlayerPacket          -> handleAddPlayer(p)
            is RemoveEntityPacket       -> handleRemoveEntity(p)
            is MoveEntityAbsolutePacket -> handleMoveAbsolute(p)
            is MoveEntityDeltaPacket    -> handleMoveDelta(p)
            is MovePlayerPacket         -> handleMovePlayer(p, event.direction)
            is PlayerAuthInputPacket    -> handleAuthInput(p, event.direction)
            is SetEntityDataPacket      -> handleEntityData(p)
            is SetEntityMotionPacket    -> handleEntityMotion(p)
            is UpdateAttributesPacket   -> handleAttributes(p)
            is PlayerListPacket         -> handlePlayerList(p)
            is SetPlayerGameTypePacket  -> selfGameMode = p.gamemode.ordinal
            is ChangeDimensionPacket    -> handleDimension(p)
            is SetEntityLinkPacket      -> handleEntityLink(p)
            is RespawnPacket            -> {
                if (p.state == RespawnPacket.State.SERVER_SEARCHING) {
                    selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
                }
            }
            else -> {}
        }
    }

    private fun handleStartGame(p: StartGamePacket) {
        selfRuntimeId = p.runtimeEntityId; selfUniqueId = p.uniqueEntityId
        selfX = p.playerPosition.x; selfY = p.playerPosition.y; selfZ = p.playerPosition.z
        selfYaw = p.rotation.y; selfPitch = p.rotation.x
        // GameType.ordinal — Kotlin property, Java int
        selfGameMode = p.playerGameType.ordinal
        Log.i(TAG, "StartGame rid=$selfRuntimeId pos=($selfX,$selfY,$selfZ)")
    }

    private fun handleAddEntity(p: AddEntityPacket) {
        if (p.runtimeEntityId == selfRuntimeId) return
        val e = TrackedEntity(
            runtimeId  = p.runtimeEntityId,
            uniqueId   = p.uniqueEntityId,
            identifier = p.identifier,
            type       = resolveType(p.identifier),
            x          = p.position.x,
            y          = p.position.y,
            z          = p.position.z,
            yaw        = p.rotation.y,
            pitch      = p.rotation.x,
            headYaw    = p.rotation.z,
            velX       = p.motion.x,
            velY       = p.motion.y,
            velZ       = p.motion.z,
        )
        entities[p.runtimeEntityId] = e
        uniqueToRuntime[p.uniqueEntityId] = p.runtimeEntityId
        notifyUpdate()
    }

    private fun handleAddPlayer(p: AddPlayerPacket) {
        if (p.runtimeEntityId == selfRuntimeId) return
        val e = TrackedEntity(
            runtimeId  = p.runtimeEntityId,
            uniqueId   = p.uniqueEntityId,
            identifier = "minecraft:player",
            type       = EntityType.PLAYER,
            x          = p.position.x,
            y          = p.position.y,
            z          = p.position.z,
            yaw        = p.rotation.y,
            pitch      = p.rotation.x,
            headYaw    = p.rotation.z,
            velX       = p.motion.x,
            velY       = p.motion.y,
            velZ       = p.motion.z,
            name       = p.username ?: "",
        )
        entities[p.runtimeEntityId] = e
        uniqueToRuntime[p.uniqueEntityId] = p.runtimeEntityId
        notifyUpdate()
    }

    private fun handleRemoveEntity(p: RemoveEntityPacket) {
        val rid = uniqueToRuntime.remove(p.uniqueEntityId)
        if (rid != null) { entities.remove(rid); notifyUpdate() }
    }

    private fun handleMoveAbsolute(p: MoveEntityAbsolutePacket) {
        val e = entities[p.runtimeEntityId] ?: return
        e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z
        e.x = p.position.x; e.y = p.position.y; e.z = p.position.z
        e.yaw = p.rotation.y; e.pitch = p.rotation.x; e.headYaw = p.rotation.z
        e.isOnGround = p.isOnGround
        e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleMoveDelta(p: MoveEntityDeltaPacket) {
        val e = entities[p.runtimeEntityId] ?: return
        e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z

        // 3.0 API: p.flags is Set<MoveEntityDeltaPacket.Flag>
        val flags = p.flags
        if (MoveEntityDeltaPacket.Flag.HAS_X in flags) e.x = p.x
        if (MoveEntityDeltaPacket.Flag.HAS_Y in flags) e.y = p.y
        if (MoveEntityDeltaPacket.Flag.HAS_Z in flags) e.z = p.z
        if (MoveEntityDeltaPacket.Flag.HAS_YAW      in flags) e.yaw     = p.yaw
        if (MoveEntityDeltaPacket.Flag.HAS_PITCH     in flags) e.pitch   = p.pitch
        if (MoveEntityDeltaPacket.Flag.HAS_HEAD_YAW  in flags) e.headYaw = p.headYaw
        if (MoveEntityDeltaPacket.Flag.ON_GROUND     in flags) e.isOnGround = true

        e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleMovePlayer(p: MovePlayerPacket, dir: PacketEvent.Direction) {
        if (p.runtimeEntityId == selfRuntimeId) {
            if (dir == PacketEvent.Direction.CLIENT_TO_SERVER) {
                prevSelfX = selfX; prevSelfZ = selfZ
                selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
                selfYaw = p.rotation.y; selfPitch = p.rotation.x
                selfOnGround = p.isOnGround
            }
            return
        }
        val e = entities[p.runtimeEntityId] ?: return
        e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z
        e.x = p.position.x; e.y = p.position.y; e.z = p.position.z
        e.yaw = p.rotation.y; e.pitch = p.rotation.x
        e.isOnGround = p.isOnGround
        e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleAuthInput(p: PlayerAuthInputPacket, dir: PacketEvent.Direction) {
        if (dir != PacketEvent.Direction.CLIENT_TO_SERVER) return
        prevSelfX = selfX; prevSelfZ = selfZ
        selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
        selfYaw = p.rotation.y; selfPitch = p.rotation.x
    }

    private fun handleEntityMotion(p: SetEntityMotionPacket) {
        if (p.runtimeEntityId == selfRuntimeId) return
        val e = entities[p.runtimeEntityId] ?: return
        e.velX = p.motion.x; e.velY = p.motion.y; e.velZ = p.motion.z
    }

    private fun handleEntityData(p: SetEntityDataPacket) {
        val e = entities[p.runtimeEntityId] ?: return
        applyMetadata(e, p.metadata)
        e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleAttributes(p: UpdateAttributesPacket) {
        val isSelf = p.runtimeEntityId == selfRuntimeId
        p.attributes.forEach { attr ->
            when (attr.name) {
                "minecraft:health" -> {
                    if (isSelf) {
                        selfHealth = attr.value; selfMaxHealth = attr.maximum
                        _selfHealthFlow.value = selfHealth
                    } else {
                        entities[p.runtimeEntityId]?.let {
                            it.health = attr.value; it.maxHealth = attr.maximum
                        }
                    }
                }
            }
        }
    }

    private fun handlePlayerList(p: PlayerListPacket) {
        if (p.action != PlayerListPacket.Action.ADD) return
        // PlayerListPacket.Entry has: uuid, entityId (uniqueId), name, xuid, skin...
        // No latency/ping field exists in 3.0
        p.entries.forEach { entry ->
            val rid = uniqueToRuntime[entry.entityId] ?: return@forEach
            val name = entry.name ?: return@forEach
            entities[rid]?.name = name.toString()
        }
    }

    private fun handleDimension(p: ChangeDimensionPacket) {
        selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
        val old = entities.size
        entities.clear(); uniqueToRuntime.clear()
        Log.i(TAG, "Boyut değişti, $old entity temizlendi")
        notifyUpdate()
    }

    private fun handleEntityLink(p: SetEntityLinkPacket) {
        // SetEntityLinkPacket has single EntityLinkData: p.entityLink
        // EntityLinkData fields: from (ridden), to (rider), type, immediate, riderInitiated
        val link = p.entityLink ?: return
        val riderRid  = uniqueToRuntime[link.to]   ?: return
        val rider     = entities[riderRid]          ?: return
        val typeStr   = link.type?.toString()?.uppercase() ?: ""
        when {
            typeStr == "REMOVE" -> { rider.isRiding = false; rider.ridingId = 0L }
            else                -> {
                rider.isRiding = true
                rider.ridingId = uniqueToRuntime[link.from] ?: 0L
            }
        }
    }

    private fun applyMetadata(entity: TrackedEntity, metadata: Map<*, *>?) {
        if (metadata == null) return
        try {
            metadata.forEach { (key, value) ->
                val keyStr = key?.toString() ?: return@forEach
                when (keyStr) {
                    "2"  -> entity.name   = (value as? String) ?: entity.name
                    "7"  -> entity.health = (value as? Float)  ?: entity.health
                }
            }
        } catch (_: Exception) {}
    }

    private fun resolveType(id: String): EntityType {
        val n = id.lowercase().removePrefix("minecraft:")
        return when {
            n == "player"           -> EntityType.PLAYER
            n.contains("crystal")  -> EntityType.CRYSTAL
            n in MONSTER_IDS        -> EntityType.MONSTER
            n in ANIMAL_IDS         -> EntityType.ANIMAL
            n in PASSIVE_IDS        -> EntityType.PASSIVE
            n in PROJECTILE_IDS     -> EntityType.PROJECTILE
            n == "item"             -> EntityType.ITEM
            else                    -> EntityType.UNKNOWN
        }
    }

    private val MONSTER_IDS = setOf(
        "zombie","skeleton","creeper","spider","cave_spider","enderman","witch","phantom",
        "drowned","husk","stray","wither_skeleton","blaze","ghast","magma_cube","slime",
        "guardian","elder_guardian","shulker","vindicator","evoker","vex","pillager",
        "ravager","hoglin","piglin","zoglin","piglin_brute","warden","zombie_villager",
        "zombie_pigman","zombified_piglin","endermite","silverfish"
    )
    private val ANIMAL_IDS = setOf(
        "cow","pig","sheep","chicken","horse","donkey","mule","rabbit","wolf","cat",
        "ocelot","panda","polar_bear","fox","bee","turtle","dolphin","squid","glow_squid","axolotl","goat"
    )
    private val PASSIVE_IDS = setOf(
        "villager","wandering_trader","iron_golem","snow_golem","bat","parrot","allay"
    )
    private val PROJECTILE_IDS = setOf(
        "arrow","spectral_arrow","thrown_trident","snowball","egg","ender_pearl",
        "fireball","small_fireball","fishing_hook","shulker_bullet","wither_skull"
    )

    fun getAll()    : Collection<TrackedEntity> = entities.values
    fun getById(id: Long) = entities[id]

    fun getEntitiesInRange(range: Float): List<TrackedEntity> {
        val r2 = range * range
        return entities.values.filter {
            MathUtil.dist3sq(it.x, it.y, it.z, selfX, selfY, selfZ) <= r2
        }
    }

    fun getPlayers (range: Float = Float.MAX_VALUE) = getEntitiesInRange(range).filter { it.isPlayer }
    fun getHostiles(range: Float = Float.MAX_VALUE) = getEntitiesInRange(range).filter { it.isHostile }
    fun getCrystals(range: Float = Float.MAX_VALUE) = getEntitiesInRange(range).filter { it.isCrystal }

    fun distanceTo(e: TrackedEntity) = MathUtil.dist3(e.x, e.y, e.z, selfX, selfY, selfZ)
    fun distanceTo(x: Float, y: Float, z: Float) = MathUtil.dist3(x, y, z, selfX, selfY, selfZ)

    fun count()        = entities.size
    fun playerCount()  = entities.values.count { it.isPlayer }
    fun hostileCount() = entities.values.count { it.isHostile }

    private fun notifyUpdate() { _entityCountFlow.value = entities.size }
}
