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
import com.oxclient.utils.WorldBlockTracker
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction  // TriggerType + PredictedResult enum'ları için
import org.cloudburstmc.protocol.bedrock.packet.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class CrystalAura : BaseModule(
    name        = "CrystalAura",
    category    = ModuleCategory.COMBAT,
    description = "Rakip oyuncunun etrafına otomatik kristal yerleştirir ve patlatır"
) {
    enum class Mode { Single, Full5x5 }

    private val range           = float("Range", 5f, 3f, 10f)
    private val placeRange      = float("Place Range", 4.5f, 2f, 8f)
    private val breakRange      = float("Break Range", 6f, 3f, 10f)
    private val suicide         = bool ("Suicide", false)
    private val place           = bool ("Place", true)
    private val breakCrystals   = bool ("Break", true)
    private val delay           = int  ("Delay", 100, 50, 500)
    private val removeParticles = bool ("RemoveParticles", true)
    private val placeMode       = enum ("Place Mode", Mode.Single)
    private val breakMode       = enum ("Break Mode", Mode.Single)
    private val targetMode      = enum ("Target Mode", Mode.Single)

    private val activeCrystals  = ConcurrentHashMap<Long, Vector3f>()
    private val uniqueToRuntime = ConcurrentHashMap<Long, Long>()
    private val placedPositions = ConcurrentHashMap<Long, Long>()

    @Volatile private var lastPlaceMs = 0L
    @Volatile private var lastBreakMs = 0L
    private var tickJob: Job? = null

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
                    OverlayLogger.v(TAG, "Crystal kaldırıldı: rid=$rid")
                }
            }
            is LevelEventPacket -> {
                // FIX #1: String karşılaştırması yerine LevelEvent enum'u doğrudan karşılaştır.
                // Eski kod: pkt.type?.toString()?.uppercase() ile "PARTICLE"/"EXPLOSION" string arama →
                //   yanlış: ses efektleri de (SOUND_GHAST_FIREBALL gibi) "EXPLOSION" içerdiği için
                //   cancel ediliyordu. Ayrıca toString() implementasyona göre değişebilir.
                // ModuleCrystalAura_kt.txt referansı: `packet.type == LevelEvent.PARTICLE_EXPLOSION`
                if (removeParticles.value) {
                    val type = pkt.type
                    if (type == LevelEvent.PARTICLE_EXPLOSION || type == LevelEvent.PARTICLE_BLOCK_EXPLOSION) {
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
                val target = selectTarget()
                if (target != null) {
                    if (breakCrystals.value) doBreak(target)
                    if (place.value) doPlace(target)
                }
            }
            delay(1L)
        }
    }

    private fun selectTarget(): EntityTracker.TrackedEntity? {
        val rangeSq = range.value * range.value
        var closest: EntityTracker.TrackedEntity? = null
        var closestDistSq = Float.MAX_VALUE
        for (e in EntityTracker.getAll()) {
            if (!e.isPlayer) continue
            if (e.runtimeId == EntityTracker.selfRuntimeId) continue
            val dx = e.x - EntityTracker.selfX
            val dz = e.z - EntityTracker.selfZ
            val distSq = dx * dx + dz * dz
            if (distSq < rangeSq && distSq < closestDistSq) {
                closestDistSq = distSq
                closest = e
            }
        }
        return closest
    }

    private fun getBlockDefinition(session: OxRelaySession, isBedrock: Boolean = false): BlockDefinition? {
        if (isBedrock) cachedBedrockDef?.let { return it } else cachedObsidianDef?.let { return it }
        val targetId = if (isBedrock) "minecraft:bedrock" else "minecraft:obsidian"
        try {
            val blockDefs = session.clientSession.peer.codecHelper.blockDefinitions
            if (blockDefs != null) {
                for (i in 0..2048) {
                    val def = try { blockDefs.getDefinition(i) } catch (_: Exception) { null } ?: continue
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
            val blockDefs2 = Definitions.getClosestDefinitions(session.activeCodec.protocolVersion).blockDefinitions
            for (i in 0..2048) {
                val def = try { blockDefs2.getDefinition(i) } catch (_: Exception) { null } ?: continue
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
            OverlayLogger.w(TAG, "Block definition bulunamadı, fallback oluşturuluyor: $targetId")
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
            OverlayLogger.e(TAG, "Block definition hatası: ${e.message}", e)
            return null
        }
    }

    private fun doPlace(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceMs < delay.value) return
        lastPlaceMs = now

        val session = PacketEventBus.currentSession ?: return

        val heldItem = EntityTracker.getHeldItem()
        if (heldItem == null || heldItem.count <= 0) {
            OverlayLogger.d(TAG, "Elinde item yok")
            return
        }
        val itemId = runCatching { heldItem.definition?.identifier }.getOrElse { null }
        if (itemId != "minecraft:end_crystal") {
            OverlayLogger.d(TAG, "Elindeki item crystal değil: $itemId")
            return
        }

        val positions = getPlacePositions(target)
        if (positions.isEmpty()) {
            // ✅ TANI: "chunk verisi hiç yok" ile "chunk verisi var ama yakında
            // obsidian/bedrock yok" durumlarını ayırt et — WorldBlockTracker'ın
            // hiç terrain almadığı (ör. LevelChunkPacket/SubChunkPacket akışı
            // uyuşmazlığı) durumda pozisyon bulunamaz ve bunu bilmek gerekir.
            val terrainKnown = WorldBlockTracker.hasAnyTerrainData()
            OverlayLogger.d(
                TAG,
                "Uygun yerleştirme pozisyonu bulunamadı (terrain verisi ${if (terrainKnown) "MEVCUT ama yakında obsidian/bedrock yok" else "HİÇ YOK — WorldBlockTracker henüz bir chunk decode edemedi"})"
            )
            return
        }

        val blockDef = getBlockDefinition(session, false) ?: return

        for ((bx, by, bz) in positions) {
            val bKey = packKey(bx, by, bz)
            if (placedPositions.containsKey(bKey)) {
                val placedTime = placedPositions[bKey] ?: 0L
                if (System.currentTimeMillis() - placedTime > 5000) {
                    placedPositions.remove(bKey)
                } else {
                    continue
                }
            }

            val alreadyPlaced = activeCrystals.values.any { c ->
                Math.abs(c.x - bx - 0.5f) < 0.5f &&
                Math.abs(c.y - by - 1f)   < 0.5f &&
                Math.abs(c.z - bz - 0.5f) < 0.5f
            }
            if (alreadyPlaced) continue

            val centerX = bx + 0.5f; val centerY = by + 1f; val centerZ = bz + 0.5f

            val dist = MathUtil.dist3(centerX, centerY, centerZ,
                EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
            if (dist > placeRange.value) continue

            if (!suicide.value) {
                val selfDist = MathUtil.dist3(centerX, centerY, centerZ,
                    EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ)
                if (selfDist < 3f) continue
            }

            try {
                // Bu versiyonda (InventoryTransactionPacket__5_.java) tüm ITEM_USE alanları
                // doğrudan InventoryTransactionPacket üzerinde — ayrı bir alt obje yok.
                val packet = InventoryTransactionPacket().apply {
                    transactionType          = InventoryTransactionType.ITEM_USE
                    actionType               = 0  // 0 = INTERACT (block'a tıkla/place)
                    blockPosition            = Vector3i.from(bx, by, bz)
                    blockFace                = 1  // 1 = UP yüzü
                    hotbarSlot               = EntityTracker.selfHotbarSlot
                    itemInHand               = heldItem
                    playerPosition           = Vector3f.from(EntityTracker.selfX, EntityTracker.selfY, EntityTracker.selfZ)
                    // ✅ FIX: blockFace=UP iken clickPosition'ın y bileşeni o yüzün
                    // düzlemine (y=1.0) denk gelmeli. Eskiden y=0.0 veriliyordu —
                    // face/clickPos tutarsızlığı bazı sunucularda (ör. anti-cheat/
                    // vanilla place-validation) paketin sessizce reddedilmesine yol açar.
                    clickPosition            = Vector3f.from(0.5f, 1.0f, 0.5f)
                    blockDefinition          = blockDef
                    triggerType              = ItemUseTransaction.TriggerType.PLAYER_INPUT
                    clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                    clientCooldownState      = 0
                }
                session.serverBound(packet)
                placedPositions[bKey] = System.currentTimeMillis()
                OverlayLogger.d(TAG, "Crystal yerleştirildi: ($bx,$by,$bz) hedef=${target.name}")
            } catch (e: Exception) {
                OverlayLogger.w(TAG, "Crystal yerleştirme hatası: ${e.message}")
            }
        }
    }

    private fun getPlacePositions(target: EntityTracker.TrackedEntity): List<Triple<Int, Int, Int>> {
        val positions = mutableListOf<Triple<Int, Int, Int>>()

        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()

        val searchRadius = when (placeMode.value) {
            Mode.Single -> 1
            Mode.Full5x5 -> 2
        }

        for (dx in -searchRadius..searchRadius) {
            for (dz in -searchRadius..searchRadius) {
                if (placeMode.value == Mode.Single && dx == 0 && dz == 0) continue

                val bx = tx + dx
                val bz = tz + dz

                val by = findValidSurface(bx, ty, bz) ?: continue

                val distToTarget = MathUtil.dist3(bx + 0.5f, by + 1f, bz + 0.5f, target.x, target.y, target.z)
                if (distToTarget > 4f) continue

                positions.add(Triple(bx, by, bz))
            }
        }
        return positions
    }

    private fun findValidSurface(bx: Int, ty: Int, bz: Int): Int? {
        for (by in (ty - 2)..(ty + 1)) {
            val here = WorldBlockTracker.getBlockIdentifier(bx, by, bz) ?: continue
            if (here != "minecraft:obsidian" && here != "minecraft:bedrock") continue
            val above = WorldBlockTracker.getBlockIdentifier(bx, by + 1, bz) ?: continue
            if (above != "minecraft:air") continue
            return by
        }
        return null
    }

    private fun doBreak(target: EntityTracker.TrackedEntity) {
        val now = System.currentTimeMillis()
        if (now - lastBreakMs < delay.value) return
        lastBreakMs = now

        val session = PacketEventBus.currentSession ?: return
        val targetRange = breakRange.value
        val inRange = activeCrystals.entries.filter { (_, pos) ->
            MathUtil.dist3(pos.x, pos.y, pos.z, target.x, target.y, target.z) <= targetRange
        }
        if (inRange.isEmpty()) return

        when (breakMode.value) {
            Mode.Single -> {
                val nearest = inRange.minByOrNull { (_, pos) ->
                    MathUtil.dist3(pos.x, pos.y, pos.z, target.x, target.y, target.z)
                }
                nearest?.let { (rid, _) -> attackCrystal(rid, session); activeCrystals.remove(rid) }
            }
            Mode.Full5x5 -> {
                for ((rid, _) in inRange) { attackCrystal(rid, session); activeCrystals.remove(rid) }
            }
        }
    }

    private fun attackCrystal(rid: Long, session: OxRelaySession) {
        val pos = activeCrystals[rid] ?: return
        val r = RotationUtil.toPoint(pos.x, pos.y, pos.z)
        PacketUtil.sendMoveAtSelf(session, r.yaw, r.pitch)
        PacketUtil.sendSwingAndAttack(session, rid)
        OverlayLogger.v(TAG, "Crystal patlatıldı: rid=$rid pos=${pos}")
    }

    private fun packKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL)     shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}
