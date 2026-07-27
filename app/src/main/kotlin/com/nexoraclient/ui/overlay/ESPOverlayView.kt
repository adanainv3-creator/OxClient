package com.nexoraclient.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.nexoraclient.module.ModuleManager
import com.nexoraclient.module.visual.ArrayListModule
import com.nexoraclient.module.visual.ESP
import com.nexoraclient.module.visual.EnemyESP
import com.nexoraclient.module.visual.Xray

class ESPOverlayView(context: Context) : View(context) {

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private val espModule: ESP?
        get() = ModuleManager.byName("ESP") as? ESP

    private val enemyEspModule: EnemyESP?
        get() = ModuleManager.byName("EnemyESP") as? EnemyESP

    private val xrayModule: Xray?
        get() = ModuleManager.byName("Xray") as? Xray

    private val arrayListModule: ArrayListModule?
        get() = ModuleManager.byName("Mod List") as? ArrayListModule

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

        postInvalidateOnAnimation()
    }

    fun startRenderLoop() {
        postInvalidateOnAnimation()
    }
}
