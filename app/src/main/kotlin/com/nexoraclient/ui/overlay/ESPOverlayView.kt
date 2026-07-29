package com.nexoraclient.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.nexoraclient.module.ModuleManager
import com.nexoraclient.module.misc.AutoMapArt
import com.nexoraclient.module.visual.ArrayListModule
import com.nexoraclient.module.visual.ESP
import com.nexoraclient.module.visual.EnemyESP
import com.nexoraclient.module.visual.Xray

class ESPOverlayView(context: Context) : View(context) {

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // FPS FIX: modül instance'ları register-time'da sabit (ModuleManager.registerAll
    // sadece bir kez çalışıyor, sonradan modül eklenip çıkarılmıyor). Önceden bu
    // 4 property her onDraw()'da ModuleManager.byName() ile linear search +
    // capturing lambda allocate ediyordu — 60-120hz'de gereksiz CPU/GC yükü.
    // Artık sadece bir kere resolve edilip cache'leniyor.
    private val espModule: ESP? by lazy { ModuleManager.byName("ESP") as? ESP }
    private val enemyEspModule: EnemyESP? by lazy { ModuleManager.byName("EnemyESP") as? EnemyESP }
    private val xrayModule: Xray? by lazy { ModuleManager.byName("Xray") as? Xray }
    private val arrayListModule: ArrayListModule? by lazy { ModuleManager.byName("Mod List") as? ArrayListModule }
    private val autoMapArtModule: AutoMapArt? by lazy { ModuleManager.byName("AutoMapArt") as? AutoMapArt }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val esp = espModule
        if (esp != null && esp.isEnabled) {
            try { esp.render(canvas, width, height) } catch (_: Exception) {}
        }

        val enemyEsp = enemyEspModule
        if (enemyEsp != null && enemyEsp.isEnabled) {
            try { enemyEsp.render(canvas, width, height) } catch (_: Exception) {}
        }

        val xray = xrayModule
        if (xray != null && xray.isEnabled) {
            try { xray.render(canvas, width, height) } catch (_: Exception) {}
        }

        val arrayList = arrayListModule
        if (arrayList != null && arrayList.isEnabled) {
            try { arrayList.render(canvas, width, height) } catch (_: Exception) {}
        }

        val autoMapArt = autoMapArtModule
        if (autoMapArt != null && autoMapArt.isEnabled) {
            try { autoMapArt.render(canvas, width, height) } catch (_: Exception) {}
        }

        postInvalidateOnAnimation()
    }

    fun startRenderLoop() {
        postInvalidateOnAnimation()
    }
}
