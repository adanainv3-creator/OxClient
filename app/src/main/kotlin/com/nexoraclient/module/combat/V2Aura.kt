package com.rubidiumclient.module.combat

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.core.relay.RubidiumRelaySession
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.events.PacketEventBus
import com.rubidiumclient.module.*
import com.rubidiumclient.module.social.isFriendEntity
import com.rubidiumclient.utils.CritLock
import com.rubidiumclient.utils.MathUtil
import com.rubidiumclient.utils.PacketUtil
import com.rubidiumclient.utils.RotationUtil
import com.rubidiumclient.utils.V2AuraUtil
import com.rubidiumclient.utils.WorldBlockTracker
import kotlinx.coroutines.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * V2Aura — hem bizim hem hedefin çok hızlı hareket edip yer değiştirebildiği
 * (strafe/circle, knockback, elytra vb.) durumlar için tasarlanmış KillAura
 * varyantı.
 *
 * KillAura/KillAuraPro'dan asıl farkı: rotasyon ve vuruş noktası sadece
 * hedefin kendi hızına göre değil, RELATİF hıza göre hesaplanıyor
 * (V2AuraUtil.interceptPosition) ve iki taraf birbirini geçecekse tahmin
 * süresi otomatik olarak o geçiş anına kırpılıyor — böylece hızlı yer
 * değiştirmede "ters pozisyona" kilitlenme/rotasyon atma önleniyor. Ayrıca
 * her saldırı anında hedef EntityTracker'dan TEKRAR, en güncel haliyle
 * çekiliyor (tick() sırasında cache'lenmiş eski pozisyona güvenilmiyor).
 *
 * Yeni özellikler:
 * - Bypass Hurt Time: Hedefin hurtTime > 0 olsa bile saldırmaya devam eder.
 * - Packets Per Tick: Her tick'te aynı hedefe 1-25 arası saldırı paketi gönderir.
 */
class V2Aura : BaseModule(
    name        = "V2Aura",
    category    = ModuleCategory.COMBAT,
    description = "Relatif hız/intercept tahmini ile hızlı yer değiştiren hedeflere karşı KillAura"
), PacketEventBus.PacketListener {

    enum class PriorityMode { Distance, LowestHealth, Direction }

    companion object {
        private const val TICK_INTERVAL_MS = 5L
        // KillAura/KillAuraPro'daki aynı fix: hedef yokken/CPS kapısı açık
        // kalınca tarama her tick'te (200Hz) çalışıp lag'e sebep oluyordu.
        // Tarama en fazla bu aralıkla yapılıp cache'leniyor.
        private const val SCAN_INTERVAL_MS = 50L
        private const val HEAD_LOCK_SCAN_INTERVAL_MS = 50L
    }

    private val cpsMin          = int  ("CPS Min",          28,    1,    30)
    private val cpsMax          = int  ("CPS Max",          30,    1,    30)
    private val range           = float("Range",            10f,   1f,   10f)
    private val fov             = int  ("FOV",              360,   30,   360)
    private val maxTargets      = int  ("Max Targets",      1,     1,    10)
    // Hedefi ne kadar ileriye tahmin edebileceğimizin ÜST sınırı — asıl
    // gecikme, hedef ve biz birbirimizi geçecekse V2AuraUtil tarafından
    // otomatik olarak bunun altına kırpılır.
    private val maxPredictDelay = float("Max Predict Delay", 0.15f, 0.02f, 0.4f)
    private val priorityMode    = enum ("Priority",         PriorityMode.LowestHealth)
    private val headLock        = bool ("Head Lock",        true)
    private val headLockSmooth  = float("Head Lock Smooth", 1f,    0.01f, 1f)
    private val alwaysCrit      = bool ("Always Crit",      true)
    private val ignoreFriends   = bool ("Ignore Friends",   true)
    private val autoWeapon      = bool ("AutoWeapon",       true)
    // AutoWeapon artık InventoryHelper'ın envanter taramasına/konfigürasyonuna
    // bağımlı değil — kılıcın ve tridentin hangi hotbar slotunda olduğu
    // doğrudan burada belirtiliyor. InventoryHelper modülü kapalı olsa bile
    // ya da hotbar düzeni onun varsayımlarıyla uyuşmasa bile çalışır.
    private val swordSlot       = int  ("Sword Slot",       0,     0,    8)
    private val tridentSlot     = int  ("Trident Slot",     1,     0,    8)
    private val shortcut        = bool ("Shortcut",         false)

    // Yeni ayarlar
    private val bypassHurtTime  = bool ("Bypass Hurt Time", true)
    private val packetsPerTick  = int  ("Packets Per Tick", 1,     1,    25)

    @Volatile private var lastAttackMs = 0L
    @Volatile private var headLockYaw = 0f
    @Volatile private var headLockPitch = 0f
    @Volatile private var cachedHeadLockTarget: EntityTracker.TrackedEntity? = null
    @Volatile private var lastHeadLockScanMs = 0L
    @Volatile private var lastScanMs = 0L
    @Volatile private var cachedTargets: List<EntityTracker.TrackedEntity> = emptyList()
    private var tickJob: Job? = null

    override fun onEnable() {
        super.onEnable()
        lastAttackMs = 0L
        headLockYaw = EntityTracker.selfYaw
        headLockPitch = EntityTracker.selfPitch
        cachedHeadLockTarget = null
        lastHeadLockScanMs = 0L
        lastScanMs = 0L
        cachedTargets = emptyList()
        PacketEventBus.register(this)
        tickJob = scope.launch { tickLoop() }
    }

    override fun onDisable() {
        tickJob?.cancel()
        PacketEventBus.unregister(this)
        super.onDisable()
    }

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled || !headLock.value) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return
        applyHeadLock(event, pkt)
    }

    private fun applyHeadLock(event: PacketEvent, pkt: PlayerAuthInputPacket) {
        val now = System.currentTimeMillis()
        if (cachedHeadLockTarget == null || now - lastHeadLockScanMs >= HEAD_LOCK_SCAN_INTERVAL_MS) {
            cachedHeadLockTarget = findHeadLockTarget()
            lastHeadLockScanMs = now
        }
        // FIX: cache'lenmiş TrackedEntity referansı bir önceki taramadan kalma
        // olabilir — hızlı yer değiştirmede eski pozisyona kilitlenmemek için
        // canlı entity'yi runtimeId üzerinden tekrar çekiyoruz.
        val target = cachedHeadLockTarget?.let { EntityTracker.getById(it.runtimeId) } ?: return

        val strike = V2AuraUtil.computeStrikePoint(target, maxPredictDelay.value)
        val targetRot = RotationUtil.toPoint(strike.x, strike.y, strike.z)
        val smoothFactor = headLockSmooth.value.coerceIn(0.01f, 1f)

        val newYaw = smoothYaw(headLockYaw, targetRot.yaw, smoothFactor)
        val newPitch = smoothPitch(headLockPitch, targetRot.pitch, smoothFactor)
        headLockYaw = newYaw
        headLockPitch = newPitch

        pkt.rotation = Vector3f.from(newPitch, newYaw, newYaw)
        EntityTracker.selfYaw = newYaw
        EntityTracker.selfPitch = newPitch

        // KRİTİK FIX: cancelAndReplace çağrılmazsa relay ham wire byte'larını
        // gönderiyor, bu mutation server'a hiç ulaşmıyordu (bkz. KillAura.kt).
        event.cancelAndReplace(pkt)
    }

    private fun smoothYaw(current: Float, target: Float, factor: Float): Float {
        var diff = target - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val result = current + (diff * factor)
        var normalized = result % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }

    private fun smoothPitch(current: Float, target: Float, factor: Float): Float {
        val diff = target - current
        return (current + (diff * factor)).coerceIn(-90f, 90f)
    }

    private fun findHeadLockTarget(): EntityTracker.TrackedEntity? {
        val candidates = EntityTracker.getEntitiesInRange(range.value * 1.5f)
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { fov.value >= 360 || EntityTracker.angleToEntity(it) <= fov.value / 2f }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
        return sortByPriority(candidates).firstOrNull()
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (isEnabled) tick()
            delay(TICK_INTERVAL_MS)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val delayMs = MathUtil.cpsToDelayMs(cpsMin.value, cpsMax.value)
        if (now - lastAttackMs < delayMs) return

        if (now - lastScanMs >= SCAN_INTERVAL_MS) {
            cachedTargets = selectTargets()
            lastScanMs = now
        }
        val targets = cachedTargets
        if (targets.isEmpty()) return

        lastAttackMs = now
        val session = PacketEventBus.currentSession ?: return

        val ppTick = packetsPerTick.value
        targets.take(maxTargets.value).forEach { cachedTarget ->
            val rid = cachedTarget.runtimeId
            // FIX: eskiden her deneme (ppTick) için AYRI coroutine paralel
            // başlatılıyordu. Sadece ilk denemenin crit'i var ve injectCrit()
            // delay(10L)+delay(5L) ile ZAMANLI paket sırası gerektiriyor —
            // paralel coroutine'lerde scheduler garantisi olmadığından, index=1..N
            // denemeleri crit'in düşüş paketlerinden ÖNCE server'a ulaşabiliyordu,
            // bu da crit'in bazen hiç tutmamasına sebep oluyordu. Artık aynı
            // hedefin tüm ppTick denemeleri TEK coroutine içinde SIRALI çalışıyor
            // (farklı hedefler için ayrı coroutine'ler yine paralel kalıyor).
            scope.launch {
                repeat(ppTick) { index ->
                    attack(session, rid, withCrit = index == 0)
                }
            }
        }
    }

    private fun selectTargets(): List<EntityTracker.TrackedEntity> {
        val candidates = EntityTracker.getEntitiesInRange(range.value)
            .filter { it.isPlayer && it.runtimeId != EntityTracker.selfRuntimeId }
            .filter { fov.value >= 360 || EntityTracker.angleToEntity(it) <= fov.value / 2f }
            .let { if (ignoreFriends.value) it.filterNot { e -> e.isFriendEntity } else it }
            .let { if (!bypassHurtTime.value) it.filter { it.hurtTime <= 0 } else it }
        return sortByPriority(candidates)
    }

    private fun sortByPriority(candidates: List<EntityTracker.TrackedEntity>) = when (priorityMode.value) {
        PriorityMode.Distance     -> candidates.sortedBy { EntityTracker.distanceTo(it) }
        PriorityMode.LowestHealth -> candidates.sortedBy { it.health }
        PriorityMode.Direction    -> candidates.sortedBy { EntityTracker.angleToEntity(it) }
    }

    private suspend fun attack(session: RubidiumRelaySession, targetRid: Long, withCrit: Boolean = true) {
        // FIX: coroutine launch edildikten sonra scheduler gecikmesi olabilir —
        // hedefi runtimeId üzerinden TEKRAR, en güncel haliyle çekiyoruz.
        // Aksi halde tick() anındaki eski pozisyon kullanılır ve hızlı yer
        // değiştiren bir hedefte saldırı/rotasyon ters pozisyona gider.
        val target = EntityTracker.getById(targetRid) ?: return

        if (withCrit && alwaysCrit.value) {
            CritLock.tryRun { V2AuraUtil.injectCrit(session) }
        }

        val strike = V2AuraUtil.computeStrikePoint(target, maxPredictDelay.value)
        val rot = RotationUtil.toPoint(strike.x, strike.y, strike.z)
        PacketUtil.sendMoveAtSelf(session, rot.yaw, rot.pitch, onGround = true)

        PacketUtil.sendSwing(session)

        val hotbarSlot = resolveWeaponSlot()
        PacketUtil.sendAttack(session, targetRid, hotbarSlot, strike)
    }

    // ---------- Silah slotu seçimi ----------
    // AutoWeapon kapalıysa dokunulmaz (mevcut seçili slot kullanılır).
    // Açıksa: yağmur/suda Trident Slot, aksi halde Sword Slot kullanılır.
    // Artık InventoryHelper'a ya da envanterde canlı "_sword"/"trident"
    // identifier taramasına hiç bakmıyor — direkt kullanıcının belirttiği
    // sabit slot numarasını döndürüyor.
    private fun resolveWeaponSlot(): Int {
        val fallback = EntityTracker.selfHotbarSlot.coerceIn(0, 8)
        if (!autoWeapon.value) return fallback

        val wet = EntityTracker.selfIsRaining || WorldBlockTracker.isPlayerInWater()
        return if (wet) tridentSlot.value.coerceIn(0, 8) else swordSlot.value.coerceIn(0, 8)
    }
}