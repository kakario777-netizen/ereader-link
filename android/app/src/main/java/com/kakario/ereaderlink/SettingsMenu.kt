package com.kakario.ereaderlink

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView

/** 화면 오른쪽 위 '설정' — 새 버전 확인 등 */
object SettingsMenu {
    fun button(a: Activity): TextView = TextView(a).apply {
        text = "설정"
        textSize = 17f
        setTextColor(Color.BLACK)
        val p = (12 * a.resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, p / 2)
        setOnClickListener { v -> show(a, v) }
    }

    private fun show(a: Activity, anchor: View) {
        val m = PopupMenu(a, anchor)
        m.menu.add(0, 1, 0, "새 버전 확인")
        m.menu.add(0, 2, 1, "버전 정보")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    say(a, "새 버전 확인하는 중…")
                    Updater.checkNow(a) { msg -> a.runOnUiThread { say(a, msg) } }
                }
                2 -> say(a, "이북 링크  빌드 #${Updater.currentBuild(a)}\n기기: ${FileServer.deviceName()}")
            }
            true
        }
        m.show()
    }

    private var dlg: AlertDialog? = null

    private fun say(a: Activity, t: String) {
        if (a.isFinishing) return
        runCatching { dlg?.dismiss() }
        dlg = AlertDialog.Builder(a).setMessage(t).setPositiveButton("확인", null).show()
    }
}
