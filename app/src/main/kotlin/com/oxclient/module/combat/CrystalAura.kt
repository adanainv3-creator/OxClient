package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.core.relay.Definitions
import com.oxclient.core.relay.OxRelaySession
import com.oxclient.events.PacketEvent
import com.oxclient.events.PacketEventBus
import com.oxclient.module.*
import com.oxclient.ui.overlay.OverlayLogger
import com.oxclient.utils.MathUtil
import com.oxclient.utils.PacketUtil
import com.oxclient.utils.RotationUtil
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "Rakip oyuncunun etrafna otomatik kristal yerletirir ve patlatr"
) {
    enum class Mode { Single, Full5x5 }

    private val range           = float("Range", 5f, 3f, 10f)
    private val placeRange      = float("Place Range", 4.5f, 2f, 8f)  // Yerletirme menzili
    private val breakRange      = float("Break Range", 6f, 3f, 10f)   // Patlatma menzili
    private val suicide         = bool ("Suicide", false)
    private val place           = bool ("Place", true)
    private val breakCrystals   = bool ("Break", true)
    private val delay           = int  ("Delay", 100, 50, 500)
    private val removeParticles = bool ("RemoveParticles", true)
    private val placeMode       = enum ("Place Mode", Mode.Single)
    private val breakMode       = enum ("Break Mode", Mode.Single)
    private val targetMode      = enum ("Target Mode", Mode.Single)  // Single = en yakn, Full5x5 = tümü

    // Aktif crystalleri takip et
    private val activeCrystals  = ConcurrentHashMap<Long, Vector3f>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    
    // Yerletirilen pozisyonlar cache'le (çakmay önlemek için)
    private val placedPositions = ConcurrentHashMap<Long, Long>()

    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastBreakMs = 0L
    private var tickJob: Job? = null

    // Obsidian definition cache
    private var cachedObsidianDef: BlockDefinition? = null
    private var cachedBedrockDef: BlockDefinition? = null

    private companion object { const val TAG = "CrystalAura" }

    override fun onEnable() {
        super.onEnable()
        activeCrystals.clear(); uniqueToRuntime.clear(); placedPositions.clear()
        cachedObsidianDef = null; cachedBedrockDef = null
        OverlayLogger.d(TAG, "Enabled: place=${place.value} break=${breakCrystals.value} target=${targetMode.value}")
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        super.onDisable()
        OverlayLogger.d(TAG, "Disabled")
        activeCrystals.clear(); uniqueToRuntime.clear(); placedPositions.clear()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        when (val pkt = event.packet) {
            is AddEntityPacket -> {
                if (pkt.identifier.contains("crystal", ignoreCase = true)) {
                    activeCrystals[pkt.runtimeEntityId] = pkt.position
                    uniqueToRuntime[pkt.uniqueEntityId] = pkt.runtimeEntityId
                    OverlayLogger.v(TAG, "Crystal eklendi: rid=${pkt.runtimeEntityId} pos=${pkt.position}")
                }
            }
            is RemoveEntityPacket -> {
                val rid = uniqueToRuntime.remove(pkt.uniqueEntityId)
                if (rid != null) {
                    activeCrystals.remove(rid)
                    OverlayLogger.v(TAG, "Crystal kaldrld: rid=$rid")
                }
            }
            is LevelEventPacket -> {
                if (removeParticles.value) {
                    val typeName = pkt.type?.toString()?.uppercase() ?: ""
                    if (typeName.contains("PARTICLE") || typeName.contains("EXPLOSION")) {
                        event.cancel()
                    }
                }
            }
            else -> {}
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) {
                // 1. Önce hedef oyuncuyu bul
                val target = selectTarget()
                
                if (target != null) {
                    // 2. Kristalleri patlat
                    if (breakCrystals.value) {
                        doBreak(target)
                    }
                    
                    // 3. Yeni kristal yerletir
                    if (place.value) {
                        doPlace(target)
                    }
                }
            }
            delay(1L)
        }
    }

    /**
     * En yakn rakip oyuncuyu bul
     */
    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val rangeSq = range.value * range.value
        var closest: EntityTracker.TrackedEntity? = null
        var closestDistSq = Float.MAX_VALUE
        
        for (e in EntityTracker.getAll()) {
            // Sadece oyuncular hedef al (kendini deil)
            if (!e.isPlayer) continue
            if (e.runtimeId == EntityTracker.selfRuntimeId) continue
            
            val dx = e.x - EntityTracker.selfX
            val dz = e.z - EntityTracker.selfZ
            val distSq = dx*dx + dz*dz  // Sadece XZ düzlemi
            if (distSq < rangeSq && distSq < closestDistSq) {
                closestDistSq = distSq
                closest = e
            }
        }
        return closest
    }

    /**
     * Obsidian veya Bedrock definition'n bul
     */
    private fun getBlockDefinition(session: OxRelaySession, isBedrock: Boolean = false): BlockDefinition? {
        if (isBedrock) {
            cachedBedrockDef?.let { return it }
        } else {
            cachedObsidianDef?.let { return it }
        }

        val targetId = if (isBedrock) "minecraft:bedrock" else "minecraft:obsidian"
        
        try {
            // 1. Server'dan gelen block definitions' dene
            val blockDefs = session.clientSession.definitionRegistry.blockDefinitions
            if (blockDefs != null) {
                // Tüm block ID'lerini tara
                for (i in 0..1000) {
                    val def = blockDefs.getDefinition(i)
                    if (def != null) {
                        val id = when (def) {
                            is SimpleBlockDefinition -> def.identifier
                            is Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
                            else -> null
                        }
                        if (id == targetId) {
                            val result = def
                            if (isBedrock) cachedBedrockDef = result else cachedObsidianDef = result
                            OverlayLogger.d(TAG, "Block definition bulundu: $targetId runtimeId=$i")
                            return result
                        }
                    }
                }
            }

            // 2. Fallback: Definitions'dan al
            val blockDefs2 = Definitions.getClosestDefinitions(session.activeCodec.protocolVersion).blockDefinitions
            for (i in 0..1000) {
                val def = blockDefs2.getDefinition(i)
                if (def != null) {
                    val id = when (def) {
                        is SimpleBlockDefinition -> def.identifier
                        is Definitions.NbtBlockDefinitionRegistry.NbtBlockDefinition -> def.tag.getString("name")
                        else -> null
                    }
                    if (id == targetId) {
                        val result = def
                        if (isBedrock) cachedBedrockDef = result else cachedObsidianDef = result
                        OverlayLogger.d(TAG, "Block definition bulundu (Definitions): $targetId runtimeId=$i")
                        return result
                    }
                }
            }

            // 3. Son çare: manuel fallback
            OverlayLogger.w(TAG, "Block definition bulunamad, fallback oluturuluyor: $targetId")
            val fallback = SimpleBlockDefinition(
                targetId,
                if (isBedrock) 7 else 49,
                org.cloudburstmc.nbt.NbtMap.builder()
                    .putString("name", targetId)
                    .putCompound("states", org.cloudburstmc.nbt.NbtMap.builder().build())
                    .build()
            )
            if (isBedrock) cachedBedrockDef = fallback else cachedObsidianDef = fallback
            return fallback
        } catch (e: Exception) {
            OverlayLogger.e(TAG, "Block definition hatas: ${e.message}", e)
            return null
        }
    }

    /**
     * Hedef oyuncunun etrafna kristal yerletir
     */
    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < delay.value) return
        lastPlaceMs = now

        val session = PacketEventBus.currentSession ?: return
        
        // Elinde crystal var m kontrol et
        val heldItem = EntityTracker.getHeldItem()
        if (heldItem == null || heldItem.count <= 0) {
            OverlayLogger.v(TAG, "Elinde item yok")
            return
        }
        
        val itemId = runCatching { heldItem.definition?.identifier }.getOrElse { null }
        if (itemId != "minecraft:end_crystal") {
            OverlayLogger.v(TAG, "Elindeki item crystal deil: $itemId")
            return
        }

        // Hedef oyuncunun etrafndaki yerletirme pozisyonlarn hesapla
        val positions = getPlacePositions(target)
        if (positions.isEmpty()) {
            OverlayLogger.v(TAG, "Uygun yerletirme pozisyonu bulunamad")
            return
        }

        // Obsidian veya Bedrock definition'n al
        val blockDef = getBlockDefinition(session, false)  // false = obsidian
            ?: return

        for ((bx, by, bz) in positions) {
            // Bu pozisyona daha önce kristal yerletirdik mi?
            val bKey = packKey(bx, by, bz)
            if (placedPositions.containsKey(bKey)) {
                // 5 saniye sonra cache'i temizle
                val placedTime = placedPositions[bKey] ?: 0L
                if (System.currentTimeMillis() - placedTime > 5000) {
                    placedPositions.remove(bKey)
                } else {
                    continue
                }
            }

            // Bu pozisyonda zaten kristal var m?
            val alreadyPlaced = activeCrystals.values.any { c ->
                Math.abs(c.x - bx - 0.5f) < 0.5f &&
                Math.abs(c.y - by - 1f)   < 0.5f &&
                Math.abs(c.z - bz - 0.5f) < 0.5f
            }
            if (alreadyPlaced) continue

            // Menzil kontrolü
            val dist = MathUtil.dist3(bx.toFloat(), by.toFloat(), bz.toFloat(),
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            if (dist > placeRange.value) continue

            // Suicide kontrolü
            if (!suicide.value) {
                val selfDist = MathUtil.dist3(bx + 0.5f, by + 1f, bz + 0.5f,
                    EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ)
                if (selfDist < 3f) continue
            }

            // Paketi olutur ve gönder
            try {
                val packet = InventoryTransactionPacket().apply {
                    transactionType = InventoryTransactionType.ITEM_USE
                    actionType = 0  // PLACE
                    blockPosition = Vector3i.from(bx, by, bz)
                    blockFace = 1  // Yukar
                    hotbarSlot = EntityTracker.selfHotbarSlot
                    itemInHand = heldItem
                    playerPosition = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                    clickPosition = Vector3f.from(0.5f, 0.0f, 0.5f)
                    this.blockDefinition = blockDef
                    triggerType = ItemUseTransaction.TriggerType.PLAYER_INPUT
                    clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                    clientCooldownState = 0
                }
                session.serverBound(packet)
                placedPositions[bKey] = System.currentTimeMillis()
                OverlayLogger.d(TAG, "Crystal yerletirildi: ($bx,$by,$bz) hedef=${target.name}")
            } catch (e: Exception) {
                OverlayLogger.w(TAG, "Crystal yerletirme hatas: ${e.message}")
            }
        }
    }

    /**
     * Hedef oyuncunun etrafndaki yerletirme pozisyonlarn hesapla
     */
    private fun getPlacePositions(target: EntityTracker.TrackedEntity): List<Triple<Int, Int, Int>> {
        val positions = mutableListOf<Triple<Int, Int, Int>>()
        val tx = target.x.toInt()
        val ty = target.y.toInt()
        val tz = target.z.toInt()

        // Hedef oyuncunun ayaklarnn altndan bala
        val baseY = ty - 1  // Oyuncunun altndaki blok
        
        // Obsidian bulunan pozisyonlar tara
        val searchRadius = when (placeMode.value) {
            Mode.Single -> 1
            Mode.Full5x5 -> 2
        }

        for (dx in -searchRadius..searchRadius) {
            for (dz in -searchRadius..searchRadius) {
                // Sadece çevresel pozisyonlar (merkez dahil deil)
                if (placeMode.value == Mode.Single && dx == 0 && dz == 0) continue
                if (placeMode.value == Mode.Full5x5) {
                    // Full5x5'te merkez dahil tüm pozisyonlar
                }
                
                val bx = tx + dx
                val bz = tz + dz
                val by = baseY
                
                // Bu pozisyon menzilde mi?
                val dist = MathUtil.dist3(bx.toFloat(), by.toFloat(), bz.toFloat(),
                    EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                if (dist > placeRange.value) continue
                
                // Oyuncunun kendisine çok yakn m?
                val distToTarget = MathUtil.dist3(bx.toFloat(), by.toFloat(), bz.toFloat(),
                    target.x, target.y, target.z)
                if (distToTarget > 4f) continue  // Oyuncunun çok uzana koyma
                
                positions.add(Triple(bx, by, bz))
            }
        }

        return positions
    }

    /**
     * Hedef oyuncunun etrafndaki crystalleri patlat
     */
    private fun doBreak(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < delay.value) return
        lastBreakMs = now

        val session = PacketEventBus.currentSession ?: return

        // Hedef oyuncunun etrafndaki crystalleri bul
        val targetRange = breakRange.value
        val inRange = activeCrystals.entries.filter { (_, pos) ->
            val dist = MathUtil.dist3(pos.x, pos.y, pos.z, target.x, target.y, target.z)
            dist <= targetRange
        }

        if (inRange.isEmpty()) return

        when (breakMode.value) {
            Mode.Single -> {
                // En yakn crystal'i patlat
                val nearest = inRange.minByOrNull { (_, pos) ->
                    MathUtil.dist3(pos.x, pos.y, pos.z, target.x, target.y, target.z)
                }
                nearest?.let { (rid, _) ->
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                }
            }
            Mode.Full5x5 -> {
                // Tüm crystalleri patlat
                for ((rid, _) in inRange) {
                    attackCrystal(rid, session)
                    activeCrystals.remove(rid)
                }
            }
        }
    }

    private fun attackCrystal(rid: Long, session: OxRelaySession) {
        val pos = activeCrystals[rid] ?: return
        val r = RotationUtil.toPoint(pos.x, pos.y, pos.z)
        PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        PacketUtil.sendSwingAndAttack(session, rid)
        OverlayLogger.v(TAG, "Crystal patlatld: rid=$rid pos=${pos}")
    }

    private fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}