package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PlacementUtil
import com.rubidiumclient.utils.WorldBlockTracker
import org.cloudburstmc.math.vector.Vector3i
import kotlin.math.floor

// NOT: BedAura'nın TERSİ — respawn anchor Nether'e özgü bir blok, bu yüzden
// Nether'de normal çalışır (sadece spawn noktası ayarlar), ama Nether DIŞINDA
// (Overworld veya End) şarjlıyken kullanılırsa patlar. selfDimension'a göre
// (0=Overworld, 1=Nether, 2=End) Nether'de tetiklemiyoruz.
//
// Akış BedAura/PistonAura ile aynı Attempt/tick deseni ama iki aşamalı:
//   1) PLACED  — anchor hedefin ayak hizasına (BedAura'daki gibi "below" bloğun
//                üstüne) yerleştirilir.
//   2) CHARGED — bir tık gecikmeden sonra elde glowstone ile anchor'a "kullanım"
//                gönderilir (PlacementUtil.sendPlacementUseRaw — item'ı bloğa
//                değil bloğUN ÜZERİNE kullanmak yerine bizzat anchor'ın kendi
//                pozisyonuna göndererek şarj ekletiyoruz, yeni bir util
//                gerekmiyor, aynı ITEM_USE transaction'ı işi görüyor).
//   3) Son olarak elde item YOKKEN anchor'a interact gönderilir
//      (PlacementUtil.sendInteract, BedAura'nın yatağı tetiklemesiyle aynı
//      çağrı) — Nether dışında şarjlı anchor'a dokunmak onu patlatır.
class AnchorAura : BaseModule(
    name        = "AnchorAura",
    category    = ModuleCategory.COMBAT,
    description = "Hedefin yanına respawn anchor koyup şarj edip Nether dışında patlatır"
) {
    companion object {
        private const val TICK_MS           = 150L
        private const val ACTIVATE_DELAY_MS = 200L

        private val NON_SOLID = setOf(
            "minecraft:air", "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava",
            "minecraft:void_air", "minecraft:cave_air"
        )

        private const val GLOWSTONE = "minecraft:glowstone"
    }

    private val targetRange      = int   ("Target Range",       8,    2,   16)
    private val friendSkip       = bool  ("Friend Skip",        true)
    private val placeRange       = int   ("Place Range",        6,    2,   12)
    private val anchorIdentifier = string("Anchor",             "minecraft:respawn_anchor")
    private val noSwitch         = bool  ("No Switch",          true)
    private val cooldownMs       = int   ("Cooldown (ms)",      1500, 200, 5000)
    private val requireNotNether = bool  ("Require Not-Nether", true)
    private val shortcut         = bool  ("Shortcut",           false)

    private enum class Phase { PLACED, CHARGED }
    private data class Attempt(val anchorPos: Vector3i, val phase: Phase, val armedAt: Long)

    @Volatile private var active: Attempt? = null
    @Volatile private var lastAttemptMs = 0L
    @Volatile private var tickJob: kotlinx.coroutines.Job? = null

    override fun onEnable() {
        super.onEnable()
        active = null
        tickJob?.cancel()
        tickJob = launchTickLoop(TICK_MS) { tick() }
    }

    override fun onDisable() {
        tickJob?.cancel(); tickJob = null
        super.onDisable()
        active = null
    }

    private fun tick() {
        // Nether'de anchor patlamıyor (spawn noktası ayarlamaktan başka bir işe
        // yaramaz) — BedAura'nın Nether/End kontrolünün tam tersi.
        if (requireNotNether.value && EntityTracker.selfDimension == 1) return
        val session = PacketEventBus.currentSession ?: return

        active?.let { a ->
            if (System.currentTimeMillis() - a.armedAt < ACTIVATE_DELAY_MS) return

            when (a.phase) {
                Phase.PLACED -> {
                    val glow = PlacementUtil.prepareItemForUse(session, GLOWSTONE, noSwitch.value)
                    if (glow == null) {
                        // Elde/envanterde glowstone yoksa şarj edemeyiz, denemeyi bırak.
                        active = null
                        return
                    }
                    val ok = PlacementUtil.sendPlacementUseRaw(session, glow, a.anchorPos, anchorIdentifier.value)
                    PlacementUtil.revert(session, glow)
                    active = if (ok) Attempt(a.anchorPos, Phase.CHARGED, System.currentTimeMillis()) else null
                }
                Phase.CHARGED -> {
                    PlacementUtil.sendInteract(session, a.anchorPos, anchorIdentifier.value)
                    active = null
                }
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < cooldownMs.value) return

        val target = nearestEnemy() ?: return
        attemptPlace(session, target)
    }

    private fun nearestEnemy(): EntityTracker.TrackedEntity? {
        if (EntityTracker.selfRuntimeId <= 0L) return null
        val sx = EntityTracker.selfX; val sy = EntityTracker.selfY; val sz = EntityTracker.selfZ
        return EntityTracker.getPlayers(targetRange.value.toFloat())
            .asSequence()
            .filter { it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { !friendSkip.value || !it.isFriendEntity }
            .minByOrNull { MathUtil.dist3sq(it.x, it.y, it.z, sx, sy, sz) }
    }

    private fun attemptPlace(session: RubidiumRelaySession, target: EntityTracker.TrackedEntity) {
        val tx = floor(target.x).toInt()
        val ty = floor(target.y).toInt()
        val tz = floor(target.z).toInt()

        val hasData = WorldBlockTracker.hasAnyTerrainData()
        val anchorPos = Vector3i.from(tx, ty, tz)
        val spotFree = !hasData || (WorldBlockTracker.getBlockIdentifier(anchorPos.x, anchorPos.y, anchorPos.z) ?: "minecraft:air") in NON_SOLID
        if (!spotFree) return

        val below = WorldBlockTracker.getBlockIdentifier(anchorPos.x, anchorPos.y - 1, anchorPos.z)
        if (hasData && (below == null || below in NON_SOLID)) return

        val cx = anchorPos.x + 0.5f; val cy = anchorPos.y + 0.5f; val cz = anchorPos.z + 0.5f
        if (MathUtil.dist3(cx, cy, cz, EntityTracker.selfX, EntityTracker.selfY + 1.62f, EntityTracker.selfZ) > placeRange.value) return

        lastAttemptMs = System.currentTimeMillis()

        val prepared = PlacementUtil.prepareItemForUse(session, anchorIdentifier.value, noSwitch.value) ?: return
        val below2 = Vector3i.from(anchorPos.x, anchorPos.y - 1, anchorPos.z)
        val ok = PlacementUtil.sendPlacementUseRaw(session, prepared, below2, below ?: "minecraft:obsidian", blockFace = 1)
        PlacementUtil.revert(session, prepared)
        if (!ok) return

        active = Attempt(anchorPos, Phase.PLACED, System.currentTimeMillis())
    }
}
