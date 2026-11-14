package com.kakario.ereaderlink

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * 화면이 위쪽 시계줄·아래쪽 내비게이션 바 밑으로 파고들지 않게,
 * 그 높이만큼 안쪽 여백을 더해 줌 (안드로이드 15부터는 기본이 '전체 화면'이라서 필요)
 */
object Insets {
    fun apply(a: Activity, root: View) {
        val w = a.window
        @Suppress("DEPRECATION")
        run {
            w.statusBarColor = Color.WHITE
            w.navigationBarColor = Color.WHITE
        }
        // 흰 바탕에 맞게 시계·아이콘을 검은색으로
        if (Build.VERSION.SDK_INT >= 30) {
            root.post {
                w.insetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            }
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        val l0 = root.paddingLeft; val t0 = root.paddingTop; val r0 = root.paddingRight; val b0 = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(l0 + i.left, t0 + i.top, r0 + i.right, b0 + i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(l0 + ins.systemWindowInsetLeft, t0 + ins.systemWindowInsetTop,
                    r0 + ins.systemWindowInsetRight, b0 + ins.systemWindowInsetBottom)
            }
            ins
        }
    }
}
