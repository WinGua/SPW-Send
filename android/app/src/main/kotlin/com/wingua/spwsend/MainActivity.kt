package com.wingua.spwsend

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.flutter.embedding.android.FlutterActivity

class MainActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFullscreen()
        // 启动后台保活服务（前台服务 + 常驻通知），保持局域网接收服务在线。
        SyncKeepAliveService.start(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    private fun applyFullscreen() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // 1) 内容铺满到状态栏/挖孔区域（edge-to-edge）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 2) 程序化指定挖孔显示模式。关键点有二：
        //    a) edge-to-edge / 隐藏状态栏时用 ALWAYS（而非 SHORT_EDGES），
        //       因为 SHORT_EDGES 在窗口处于全屏/沉浸态时会被系统当作不铺满挖孔；
        //    b) 必须取副本、改字段、再整体回写 setAttributes，直接改字段不生效。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.attributes = lp
        }
        // 3) 现代 WindowInsetsController 隐藏状态栏（不使用 deprecated 的
        //    systemUiVisibility/IMMERSIVE_STICKY，避免与国产系统挖孔屏冲突导致黑屏）
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }
}
