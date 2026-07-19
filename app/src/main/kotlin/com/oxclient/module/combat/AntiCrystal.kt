package com.oxclient.module.combat

import com.oxclient.core.proxy.EntityTracker
import com.oxclient.events.PacketEvent
import com.oxclient.module.*
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * AntiCrystal — sunucuya bildirdiğimiz Y pozisyonunu gerçek pozisyonundan
 * biraz daha aşağıda gösterir. Kristal patlaması hasarı sunucu tarafında
 * "sunucunun bildiğin pozisyonun" patlama merkezine olan mesafesine göre
 * hesaplandığı için, gerçekte olduğundan daha uzakta gibi görünüp daha az
 * hasar alırsın. Ekranında/gerçek konumunda hiçbir şey değişmez — sadece
 * kendi paketimizin içindeki Y değeri.
 *
 * Referans dosyadaki yaklaşımdan (yeni bir PlayerAuthInputPacket inşa edip
 * orijinali intercept ederek göndermek) farklı olarak, burada KillAura'nın
 * Head Lock fix'inde kullandığımız aynı prensip uygulanıyor: gönderilecek
 * olan GERÇEK paketin alanını doğrudan mutate ediyoruz. Daha az obje
 * allocation'ı, daha az hata payı.
 *
 * ÖNEMLİ: EntityTracker.selfY'yi burada GÜNCELLEMİYORUZ — EntityTracker'ın
 * kendi paket dinleyicisi bu paket sunucuya gitmeden önce zaten gerçek
 * (sahte olmayan) Y'yi okuyup kendi state'ine yazıyor. Eğer biz de burada
 * EntityTracker.selfY'yi sahte değere çekersek, CrystalAura/KillAura gibi
 * diğer tüm modüllerin mesafe hesapları bozulur (kendi pozisyonunu yanlış
 * bilirler). Sadece TELE OLARAK sunucuya giden değeri değiştiriyoruz.
 */
class AntiCrystal : BaseModule(
    name        = "AntiCrystal",
    category    = ModuleCategory.COMBAT,
    description = "Kristal hasarını azaltmak için sunucuya gerçek pozisyonundan daha aşağıda olduğunu bildirir"
) {
    private val yLevel = float("Y Level", 0.4f, 0.1f, 1.61f)

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (event.direction != PacketEvent.Direction.CLIENT_TO_SERVER) return
        val pkt = event.packet as? PlayerAuthInputPacket ?: return

        pkt.position = pkt.position.sub(0f, yLevel.value, 0f)
    }
}
