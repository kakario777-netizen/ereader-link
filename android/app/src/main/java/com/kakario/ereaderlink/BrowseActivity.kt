package com.kakario.ereaderlink

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 폰 ↔ 이북리더 탐색기.
 *  맨 위: [내 폰] [상대 기기] 탭
 *  그 아래: [내장 메모리] [외장 메모리] 탭
 *  그 아래: 파일 목록 — 길게 누르면 복사·이동·이름 바꾸기·삭제
 *  복사/이동을 고르면 아래쪽에 '이곳에 붙여넣기' 막대가 떠 있음 → 원하는 기기·폴더로 가서 붙여넣기
 */
class BrowseActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()

    // 기기
    private lateinit var local: LocalFs
    private var remote: RemoteFs? = null
    private var tab = 0                       // 0 = 내 폰, 1 = 상대 기기
    private val curPath = arrayOfNulls<String>(2)
    private var finder: DeviceFinder? = null
    private val devices = mutableListOf<Triple<String, String, Int>>()
    private var connecting = false

    // 목록
    private var items: List<Entry> = emptyList()
    private var listError: String? = null
    private val selected = linkedSetOf<String>()
    private var selectMode = false

    // 붙여넣기 대기 (복사/이동)
    private class Clip(val fromTab: Int, val fs: Fs?, val items: List<Entry>, val move: Boolean, val uris: List<Uri> = emptyList())
    private var clip: Clip? = null

    // 작업
    private val cancel = AtomicBoolean(false)
    @Volatile private var busy = false

    // 화면
    private lateinit var tabPhone: TextView
    private lateinit var tabRemote: TextView
    private lateinit var rootsRow: LinearLayout
    private lateinit var pathText: TextView
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var clipBar: LinearLayout
    private lateinit var clipText: TextView
    private lateinit var actions: LinearLayout
    private val adapter = RowAdapter()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

    private val fs: Fs? get() = if (tab == 0) local else remote

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        local = LocalFs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        Insets.apply(this, root)

        // ① 기기 탭 + 설정
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tabPhone = tabView("내 폰") { switchTab(0) }
        tabRemote = tabView("상대 기기") { switchTab(1) }
        head.addView(tabPhone, LinearLayout.LayoutParams(0, dp(50), 1f))
        head.addView(tabRemote, LinearLayout.LayoutParams(0, dp(50), 1f))
        head.addView(SettingsMenu.button(this))
        root.addView(head)
        root.addView(line())

        // ② 내장/외장 탭
        rootsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(4)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(rootsRow) })
        pathText = TextView(this).apply {
            textSize = 13f; setTextColor(Color.DKGRAY); setPadding(dp(14), dp(2), dp(14), dp(6))
            ellipsize = TextUtils.TruncateAt.START; isSingleLine = true
        }
        root.addView(pathText)
        root.addView(line())

        // ③ 탐색기
        list = ListView(this).apply {
            adapter = this@BrowseActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onTap(pos) }
            setOnItemLongClickListener { _, _, pos, _ -> onLongTap(pos); true }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // ④ 아래쪽: 진행 상황 / 붙여넣기 막대 / 버튼
        status = TextView(this).apply { textSize = 13f; setTextColor(Color.BLACK); setPadding(dp(14), dp(4), dp(14), dp(2)) }
        root.addView(status)
        clipBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(30, 30, 30))
            setPadding(dp(14), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        clipText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; maxLines = 2 }
        clipBar.addView(clipText, LinearLayout.LayoutParams(0, -2, 1f))
        clipBar.addView(barButton("이곳에 붙여넣기", bold = true) { paste() })
        clipBar.addView(barButton("취소") { clip = null; render() })
        root.addView(clipBar, LinearLayout.LayoutParams(-1, -2))
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(2), dp(6), dp(4)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        setContentView(root)

        readShared(intent)
        startFinder()
        getSharedPreferences("browse", MODE_PRIVATE).getString("last", null)?.let { tryConnectQuiet(it) }
        // 공유로 들어왔으면 상대 기기 탭부터
        switchTab(if (clip?.uris?.isNotEmpty() == true) 1 else 0)
        Updater.check(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShared(intent)
        if (clip?.uris?.isNotEmpty() == true) switchTab(1) else render()
    }

    override fun onResume() {
        super.onResume()
        if (tab == 0 && listError != null) reload()   // 권한 허용하고 돌아온 경우
    }

    override fun onDestroy() {
        Direct.leave(this)      // 화면을 나가면 원래 와이파이로
        finder?.stop()
        cancel.set(true)
        io.shutdownNow()
        super.onDestroy()
    }

    /** 다른 앱에서 '공유 → 이북 링크'로 보낸 파일 = 붙여넣기 대기 */
    private fun readShared(i: Intent?) {
        if (i == null) return
        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                 else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) clip = Clip(-1, null, emptyList(), move = false, uris = uris)
    }

    // ───────────── 상대 기기 찾기 / 연결 ─────────────

    private fun startFinder() {
        finder?.stop()
        devices.clear()
        finder = DeviceFinder { name, host, port ->
            runOnUiThread {
                if (devices.none { it.second == host }) {
                    devices += Triple(name, host, port)
                    if (remote == null && devices.size == 1) connect(host, port, name) else render()
                }
            }
        }.also { it.start() }
    }

    private fun tryConnectQuiet(host: String) {
        io.execute {
            val a = RemoteApi(host, LinkState.HTTP_PORT)
            val inf = runCatching { a.info() }.getOrNull() ?: return@execute
            runOnUiThread { if (remote == null) connected(a, inf) }
        }
    }

    private fun connect(host: String, port: Int, name: String) {
        if (connecting) return
        connecting = true
        status.text = "$name 에 연결하는 중…"
        io.execute {
            val a = RemoteApi(host, port)
            val r = runCatching { a.info() }
            runOnUiThread {
                connecting = false
                r.onSuccess { connected(a, it) }.onFailure { status.text = "연결하지 못했어요: ${it.message}" }
            }
        }
    }

    private fun connected(a: RemoteApi, inf: RemoteApi.Info) {
        remote = RemoteFs(a, inf)
        curPath[1] = null
        getSharedPreferences("browse", MODE_PRIVATE).edit().putString("last", a.host).apply()
        status.text = ""
        if (!inf.allAccess) msg("${inf.name}의 이북 링크 앱에서 '모든 파일 접근 허용하기'를 켜 주세요. 안 켜면 폴더가 비어 보일 수 있어요.")
        if (tab == 1) openTab() else render()
    }

    private fun disconnect() {
        Direct.leave(this)      // 다이렉트로 붙어 있었으면 원래 와이파이로
        remote = null
        curPath[1] = null
        if (clip?.fromTab == 1) clip = null
        startFinder()
        if (tab == 1) { items = emptyList(); listError = null }
        render()
    }

    // ───────────── 탭 / 폴더 이동 ─────────────

    private fun switchTab(t: Int) {
        tab = t
        selected.clear(); selectMode = false
        openTab()
    }

    private fun openTab() {
        val f = fs
        if (f == null) { items = emptyList(); listError = null; render(); return }
        val p = curPath[tab] ?: f.roots().firstOrNull()?.path
        if (p == null) { items = emptyList(); render(); return }
        go(p)
    }

    private fun go(path: String, select: String? = null) {
        val f = fs ?: return
        val t = tab
        status.text = "불러오는 중…"
        io.execute {
            val r = runCatching { f.list(path).sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() })) }
            runOnUiThread {
                if (t != tab || f !== fs) return@runOnUiThread
                status.text = ""
                curPath[tab] = path
                r.onSuccess { items = it; listError = null }
                 .onFailure { items = emptyList(); listError = it.message ?: "열지 못했어요" }
                if (!selectMode) selected.clear()
                render()
                if (select != null) {
                    val i = rows().indexOfFirst { it is Entry && it.name == select }
                    if (i >= 0) list.setSelection(i)
                }
                // 상대 기기와 연결이 끊긴 경우
                val ex = r.exceptionOrNull()
                if (f is RemoteFs && (ex is java.net.ConnectException || ex is java.net.SocketTimeoutException)) {
                    disconnect()
                    status.text = "상대 기기와 연결이 끊겼어요. 다시 찾는 중…"
                }
            }
        }
    }

    private fun reload() { curPath[tab]?.let { go(it) } ?: openTab() }

    private fun isRoot(p: String) = fs?.roots()?.any { it.path == p } == true

    private fun display(p: String): String {
        val r = fs?.roots()?.filter { p == it.path || p.startsWith(it.path.trimEnd('/') + "/") }?.maxByOrNull { it.path.length }
            ?: return p
        return r.label + p.substring(r.path.trimEnd('/').length)
    }

    private fun goUp() {
        val c = curPath[tab] ?: return
        if (isRoot(c)) return
        go(RemoteApi.parent(c), RemoteApi.name(c))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val c = curPath[tab]
        when {
            busy -> cancel.set(true)
            selectMode -> { selectMode = false; selected.clear(); render() }
            fs != null && c != null && !isRoot(c) -> goUp()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ───────────── 목록 행 ─────────────

    private object UpRow

    /** 화면에 그릴 행들: 상대 기기 미연결이면 기기 목록, 아니면 ".." + 파일들 */
    private fun rows(): List<Any> {
        if (tab == 1 && remote == null) return devices
        val c = curPath[tab]
        val up = if (c != null && !isRoot(c)) listOf<Any>(UpRow) else emptyList()
        return up + items
    }

    private fun onTap(pos: Int) {
        when (val r = rows().getOrNull(pos)) {
            is Triple<*, *, *> -> connect(r.second as String, r.third as Int, r.first as String)
            UpRow -> goUp()
            is Entry -> when {
                selectMode -> toggle(r)
                r.dir -> go(r.path)
                else -> itemMenu(r)
            }
        }
    }

    private fun onLongTap(pos: Int) {
        val r = rows().getOrNull(pos) as? Entry ?: return
        if (selectMode) toggle(r) else itemMenu(r)
    }

    private fun toggle(e: Entry) {
        if (!selected.remove(e.path)) selected += e.path
        render()
    }

    /** 길게 누르면 뜨는 메뉴 (PC의 오른쪽 클릭) */
    private fun itemMenu(e: Entry) {
        val opts = arrayOf("복사", "이동", "이름 바꾸기", "삭제", "여러 개 선택")
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> setClip(listOf(e), move = false)
                    1 -> setClip(listOf(e), move = true)
                    2 -> rename(e)
                    3 -> delete(listOf(e))
                    4 -> { selectMode = true; selected.clear(); selected += e.path; render() }
                }
            }.show()
    }

    private fun setClip(sel: List<Entry>, move: Boolean) {
        clip = Clip(tab, fs, sel, move)
        selectMode = false; selected.clear()
        render()
    }

    // ───────────── 화면 그리기 ─────────────

    private fun render() {
        val r = remote
        styleTab(tabPhone, tab == 0)
        styleTab(tabRemote, tab == 1)
        tabRemote.text = r?.let { it.title + if (Direct.joined) " (다이렉트)" else "" } ?: if (devices.isEmpty()) "상대 기기 (찾는 중)" else "상대 기기"

        // 내장/외장 탭
        rootsRow.removeAllViews()
        val f = fs
        val c = curPath[tab]
        if (f != null) {
            for (root in f.roots()) {
                val on = c != null && (c == root.path || c.startsWith(root.path.trimEnd('/') + "/"))
                rootsRow.addView(chip(root.label, on) { go(root.path) })
            }
            if (tab == 1) rootsRow.addView(chip("기기 바꾸기", false) { disconnect() })
            pathText.text = listError ?: c?.let { display(it) } ?: ""
            pathText.setTextColor(if (listError != null) Color.rgb(190, 0, 0) else Color.DKGRAY)
        } else {
            pathText.setTextColor(Color.DKGRAY)
            pathText.text = if (devices.isEmpty()) "같은 와이파이에서 이북 링크 앱이 켜진 기기를 찾는 중…" else "연결할 기기를 눌러 주세요"
        }

        // 붙여넣기 막대
        val cl = clip
        if (cl != null && !busy) {
            val n = if (cl.uris.isNotEmpty()) cl.uris.size else cl.items.size
            val what = if (cl.uris.isEmpty() && n == 1) "'${cl.items[0].name}'" else "${n}개"
            val from = when { cl.uris.isNotEmpty() -> "공유한 파일"; cl.fromTab == 0 -> "내 폰"; else -> remote?.title ?: "상대 기기" }
            clipText.text = "$from $what ${if (cl.move) "이동" else "복사"} — 넣을 폴더로 가서 붙여넣기"
            clipBar.visibility = View.VISIBLE
        } else clipBar.visibility = View.GONE

        // 아래 버튼
        actions.removeAllViews()
        when {
            busy -> action("취소") { cancel.set(true) }
            f == null -> {
                action("와이파이 다이렉트로 연결", bold = true) { joinDirect() }
                action("IP로 연결") { askIp() }
                action("다시 찾기") { startFinder(); render() }
            }
            selectMode -> {
                val sel = items.filter { it.path in selected }
                action("복사 (${sel.size})") { if (sel.isNotEmpty()) setClip(sel, false) }
                action("이동") { if (sel.isNotEmpty()) setClip(sel, true) }
                action("삭제") { if (sel.isNotEmpty()) delete(sel) }
                action("모두 선택") { items.forEach { selected += it.path }; render() }
                action("선택 끝") { selectMode = false; selected.clear(); render() }
            }
            else -> {
                if (tab == 0 && !Storage.canAccessAll()) action("모든 파일 접근 허용", bold = true) { askAllFiles() }
                action("새 폴더") { newFolder() }
                action("여러 개 선택") { selectMode = true; selected.clear(); render() }
                action("새로고침") { reload() }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun styleTab(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.BLACK else Color.GRAY)
        v.setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.setBackgroundColor(if (on) Color.rgb(238, 238, 238) else Color.WHITE)
    }

    private fun tabView(text: String, click: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 17f
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { click() }
    }

    private fun chip(text: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(if (on) Color.WHITE else Color.BLACK)
        setBackgroundColor(if (on) Color.BLACK else Color.rgb(235, 235, 235))
        setPadding(dp(14), dp(7), dp(14), dp(7))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        setOnClickListener { click() }
    }

    private fun action(text: String, bold: Boolean = false, click: () -> Unit) {
        actions.addView(Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 14f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
    }

    private fun barButton(text: String, bold: Boolean = false, click: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(if (bold) Color.rgb(255, 214, 90) else Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { click() }
    }

    private fun line() = View(this).apply {
        setBackgroundColor(Color.rgb(220, 220, 220))
        layoutParams = LinearLayout.LayoutParams(-1, 1)
    }

    inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows().size
        override fun getItem(p: Int): Any = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@BrowseActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply { textSize = 16f; setTextColor(Color.BLACK); maxLines = 2 })
                    addView(TextView(context).apply { textSize = 12f; setTextColor(Color.GRAY) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val icon = row.getChildAt(0) as ImageView
            val texts = row.getChildAt(1) as LinearLayout
            val name = texts.getChildAt(0) as TextView
            val sub = texts.getChildAt(1) as TextView
            row.setBackgroundColor(Color.WHITE)
            when (val r = rows()[p]) {
                is Triple<*, *, *> -> {
                    icon.setImageResource(R.drawable.ic_launcher)
                    name.text = r.first as String
                    sub.text = r.second as String
                }
                UpRow -> {
                    icon.setImageResource(R.drawable.ic_folder)
                    name.text = ".. (상위 폴더)"
                    sub.text = ""
                }
                is Entry -> {
                    icon.setImageResource(if (r.dir) R.drawable.ic_folder else R.drawable.ic_file)
                    name.text = r.name
                    val date = if (r.mtime > 0) fmt.format(Date(r.mtime)) else ""
                    sub.text = if (r.dir) date else "$date   ${size(r.size)}"
                    if (r.path in selected) row.setBackgroundColor(Color.rgb(205, 225, 250))
                }
            }
            return row
        }
    }

    // ───────────── 작업 ─────────────

    private fun job(work: ((String) -> Unit) -> Unit, after: () -> Unit) {
        if (busy) { msg("앞 작업이 끝난 뒤에 해 주세요"); return }
        busy = true
        cancel.set(false)
        render()
        var lastUi = 0L
        val report: (String) -> Unit = { t ->
            val now = System.currentTimeMillis()
            if (now - lastUi > 250) { lastUi = now; runOnUiThread { status.text = t } }
        }
        io.execute {
            val err = try { work(report); null } catch (e: Exception) { e }
            runOnUiThread {
                busy = false
                status.text = when {
                    err == null -> "완료"
                    cancel.get() -> "취소했어요"
                    else -> "실패: ${err.message}"
                }
                render()
                after()
            }
        }
    }

    private fun checkCancel() { if (cancel.get()) throw Exception("취소") }

    /** 붙여넣기: 같은 기기 안이면 복사/이동, 다른 기기면 주고받기 */
    private fun paste() {
        val cl = clip ?: return
        val dest = fs ?: return
        val dir = curPath[tab] ?: return
        val existing = items.map { it.name }.toSet()

        // 공유로 받은 파일
        if (cl.uris.isNotEmpty()) {
            val srcs = cl.uris.map { describe(it) }
            confirmOverwrite(srcs.map { it.name }.filter { it in existing }) { overwrite ->
                val todo = if (overwrite) srcs else srcs.filter { it.name !in existing }
                clip = null
                job({ report -> sendUris(todo, dest, dir, report) }) { go(dir, todo.firstOrNull()?.name) }
            }
            return
        }

        val sameDevice = cl.fromTab == tab && cl.fs === dest
        if (sameDevice) {
            clip = if (cl.move) null else cl
            job({ report ->
                val names = existing.toMutableSet()
                cl.items.forEachIndexed { i, e ->
                    checkCancel()
                    report("${if (cl.move) "옮기는 중" else "복사 중"} ${i + 1}/${cl.items.size}: ${e.name}")
                    if (dir == e.path || dir.startsWith(e.path.trimEnd('/') + "/")) throw Exception("폴더를 자기 안으로 넣을 수 없어요")
                    if (cl.move) {
                        if (RemoteApi.parent(e.path) != dir) {
                            if (e.name in names) throw Exception("'${e.name}' 같은 이름이 이미 있어요")
                            dest.rename(e.path, RemoteApi.join(dir, e.name))
                            names += e.name
                        }
                    } else {
                        val n = uniqueName(e, names)
                        dest.copy(e.path, RemoteApi.join(dir, n))
                        names += n
                    }
                }
            }) { reload() }
            return
        }

        // 다른 기기로: 같은 이름 확인 후 주고받기
        val src = cl.fs ?: return
        confirmOverwrite(cl.items.map { it.name }.filter { it in existing }) { overwrite ->
            val todo = if (overwrite) cl.items else cl.items.filter { it.name !in existing }
            clip = null
            job({ report ->
                transfer(src, todo, dest, dir, report)
                if (cl.move) todo.forEach { checkCancel(); report("원래 자리에서 지우는 중: ${it.name}"); src.delete(it.path) }
            }) { go(dir, todo.firstOrNull()?.name) }
        }
    }

    private fun uniqueName(e: Entry, names: Set<String>): String {
        if (e.name !in names) return e.name
        val dot = e.name.lastIndexOf('.')
        val stem = if (!e.dir && dot > 0) e.name.substring(0, dot) else e.name
        val ext = if (!e.dir && dot > 0) e.name.substring(dot) else ""
        var n = "$stem - 복사본$ext"
        var k = 2
        while (n in names) n = "$stem - 복사본 (${k++})$ext"
        return n
    }

    private fun confirmOverwrite(dups: List<String>, then: (Boolean) -> Unit) {
        if (dups.isEmpty()) { then(true); return }
        AlertDialog.Builder(this)
            .setTitle("같은 이름이 있어요")
            .setMessage("${dups.size}개가 이미 있어요." + (if (dups.size == 1) "\n(${dups[0]})" else ""))
            .setPositiveButton("덮어쓰기") { _, _ -> then(true) }
            .setNegativeButton("건너뛰기") { _, _ -> then(false) }
            .setNeutralButton("취소", null)
            .show()
    }

    /** 내 폰 ↔ 상대 기기 주고받기 (폴더는 안까지) */
    private fun transfer(src: Fs, sel: List<Entry>, dest: Fs, dir: String, report: (String) -> Unit) {
        report("보낼 파일 확인 중…")
        val dirs = mutableListOf<String>()
        val files = mutableListOf<Pair<Entry, String>>()   // 원본, 받는 쪽 경로
        fun walk(e: Entry, target: String) {
            checkCancel()
            if (e.dir) {
                dirs += target
                src.list(e.path).forEach { c -> walk(c, RemoteApi.join(target, c.name)) }
            } else files += e to target
        }
        sel.forEach { e -> walk(e, RemoteApi.join(dir, e.name)) }
        dirs.forEach { d -> checkCancel(); dest.mkdir(d) }
        val total = files.sumOf { it.first.size }.coerceAtLeast(1)
        var done = 0L
        val verb = if (src is LocalFs) "보내는 중" else "받는 중"
        files.forEachIndexed { i, (e, target) ->
            checkCancel()
            val tick: (Int) -> Unit = { n -> done += n; report("$verb ${i + 1}/${files.size}: ${e.name}  ${done * 100 / total}%") }
            when {
                src is LocalFs && dest is RemoteFs ->
                    src.input(e.path).use { inp -> dest.api.upload(target, inp, e.size, e.mtime, tick, cancel) }
                src is RemoteFs && dest is LocalFs ->
                    dest.receive(target, e.mtime) { out -> src.api.download(e.path, out, tick, cancel) }
                else -> throw Exception("이 방향은 아직 지원하지 않아요")
            }
        }
    }

    private data class Src(val uri: Uri, val name: String, val size: Long)

    private fun describe(u: Uri): Src {
        var name = u.lastPathSegment?.substringAfterLast('/') ?: "파일"
        var size = -1L
        runCatching {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return Src(u, name.replace('/', '_'), size)
    }

    private fun sendUris(todo: List<Src>, dest: Fs, dir: String, report: (String) -> Unit) {
        todo.forEachIndexed { i, s ->
            checkCancel()
            report("보내는 중 ${i + 1}/${todo.size}: ${s.name}")
            val target = RemoteApi.join(dir, s.name)
            if (dest is LocalFs) {
                dest.receive(target, 0) { out -> contentResolver.openInputStream(s.uri)!!.use { inp -> inp.copyTo(out) } }
            } else if (dest is RemoteFs) {
                // 크기를 모르는 파일은 잠깐 폰 안에 복사해서 크기를 알아낸 뒤 보냄
                val tmp: File? = if (s.size >= 0) null else File(cacheDir, "up.tmp").also { t ->
                    contentResolver.openInputStream(s.uri)!!.use { inp -> t.outputStream().use { o -> inp.copyTo(o) } }
                }
                val len = tmp?.length() ?: s.size
                try {
                    (tmp?.inputStream() ?: contentResolver.openInputStream(s.uri)!!).use { inp ->
                        var done = 0L
                        dest.api.upload(target, inp, len, 0, { n ->
                            done += n
                            report("보내는 중 ${i + 1}/${todo.size}: ${s.name}  ${if (len > 0) done * 100 / len else 0}%")
                        }, cancel)
                    }
                } finally { tmp?.delete() }
            }
        }
    }

    private fun delete(sel: List<Entry>) {
        val f = fs ?: return
        val where = if (tab == 0) "내 폰" else (remote?.title ?: "상대 기기")
        val what = if (sel.size == 1) "'${sel[0].name}'" else "${sel.size}개 항목"
        AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage("$where 에서 $what 을(를) 완전히 지울까요?\n(휴지통으로 가지 않아요)")
            .setPositiveButton("지우기") { _, _ ->
                selectMode = false; selected.clear()
                job({ report ->
                    sel.forEachIndexed { i, e -> checkCancel(); report("지우는 중 ${i + 1}/${sel.size}: ${e.name}"); f.delete(e.path) }
                }) { reload() }
            }
            .setNegativeButton("아니요", null)
            .show()
    }

    private fun rename(e: Entry) {
        val f = fs ?: return
        askText("이름 바꾸기", e.name) { name ->
            if (name == e.name) return@askText
            if (name.contains('/')) { msg("이름에 / 는 쓸 수 없어요"); return@askText }
            job({ _ -> f.rename(e.path, RemoteApi.join(RemoteApi.parent(e.path), name)) }) { curPath[tab]?.let { c -> go(c, name) } }
        }
    }

    private fun newFolder() {
        val f = fs ?: return
        val dir = curPath[tab] ?: return
        val names = items.map { it.name }.toSet()
        var def = "새 폴더"
        var n = 2
        while (def in names) def = "새 폴더 (${n++})"
        askText("새 폴더", def) { name -> job({ _ -> f.mkdir(RemoteApi.join(dir, name)) }) { go(dir, name) } }
    }

    private fun askAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    /** 공유기 없이 이북리더와 직접 연결 (이북리더에서 '와이파이 다이렉트 켜기'를 먼저) */
    private fun joinDirect() {
        if (!Direct.supported) { msg("와이파이 다이렉트는 안드로이드 10 이상에서만 돼요"); return }
        if (!Direct.hasPermission(this)) { Direct.askPermission(this, 8); return }
        if (!Direct.wifiOn(this)) {
            msg("폰의 와이파이를 먼저 켜 주세요. 공유기에 연결 안 돼 있어도 괜찮아요.")
            if (Build.VERSION.SDK_INT >= 29) runCatching { startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
            return
        }
        status.text = "와이파이 다이렉트로 이북리더에 연결하는 중… (최대 30초)"
        Direct.join(this) { host, err ->
            runOnUiThread {
                if (host != null) {
                    status.text = "다이렉트 연결됨 — 이북 링크에 접속하는 중…"
                    // 그룹이 막 만들어진 직후라 잠깐 기다렸다 접속
                    list.postDelayed({ connect(host, LinkState.HTTP_PORT, "이북리더 (다이렉트)") }, 1500)
                } else status.text = err ?: "연결하지 못했어요"
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) {
            if (Direct.hasPermission(this)) joinDirect()
            else msg("와이파이 다이렉트를 쓰려면 '근처 기기'(또는 위치) 권한이 필요해요")
        }
    }

    private fun askIp() {
        askText("IP로 연결", getSharedPreferences("browse", MODE_PRIVATE).getString("last", "") ?: "", number = true) { t ->
            val host = t.substringBefore(':')
            val port = t.substringAfter(':', "").toIntOrNull() ?: LinkState.HTTP_PORT
            connect(host, port, host)
        }
    }

    private fun askText(title: String, def: String, number: Boolean = false, ok: (String) -> Unit) {
        val e = EditText(this).apply {
            setText(def)
            setSingleLine()
            if (number) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelection(0, if (def.contains('.') && !number) def.lastIndexOf('.') else def.length)
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("확인") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun msg(t: String) {
        if (!isFinishing) AlertDialog.Builder(this).setMessage(t).setPositiveButton("확인", null).show()
    }

    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        else -> "${(b + 1023) / 1024} KB"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
