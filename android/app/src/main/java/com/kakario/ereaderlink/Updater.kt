package com.kakario.ereaderlink

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 앱 안 업데이트 (이북리더 앱과 같은 방식).
 * GitHub 최신 빌드를 확인해 받고, 앱 안에서 바로 설치 → 앱이 잠깐 꺼졌다가 다시 켜짐.
 * 처음 한 번만 '이 출처 허용'과 '업데이트할까요?'가 나오고, 다음부터는 묻지 않음 (안드로이드 12 이상).
 */
object Updater {
    private const val API = "https://api.github.com/repos/kakario777-netizen/ereader-link/releases/tags/latest"

    data class Release(val build: Int, val url: String, val size: Long)

    @Volatile var available: Release? = null
    @Volatile var status: String? = null      // 화면에 보여줄 진행 상황
    @Volatile var busy = false

    fun currentBuild(ctx: Context): Int = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    @Volatile private var lastCheck = 0L

    /**
     * 새 버전 확인 → 있으면 바로 받아서 설치까지 자동으로 (버튼 안 눌러도 됨).
     * 단, PC·폰과 파일을 주고받는 중이면 끝날 때까지 미룸.
     * '이 출처 허용'이 아직 꺼져 있으면 화면에 업데이트 버튼만 띄움 (처음 한 번 허용 필요).
     */
    fun check(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (busy || (!force && now - lastCheck < 10 * 60_000L)) return
        lastCheck = now
        val app = ctx.applicationContext
        thread(isDaemon = true) {
            runCatching {
                val r = fetchLatest()
                available = if (r != null && r.build > currentBuild(app)) r else null
                if (available != null && canInstall(app) && !LinkState.connected) downloadAndInstall(app)
            }
        }
    }

    /** 설정 메뉴의 '새 버전 확인' — 결과를 알려 주고, 새 버전이면 바로 업데이트 */
    fun checkNow(ctx: Context, done: (String) -> Unit) {
        val app = ctx.applicationContext
        if (busy) { done(status ?: "업데이트 중이에요"); return }
        thread(isDaemon = true) {
            val cur = currentBuild(app)
            val r = runCatching { fetchLatest() }.getOrNull()
            when {
                r == null -> done("새 버전을 확인하지 못했어요. 인터넷 연결을 확인해 주세요.")
                r.build <= cur -> { available = null; done("최신 버전이에요 (빌드 #$cur)") }
                else -> {
                    available = r
                    done("새 버전 #${r.build}을(를) 받아서 업데이트해요")
                    downloadAndInstall(ctx)
                }
            }
        }
    }

    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun downloadAndInstall(ctx: Context) {
        val r = available ?: return
        if (busy) return
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            status = "'이 출처 허용'을 켠 뒤 다시 눌러 주세요"
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        busy = true
        thread(isDaemon = true) {
            try {
                val dir = File(app.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "ereader-link-${r.build}.apk")
                status = "받는 중 0%"
                fetch(r.url, file, r.size) { done, total ->
                    if (total > 0) status = "받는 중 ${done * 100 / total / 5 * 5}%"
                }
                status = "설치 중… 앱이 잠깐 꺼졌다가 다시 켜져요"
                install(app, file)
            } catch (e: Exception) {
                status = "업데이트 실패: ${e.message ?: e.javaClass.simpleName}"
                busy = false
            }
        }
    }

    private fun install(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("ereader-link.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 256 * 1024)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id,
                Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags)
            session.commit(pi.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ereader-link-app")
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        return c
    }

    private fun fetchLatest(): Release? {
        val c = open(API)
        if (c.responseCode !in 200..299) { c.disconnect(); return null }
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val build = Regex("#(\\d+)").find(json.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) return Release(build, a.getString("browser_download_url"), a.optLong("size"))
        }
        return null
    }

    /** 끊겨도 받은 데까지 두고 나머지만 다시 받기 */
    private fun fetch(url: String, dest: File, expected: Long, onProgress: (Long, Long) -> Unit) {
        dest.delete()
        var total = expected
        var last: Exception? = null
        for (attempt in 1..30) {
            val done = if (dest.exists()) dest.length() else 0L
            if (total > 0 && done >= total) return
            var c: HttpURLConnection? = null
            try {
                c = open(url).apply { readTimeout = 15_000; if (done > 0) setRequestProperty("Range", "bytes=$done-") }
                val code = c.responseCode
                if (code !in 200..299) throw IOException("응답 $code")
                val append = code == 206
                if (!append && done > 0) dest.delete()
                val len = c.contentLengthLong
                if (len > 0) total = if (append) done + len else len
                c.inputStream.use { input ->
                    FileOutputStream(dest, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = if (append) done else 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            onProgress(got, total)
                        }
                    }
                }
                if (total <= 0 || dest.length() >= total) return
            } catch (e: Exception) {
                last = e
                Thread.sleep(if (attempt < 5) 1_000L else 3_000L)
            } finally {
                c?.disconnect()
            }
        }
        throw IOException("끝까지 받지 못했어요 (${last?.message ?: "연결 끊김"})")
    }
}

/** 설치 결과: 안드로이드가 확인을 원하면 확인 창을 띄움 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                   else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        if (st != PackageInstaller.STATUS_SUCCESS) {
            Updater.status = "설치하지 못했어요: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: st}"
            Updater.busy = false
        }
    }
}

/** 새 버전으로 바뀌면 바로 다시 켜기 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
