package com.kakario.ereaderlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 켜 두기만 하면 되는 화면 — 흰 바탕, 검은 글자 (이잉크용) */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var detail: TextView
    private lateinit var doing: TextView
    private lateinit var permBtn: Button
    private lateinit var updBtn: Button
    private lateinit var updText: TextView
    private var lastText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 이 화면이 켜져 있는 동안은 화면이 꺼지지 않게 (꺼져도 연결은 유지됨)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = dp(28)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad * 2, pad, pad)
        }
        fun label(size: Float, bold: Boolean = false) = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(30f, true).apply { text = "이북 링크" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(SettingsMenu.button(this@MainActivity))
        }
        root.addView(head)
        root.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, dp(2)))
        state = label(24f, true); root.addView(state)
        detail = label(18f); root.addView(detail)
        doing = label(18f); root.addView(doing)

        permBtn = button("모든 파일 접근 허용하기") { askPermission() }
        root.addView(permBtn)
        root.addView(button("다른 기기 열기 (폰 → 이북리더)") {
            startActivity(Intent(this, BrowseActivity::class.java))
        })
        updBtn = button("업데이트") {
            if (Updater.canInstall(this)) Updater.downloadAndInstall(this)
            else { Updater.status = "'이 출처 허용'을 켜고 돌아오면 자동으로 업데이트돼요"; Updater.downloadAndInstall(this) }
        }
        root.addView(updBtn)
        updText = label(16f)
        root.addView(updText)
        root.addView(button("끄고 나가기") {
            startService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_STOP))
            finishAndRemoveTask()
        })
        root.addView(label(15f).apply {
            text = "이 화면을 켜 두고, PC에서 '이북 링크' 프로그램을 켜면 같은 와이파이에서 자동으로 연결돼요.\n" +
                   "다른 앱으로 넘어가도 연결은 계속 유지돼요."
            setPadding(0, dp(30), 0, 0)
        })
        Insets.apply(this, root)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (Build.VERSION.SDK_INT < 30 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        startForegroundService(Intent(this, LinkService::class.java))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 18f
        setTextColor(Color.BLACK)
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        gravity = Gravity.CENTER
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!LinkState.running) startForegroundService(Intent(this, LinkService::class.java))
        Updater.check(this)
        // '이 출처 허용'을 켜고 돌아온 경우 바로 이어서 업데이트
        if (Updater.available != null && !Updater.busy && Updater.canInstall(this) && !LinkState.connected)
            Updater.downloadAndInstall(this)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun refresh() {
        val allOk = Storage.canAccessAll()
        val upd = Updater.available
        updBtn.visibility = if (upd != null && !Updater.busy) View.VISIBLE else View.GONE
        if (upd != null) {
            val t = "새 버전으로 업데이트 (빌드 #${upd.build})"
            if (updBtn.text.toString() != t) updBtn.text = t
        }
        val us = Updater.status ?: ""
        if (updText.text.toString() != us) updText.text = us
        updText.visibility = if (us.isEmpty()) View.GONE else View.VISIBLE
        permBtn.visibility = if (allOk) View.GONE else View.VISIBLE

        val s = when {
            LinkState.error != null && !LinkState.connected -> "문제가 생겼어요"
            !allOk -> "권한이 필요해요"
            LinkState.connected -> "PC와 연결됨"
            else -> "PC를 기다리는 중"
        }
        val ips = Discovery.localIps()
        val d = buildString {
            if (!allOk) append("아래 버튼을 눌러 '모든 파일 접근'을 켜 주세요.\n")
            if (LinkState.connected) append("PC: ${LinkState.pcAddress}\n")
            append("이 기기: ${FileServer.deviceName()}  (빌드 #${Updater.currentBuild(this@MainActivity)})\n")
            append(if (ips.isEmpty()) "와이파이가 연결돼 있지 않아요" else "IP: ${ips.joinToString(", ")}")
            LinkState.error?.let { append("\n$it") }
        }
        val act = LinkState.activity
        val g = if (act != null && System.currentTimeMillis() - LinkState.activityAt < 15000) act else ""
        // 이잉크는 화면이 바뀔 때마다 깜빡이므로, 내용이 같으면 다시 그리지 않음
        val all = s + d + g
        if (all == lastText) return
        lastText = all
        state.text = s
        detail.text = d
        doing.text = g
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
