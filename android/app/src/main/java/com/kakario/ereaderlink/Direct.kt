package com.kakario.ereaderlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * 와이파이 다이렉트 — 공유기·핫스팟 없이 두 기기를 직접 와이파이로 연결.
 *  - 이북리더(받는 쪽): '다이렉트 켜기'로 정해진 이름의 그룹을 만듦
 *  - 폰: '와이파이 다이렉트로 연결'로 그 그룹에 바로 들어감 (확인 창 없이)
 *  - 다 쓰면(화면을 나가거나, 10분 동안 안 쓰면) 그룹을 없애서 원래 와이파이 상태로 돌아감
 */
object Direct {
    // 두 기기가 서로 알고 있는 고정 이름·비밀번호 (같은 앱끼리만 씀)
    private const val SSID = "DIRECT-eL-EreaderLink"
    private const val PASS = "ereaderlink2026"
    const val OWNER_IP = "192.168.49.1"

    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile var hosting = false          // 이북리더 쪽: 그룹을 켜 둔 상태
    @Volatile var joined = false           // 폰 쪽: 그룹에 들어간 상태
    @Volatile var hostStatus: String? = null
    @Volatile private var hostStarted = 0L

    val supported get() = Build.VERSION.SDK_INT >= 29

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermission(ctx: Context) = permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun wifiOn(ctx: Context): Boolean =
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled

    private fun init(ctx: Context): Boolean {
        if (mgr == null) {
            mgr = ctx.applicationContext.getSystemService(WifiP2pManager::class.java)
            ch = mgr?.initialize(ctx.applicationContext, Looper.getMainLooper(), null)
        }
        return mgr != null && ch != null
    }

    private fun reason(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "이 기기는 와이파이 다이렉트를 지원하지 않아요"
        WifiP2pManager.BUSY -> "와이파이가 바빠요. 잠시 뒤 다시 해 주세요"
        else -> "와이파이 다이렉트 오류 ($code) — 와이파이와 위치(GPS)가 켜져 있는지 확인해 주세요"
    }

    private fun config() = WifiP2pConfig.Builder()
        .setNetworkName(SSID)
        .setPassphrase(PASS)
        .build()

    // ───────── 이북리더 쪽 ─────────

    @SuppressLint("MissingPermission")
    fun startHost(ctx: Context, done: (String) -> Unit) {
        if (!supported) { done("안드로이드 10 이상에서만 돼요"); return }
        if (!init(ctx)) { done(reason(WifiP2pManager.P2P_UNSUPPORTED)); return }
        val m = mgr!!; val c = ch!!
        hostStatus = "다이렉트 켜는 중…"
        m.removeGroup(c, null)
        main.postDelayed({
            m.createGroup(c, config(), object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    hosting = true
                    hostStarted = System.currentTimeMillis()
                    hostStatus = "다이렉트 켜짐 — 폰에서 '와이파이 다이렉트로 연결'을 눌러 주세요"
                    done(hostStatus!!)
                    watchIdle(ctx.applicationContext)
                }
                override fun onFailure(r: Int) {
                    hosting = false
                    hostStatus = reason(r)
                    done(hostStatus!!)
                }
            })
        }, 600)
    }

    fun stopHost(ctx: Context) {
        if (!hosting) return
        hosting = false
        hostStatus = null
        if (init(ctx)) mgr!!.removeGroup(ch!!, null)
    }

    /** 10분 동안 아무도 안 쓰면 알아서 끄고 원래 와이파이로 */
    private fun watchIdle(ctx: Context) {
        thread(isDaemon = true) {
            while (hosting) {
                Thread.sleep(30_000)
                val now = System.currentTimeMillis()
                val lastUse = maxOf(LinkState.lastSeen, hostStarted)
                if (hosting && now - lastUse > 10 * 60_000L) {
                    main.post { stopHost(ctx) }
                    LinkState.doing("다이렉트를 10분 동안 안 써서 껐어요")
                }
            }
        }
    }

    // ───────── 폰 쪽 ─────────

    @SuppressLint("MissingPermission")
    fun join(ctx: Context, done: (host: String?, error: String?) -> Unit) {
        if (!supported) { done(null, "안드로이드 10 이상에서만 돼요"); return }
        if (!init(ctx)) { done(null, reason(WifiP2pManager.P2P_UNSUPPORTED)); return }
        val m = mgr!!; val c = ch!!
        m.connect(c, config(), object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                joined = true
                // 그룹에 다 들어갈 때까지 기다림 (최대 30초)
                var tries = 0
                fun poll() {
                    m.requestConnectionInfo(c) { info ->
                        when {
                            info != null && info.groupFormed -> done(info.groupOwnerAddress?.hostAddress ?: OWNER_IP, null)
                            ++tries > 30 -> { leave(ctx); done(null, "이북리더를 찾지 못했어요. 이북리더에서 '다이렉트 켜기'를 먼저 눌러 주세요") }
                            else -> main.postDelayed({ poll() }, 1000)
                        }
                    }
                }
                poll()
            }
            override fun onFailure(r: Int) = done(null, reason(r))
        })
    }

    /** 원래 와이파이 상태로 되돌리기 */
    fun leave(ctx: Context) {
        if (!joined) return
        joined = false
        if (init(ctx)) {
            mgr!!.cancelConnect(ch!!, null)
            mgr!!.removeGroup(ch!!, null)
        }
    }

    fun askPermission(a: Activity, code: Int) = a.requestPermissions(permissions(), code)
}
