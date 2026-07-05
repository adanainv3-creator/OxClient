
package com.oxclient.core.proxy

import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.utils.MathUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
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
        var absorbHealth : Float   = 0f,
        var armorValue   : Float   = 0f,
        var movSpeed     : Float   = 0.1f,
        var attackDmg    : Float   = 2f,
        var isOnGround   : Boolean = true,
        var isRiding     : Boolean = false,
        var ridingId     : Long    = 0L,
        var isSneaking   : Boolean = false,
        var isSprinting  : Boolean = false,
        var isInvisible  : Boolean = false,
        var name         : String  = "",
        var pingMs       : Int     = 0,
        val spawnTime    : Long    = System.currentTimeMillis(),
        var lastUpdateMs : Long    = System.currentTimeMillis(),
        var prevX        : Float   = 0f,
        var prevY        : Float   = 0f,
        var prevZ        : Float   = 0f,
        var hurtTime     : Int     = 0,
        var deathAnim    : Boolean = false
    ) {
        val speedXZ     : Float   get() = MathUtil.dist2(x, z, prevX, prevZ)
        val isMoving    : Boolean get() = speedXZ > 0.01f
        val isCrystal   : Boolean get() = type == EntityType.CRYSTAL || identifier.contains("crystal", ignoreCase = true)
        val isPlayer    : Boolean get() = type == EntityType.PLAYER
        val isHostile   : Boolean get() = type == EntityType.MONSTER
        val healthPercent: Float  get() = if (maxHealth > 0f) health / maxHealth else 0f
        fun predictedPosition(t: Float) = Triple(x + velX * t, y + velY * t, z + velZ * t)
    }

    private val entities        = ConcurrentHashMap<Long, TrackedEntity>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    private val playerNames     = ConcurrentHashMap<Long, String>()

    // ✅ FIX: Kendi envanterimizin sürekli/oturum-boyunca önbelleği. InventoryContentPacket
    // sadece bağlantı başında/envanter açıldığında gelir; AutoTotem gibi modüller oyuna
    // girdikten SONRA enable edilirse bu paketi bir daha hiç göremiyordu (BlockTracker'daki
    // palet sorununun envanter karşılığı). EntityTracker zaten her zaman aktif olduğu için
    // envanter durumu burada tutulup modüller enable anında buradan "yakalanabiliyor".
    private val selfInventory = ConcurrentHashMap<Int, ItemData>()

    @Volatile var selfRuntimeId  : Long    = 0L
    @Volatile var selfUniqueId   : Long    = 0L
    @Volatile var selfX          : Float   = 0f
    @Volatile var selfY          : Float   = 0f
    @Volatile var selfZ          : Float   = 0f
    @Volatile var selfYaw        : Float   = 0f
    @Volatile var selfPitch      : Float   = 0f
    @Volatile var selfHealth     : Float   = 20f
    @Volatile var selfMaxHealth  : Float   = 20f
    @Volatile var selfAbsorb     : Float   = 0f
    @Volatile var selfArmor      : Float   = 0f
    @Volatile var selfHunger     : Float   = 20f
    @Volatile var selfSaturation : Float   = 5f
    @Volatile var selfOnGround   : Boolean = true
    @Volatile var selfGameMode   : Int     = 0
    @Volatile var selfDimension  : Int     = 0
    @Volatile var selfSpeedXZ    : Float   = 0f

    private var prevSelfX = 0f; private var prevSelfZ = 0f

    private val _entityCountFlow  = MutableStateFlow(0)
    val entityCountFlow : StateFlow<Int>   = _entityCountFlow.asStateFlow()
    private val _selfHealthFlow   = MutableStateFlow(20f)
    val selfHealthFlow  : StateFlow<Float> = _selfHealthFlow.asStateFlow()
    private val _entityUpdateFlow = MutableStateFlow(0L)
    val entityUpdateFlow: StateFlow<Long>  = _entityUpdateFlow.asStateFlow()

    fun init()  { PacketEventBus.register(this);   OverlayLogger.i(TAG, "EntityTracker başlatıldı") }

    fun reset() {
        entities.clear(); uniqueToRuntime.clear(); playerNames.clear()
        selfInventory.clear()
        selfRuntimeId = 0L; selfUniqueId = 0L
        selfX = 0f; selfY = 0f; selfZ = 0f; selfYaw = 0f; selfPitch = 0f
        selfHealth = 20f; selfMaxHealth = 20f; selfAbsorb = 0f; selfArmor = 0f
        selfHunger = 20f; selfSaturation = 5f; selfOnGround = true
        selfGameMode = 0; selfDimension = 0; selfSpeedXZ = 0f
        prevSelfX = 0f; prevSelfZ = 0f
        _entityCountFlow.value = 0; _selfHealthFlow.value = 20f
        OverlayLogger.i(TAG, "EntityTracker sıfırlandı")
    }

    // ✅ DEBUG: Bir sürüm boyunca görülen HER FARKLI paket tipini bir KEZ loglar.
    // Spam yapmaz (her tip için sadece ilk görülüşte yazar). AutoTotem envanteri
    // hâlâ boş görüyorsa, bu logu arayıp "Inventory" geçen satır var mı diye bak:
    //  - Hiç "InventoryContentPacket" görünmüyorsa   → paket server'dan hiç gelmiyor/routing sorunu
    //  - Sadece "UnknownPacket" görünüyorsa (sık sık) → codec bu protokol sürümünde paketi tanımıyor, decode edemiyor
    //  - "InventoryContentPacket" görünüyor ama envanter boşsa → containerId beklenenden farklı, aşağıdaki log'a bak
    private val seenPacketTypes = ConcurrentHashMap<String, Boolean>()

    override fun onPacket(event: PacketEvent) {
        val typeName = event.packetName
        if (seenPacketTypes.putIfAbsent(typeName, true) == null) {
            OverlayLogger.d(TAG, "İlk kez görülen paket tipi: $typeName (dir=${event.direction})")
        }

        when (val p = event.packet) {
            is StartGamePacket          -> handleStartGame(p)
            is AddEntityPacket          -> handleAddEntity(p)
            is AddPlayerPacket          -> handleAddPlayer(p)
            is RemoveEntityPacket       -> handleRemoveEntity(p)
            is MoveEntityAbsolutePacket -> handleMoveAbsolute(p)
            is MoveEntityDeltaPacket    -> handleMoveDelta(p)
            is MovePlayerPacket         -> handleMovePlayer(p, event.direction)
            is SetEntityDataPacket      -> handleEntityData(p)
            is SetEntityMotionPacket    -> handleEntityMotion(p)
            is UpdateAttributesPacket   -> handleAttributes(p)
            is PlayerListPacket         -> handlePlayerList(p)
            is EntityEventPacket        -> handleEntityEvent(p)
            is SetPlayerGameTypePacket  -> selfGameMode = p.gamemode
            is RespawnPacket            -> if (p.state == RespawnPacket.State.SERVER_SEARCHING) { selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z }
            is ChangeDimensionPacket    -> handleDimension(p)
            is SetEntityLinkPacket      -> handleEntityLink(p)
            is PlayerAuthInputPacket    -> handleAuthInput(p, event.direction)
            is InventoryContentPacket   -> {
                // ✅ DEBUG: containerId'yi filtre uygulamadan ÖNCE logla — beklenen 0 mı değil mi görelim.
                OverlayLogger.d(TAG, "InventoryContentPacket alındı: containerId=${p.containerId} itemCount=${p.contents?.size}")
                handleInventoryContent(p)
            }
            is InventorySlotPacket      -> {
                OverlayLogger.d(TAG, "InventorySlotPacket alındı: containerId=${p.containerId} slot=${p.slot}")
                handleInventorySlot(p)
            }
            is org.cloudburstmc.protocol.bedrock.packet.UnknownPacket -> {
                // ✅ DEBUG: codec bu paketi decode edemedi (bilinmeyen/versiyon uyumsuz paket).
                // Eğer envanter hâlâ boş geliyorsa ve burada sık sık log görülüyorsa,
                // InventoryContentPacket'in bu protokol sürümünde (975) decode edilemediği
                // ihtimali güçlenir — CloudburstMC codec'i bu paket ID'sini tanımıyor demektir.
                OverlayLogger.v(TAG, "UnknownPacket alındı: packetId=${p.packetId}")
            }
            else -> {}
        }
    }

    private fun handleStartGame(p: StartGamePacket) {
        selfRuntimeId = p.runtimeEntityId; selfUniqueId = p.uniqueEntityId
        selfX = p.playerPosition.x; selfY = p.playerPosition.y; selfZ = p.playerPosition.z
        selfYaw = p.rotation.y; selfPitch = p.rotation.x
        selfGameMode = p.playerGameType.ordinal
        OverlayLogger.i(TAG, "StartGame id=$selfRuntimeId pos=($selfX,$selfY,$selfZ)")
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
            headYaw    = p.headRotation,
            velX       = p.motion.x,
            velY       = p.motion.y,
            velZ       = p.motion.z,
        )
        applyMetadata(e, p.metadata)
        entities[p.runtimeEntityId] = e; uniqueToRuntime[p.uniqueEntityId] = p.runtimeEntityId
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
        applyMetadata(e, p.metadata)
        entities[p.runtimeEntityId] = e; uniqueToRuntime[p.uniqueEntityId] = p.runtimeEntityId
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
        e.isOnGround = p.isOnGround; e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleMoveDelta(p: MoveEntityDeltaPacket) {
        val e = entities[p.runtimeEntityId] ?: return
        e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z

        val flags: Set<*>? = try { p.flags } catch (_: Exception) { null }

        if (flags != null && flags.isNotEmpty()) {
            for (flag in flags) {
                when (flag.toString().uppercase()) {
                    "HAS_X"        -> e.x       += p.x
                    "HAS_Y"        -> e.y       += p.y
                    "HAS_Z"        -> e.z       += p.z
                    "HAS_YAW"      -> e.yaw      = p.yaw
                    "HAS_PITCH"    -> e.pitch    = p.pitch
                    "HAS_HEAD_YAW" -> e.headYaw  = p.headYaw
                    "ON_GROUND"    -> e.isOnGround = true
                }
            }
        } else {
            try { e.x += p.x; e.y += p.y; e.z += p.z } catch (_: Exception) {}
        }
        e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleMovePlayer(p: MovePlayerPacket, dir: PacketEvent.Direction) {
        if (p.runtimeEntityId == selfRuntimeId) {
            if (dir == PacketEvent.Direction.CLIENT_TO_SERVER) {
                prevSelfX = selfX; prevSelfZ = selfZ
                selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
                selfYaw = p.rotation.y; selfPitch = p.rotation.x
                selfOnGround = p.isOnGround
                selfSpeedXZ  = MathUtil.dist2(selfX, selfZ, prevSelfX, prevSelfZ)
            }
            return
        }
        val e = entities[p.runtimeEntityId] ?: return
        e.prevX = e.x; e.prevY = e.y; e.prevZ = e.z
        e.x = p.position.x; e.y = p.position.y; e.z = p.position.z
        e.yaw = p.rotation.y; e.pitch = p.rotation.x
        e.isOnGround = p.isOnGround; e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleAuthInput(p: PlayerAuthInputPacket, dir: PacketEvent.Direction) {
        if (dir != PacketEvent.Direction.CLIENT_TO_SERVER) return
        prevSelfX = selfX; prevSelfZ = selfZ
        selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
        selfYaw = p.rotation.y; selfPitch = p.rotation.x
        selfSpeedXZ = MathUtil.dist2(selfX, selfZ, prevSelfX, prevSelfZ)
    }

    private fun handleEntityMotion(p: SetEntityMotionPacket) {
        if (p.runtimeEntityId == selfRuntimeId) return
        val e = entities[p.runtimeEntityId] ?: return
        e.velX = p.motion.x; e.velY = p.motion.y; e.velZ = p.motion.z
    }

    private fun handleEntityData(p: SetEntityDataPacket) {
        val e = entities[p.runtimeEntityId] ?: return
        applyMetadata(e, p.metadata); e.lastUpdateMs = System.currentTimeMillis()
    }

    private fun handleAttributes(p: UpdateAttributesPacket) {
        val isSelf = p.runtimeEntityId == selfRuntimeId
        p.attributes.forEach { attr ->
            when (attr.name) {
                "minecraft:health" -> {
                    if (isSelf) { selfHealth = attr.value; selfMaxHealth = attr.maximum; _selfHealthFlow.value = selfHealth }
                    else entities[p.runtimeEntityId]?.let { it.health = attr.value; it.maxHealth = attr.maximum }
                }
                "minecraft:absorption"    -> if (isSelf) selfAbsorb    = attr.value else entities[p.runtimeEntityId]?.absorbHealth = attr.value
                "minecraft:armor"         -> if (isSelf) selfArmor     = attr.value else entities[p.runtimeEntityId]?.armorValue   = attr.value
                "minecraft:hunger"        -> if (isSelf) selfHunger    = attr.value
                "minecraft:saturation"    -> if (isSelf) selfSaturation = attr.value
                "minecraft:movement"      -> entities[p.runtimeEntityId]?.movSpeed  = attr.value
                "minecraft:attack_damage" -> entities[p.runtimeEntityId]?.attackDmg = attr.value
            }
        }
    }

    private fun handlePlayerList(p: PlayerListPacket) {
        if (p.action != PlayerListPacket.Action.ADD) {
            p.entries.forEach { playerNames.remove(it.entityId) }
            return
        }
        p.entries.forEach { entry ->
            val rid  = uniqueToRuntime[entry.entityId]
            val name = entry.name ?: return@forEach
            playerNames[entry.entityId] = name
            if (rid != null) {
                entities[rid]?.name = name
                // FIX: latencyMs ve latency alanları yeni Cloudburst API'sinde kaldırıldı.
                // Reflection ile güvenli okuma; yoksa 0 döner.
                val ping = runCatching {
                    entry.javaClass.getDeclaredField("latencyMs")
                        .also { it.isAccessible = true }
                        .getInt(entry)
                }.getOrElse {
                    runCatching {
                        entry.javaClass.getDeclaredField("latency")
                            .also { it.isAccessible = true }
                            .getInt(entry)
                    }.getOrElse { 0 }
                }
                entities[rid]?.pingMs = ping
            }
        }
    }

    private fun handleEntityEvent(p: EntityEventPacket) {
        val e = entities[p.runtimeEntityId] ?: return
        try {
            val typeName = p.type?.toString()?.uppercase() ?: return
            when {
                typeName.contains("HURT")  -> e.hurtTime  = 10
                typeName.contains("DEATH") -> e.deathAnim = true
            }
        } catch (_: Exception) {}
    }

    private fun handleDimension(p: ChangeDimensionPacket) {
        selfDimension = p.dimension
        selfX = p.position.x; selfY = p.position.y; selfZ = p.position.z
        val old = entities.size; entities.clear(); uniqueToRuntime.clear()
        OverlayLogger.i(TAG, "Boyut değişti dim=$selfDimension $old entity temizlendi")
        notifyUpdate()
    }

    private fun handleEntityLink(p: SetEntityLinkPacket) {
        try {
            val link = try { p.entityLink } catch (_: Exception) { null } ?: return

            val riderRid  = uniqueToRuntime[link.to]   ?: return
            val rider     = entities[riderRid]          ?: return
            val typeStr   = link.type?.toString()?.uppercase() ?: ""

            when {
                typeStr.contains("RIDER") || typeStr.contains("PASSENGER") || typeStr.contains("VEHICLE") -> {
                    rider.isRiding = true
                    rider.ridingId = uniqueToRuntime[link.from] ?: 0L
                }
                typeStr.contains("REMOVE") -> {
                    rider.isRiding = false; rider.ridingId = 0L
                }
            }
        } catch (e: Exception) { OverlayLogger.v(TAG, "EntityLink hatası: ${e.message}") }
    }

    /** ✅ FIX: Beta6-SNAPSHOT'ta gelen "boş" slotlar `ItemData.AIR` ile referans/structural
     *  olarak eşleşmiyor (netId farkı nedeniyle) — bu yüzden `item != ItemData.AIR` kontrolü
     *  boş slotları da "dolu item" sanıyordu (definition=null, netId sabit bir değer).
     *  Artık anlamsal kontrol yapılıyor: definition null ise veya count <= 0 ise slot boştur. */
    private fun isEmptyItem(item: ItemData?): Boolean {
        if (item == null) return true
        return try { item.definition == null || item.count <= 0 } catch (_: Exception) { true }
    }

    private fun handleInventoryContent(p: InventoryContentPacket) {
        when (p.containerId) {
            0 -> {
                for (s in 0..35) selfInventory.remove(s)
                p.contents.forEachIndexed { slot, item ->
                    if (!isEmptyItem(item)) selfInventory[slot] = item
                }
            }
            119 -> {
                val item = p.contents.firstOrNull()
                if (item == null || isEmptyItem(item)) selfInventory.remove(119)
                else selfInventory[119] = item
            }
            else -> return
        }
    }

    private fun handleInventorySlot(p: InventorySlotPacket) {
        if (p.containerId != 0 && p.containerId != 119) return
        val slotKey = if (p.containerId == 119) 119 else p.slot
        val item = p.item
        if (isEmptyItem(item)) selfInventory.remove(slotKey)
        else selfInventory[slotKey] = item
    }

    /** Ana envanterdeki (containerId=0, offhand=119 dahil) verilen slotun son bilinen içeriği. */
    fun getInventoryItem(slot: Int): ItemData? = selfInventory[slot]

    /** Ana envanterin anlık kopyası — slot -> ItemData. Modüller geç enable olsa bile
     *  buradan mevcut durumu okuyabilir (bkz. selfInventory üstteki not). */
    fun getInventorySnapshot(): Map<Int, ItemData> = selfInventory.toMap()

    private fun applyMetadata(entity: TrackedEntity, metadata: Map<*, *>?) {
        if (metadata == null) return
        try {
            metadata.forEach { (key, value) ->
                val keyStr = key?.toString()?.uppercase() ?: return@forEach
                when {
                    keyStr.contains("SNEAKING")  -> entity.isSneaking  = value as? Boolean ?: false
                    keyStr.contains("SPRINTING") -> entity.isSprinting = value as? Boolean ?: false
                    keyStr.contains("INVISIBLE") -> entity.isInvisible = value as? Boolean ?: false
                    keyStr == "2" || keyStr.contains("NAMETAG") -> entity.name   = (value as? String) ?: entity.name
                    keyStr == "7" || keyStr.contains("HEALTH")  -> entity.health = (value as? Float) ?: entity.health
                }
            }
        } catch (e: Exception) { OverlayLogger.v(TAG, "Metadata hatası (${entity.identifier}): ${e.message}") }
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
        "ocelot","panda","polar_bear","fox","bee","turtle","salmon","cod","pufferfish",
        "tropical_fish","dolphin","squid","glow_squid","axolotl","goat","frog","tadpole",
        "camel","sniffer","armadillo"
    )
    private val PASSIVE_IDS = setOf(
        "villager","wandering_trader","iron_golem","snow_golem","strider","bat","parrot",
        "mooshroom","llama","trader_llama","allay","chest_minecart","minecart","boat","chest_boat"
    )
    private val PROJECTILE_IDS = setOf(
        "arrow","spectral_arrow","thrown_trident","snowball","egg","ender_pearl",
        "eye_of_ender_signal","fireball","small_fireball","fishing_hook","llama_spit",
        "shulker_bullet","dragon_fireball","wither_skull","fireworks_rocket","wind_charge"
    )

    fun getAll()    : Collection<TrackedEntity> = entities.values
    fun getById(id: Long) = entities[id]
    fun getByUniqueId(uid: Long) = uniqueToRuntime[uid]?.let { entities[it] }
    fun getByName(name: String)  = entities.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun getEntitiesInRange(range: Float): List<TrackedEntity> {
        val r2 = range * range
        return entities.values.filter { MathUtil.dist3sq(it.x, it.y, it.z, selfX, selfY, selfZ) <= r2 }
    }

    fun getPlayers (range: Float = Float.MAX_VALUE) = getEntitiesInRange(range).filter { it.isPlayer }
    fun getHostiles(range: Float = Float.MAX_VALUE) = getEntitiesInRange(range).filter { it.isHostile }
    fun getCrystals(range: Float = Float.MAX_VALUE) = getEntitiesInRange(range).filter { it.isCrystal }

    fun getNearestPlayer (range: Float) = getPlayers(range) .minByOrNull { distanceTo(it) }
    fun getNearestHostile(range: Float) = getHostiles(range).minByOrNull { distanceTo(it) }

    fun distanceTo(e: TrackedEntity)             = MathUtil.dist3(e.x, e.y, e.z, selfX, selfY, selfZ)
    fun distanceTo(x: Float, y: Float, z: Float) = MathUtil.dist3(x, y, z, selfX, selfY, selfZ)
    fun distanceTo2D(e: TrackedEntity)           = MathUtil.dist2(e.x, e.z, selfX, selfZ)

    fun angleToEntity(e: TrackedEntity): Float {
        val yaw = Math.toDegrees(atan2(-(e.x - selfX).toDouble(), (e.z - selfZ).toDouble())).toFloat()
        return abs(((selfYaw - yaw) % 360f + 540f) % 360f - 180f)
    }

    fun isInFov(e: TrackedEntity, fov: Float) = fov >= 360f || angleToEntity(e) <= fov / 2f

    fun getHealthPercent() = if (selfMaxHealth > 0f) selfHealth / selfMaxHealth else 0f
    fun isLowHealth(t: Float = 6f)      = selfHealth <= t
    fun isCriticalHealth(t: Float = 3f) = selfHealth <= t

    fun count()        = entities.size
    fun playerCount()  = entities.values.count { it.isPlayer }
    fun hostileCount() = entities.values.count { it.isHostile }

    fun removeStale(maxAgeMs: Long = 30_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val stale  = entities.entries.filter { it.value.lastUpdateMs < cutoff }
        stale.forEach { (rid, e) -> entities.remove(rid); uniqueToRuntime.remove(e.uniqueId) }
        if (stale.isNotEmpty()) { OverlayLogger.d(TAG, "${stale.size} stale entity temizlendi"); notifyUpdate() }
    }

    private fun notifyUpdate() {
        _entityCountFlow.value  = entities.size
        _entityUpdateFlow.value = System.currentTimeMillis()
    }
}
