package com.wingua.spwsend.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage

/**
 * 采样宿主窗口左侧导航栏的空白区域，取中位色作为面板底色。
 *
 * 宿主材质由系统合成后已经显示在屏幕上，直接采样比从壁纸反推更准确，
 * 且天然适配任意用户的壁纸、主题与材质。
 *
 * 采样范围固定为 24×64 像素，仅在面板打开时执行一次，不落盘、不外传。
 * 采样失败或区域颜色不稳定时返回 null，由调用方回退到壁纸取色方案。
 */
internal object HostSurfaceSampler {

    fun sample(window: Window?, panel: Rect): Color? {
        if (window == null || !window.isShowing) return null
        if (panel.width <= 0f || panel.height <= 0f) return null
        return runCatching {
            val origin = window.locationOnScreen
            val centerY = origin.y + (panel.top + panel.height * 0.65f).toInt()
            val bounds = Rectangle(origin.x + 4, centerY - 32, 24, 64)
            medianColor(Robot().createScreenCapture(bounds))
        }.getOrNull()
    }

    private fun medianColor(image: BufferedImage): Color? {
        val count = image.width * image.height
        if (count == 0) return null
        val particles = Array(count) { image.getRGB(it % image.width, it / image.width) }
        val reds = IntArray(count) { (particles[it] shr 16) and 0xFF }.apply { sort() }
        val greens = IntArray(count) { (particles[it] shr 8) and 0xFF }.apply { sort() }
        val blues = IntArray(count) { particles[it] and 0xFF }.apply { sort() }
        val spread = maxOf(
            reds[count * 9 / 10] - reds[count / 10],
            greens[count * 9 / 10] - greens[count / 10],
            blues[count * 9 / 10] - blues[count / 10]
        )
        // 采样区混入图标或文字时颜色起伏过大，放弃本次采样。
        if (spread > 48) return null
        return Color(
            reds[count / 2] / 255f,
            greens[count / 2] / 255f,
            blues[count / 2] / 255f
        )
    }
}
