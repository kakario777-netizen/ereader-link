package com.kakario.ereaderlink

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
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
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 폰에서 이북리더(다른 기기)를 탐색기처럼 열기.
 *  - 같은 와이파이의 기기를 자동으로 찾아 목록에 보여 줌
 *  - 폴더 이동, 폰에서 올리기, 폰으로 받기, 삭제, 이름 바꾸기, 새 폴더
 *  - 다른 앱에서 '공유 → 이북 링크'로 보낸 파일을 원하는 폴더에 올리기
 */
class BrowseActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var finder: DeviceFinder? = null
    private val devices = mutableListOf<Triple<String, String, Int>>()   // 이름, IP, 포트

    private var api: RemoteApi? = null
    private var info: RemoteApi.Info? = null
    private var cur: String? = null
    private var items: List<RemoteApi.Item> = emptyList()
    private val selected = linkedSetOf<String>()
    private var shared: List<Uri> = emptyList()
    private val cancel = AtomicBoolean(false)
    @Volatile private var busy = false

    private lateinit var title: TextView
    private lateinit var rootsRow: LinearLayout
    private lateinit var pathText: TextView
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private val adapter = RowAdapter()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readShared(intent)
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, pad, 0, 0)
        }
        title = TextView(this).apply {
            textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.BLACK)
            setPadding(pad, 0, pad, dp(6))
        }
        root.addView(title)
        rootsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad, 0, pad, 0) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(rootsRow) })
        pathText = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY); setPadding(pad, dp(4), pad, dp(4)) }
        root.addView(pathText)
        root.addView(View(this).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, 1))
        list = ListView(this).apply {
            adapter = this@BrowseActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onTap(pos) }
            setOnItemLongClickListener { _, _, pos, _ -> onLongTap(pos); true }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { textSize = 14f; setTextColor(Color.BLACK); setPadding(pad, dp(6), pad, dp(6)) }
        root.addView(status)
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), dp(8)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        setContentView(root)
        showDevices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShared(intent)
        render()
    }

    private fun readShared(i: Intent?) {
        if (i == null) return
        @Suppress("DEPRECATION")
        shared = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                 else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> shared
        }
    }

    override fun onDestroy() {
        finder?.stop()
        cancel.set(true)
        io.shutdownNow()
        super.onDestroy()
    }

    // ───────────── 기기 고르기 ─────────────

    private fun showDevices() {
        api = null; info = null; cur = null; items = emptyList(); selected.clear()
        devices.clear()
        finder?.stop()
        finder = DeviceFinder { name, host, port ->
            runOnUiThread {
                if (devices.none { it.second == host }) {
                    devices += Triple(name, host, port)
                    // 처음 찾은 기기가 하나면 바로 연결
                    if (devices.size == 1 && api == null) connect(host, port, name) else render()
                }
            }
        }.also { it.start() }
        val last = getSharedPreferences("browse", MODE_PRIVATE).getString("last", null)
        if (last != null) tryConnectQuiet(last)
        render()
    }

    private fun tryConnectQuiet(host: String) {
        io.execute {
            val a = RemoteApi(host, LinkState.HTTP_PORT)
            val inf = runCatching { a.info() }.getOrNull() ?: return@execute
            runOnUiThread { if (api == null) connected(a, inf) }
        }
    }

    private fun connect(host: String, port: Int, name: String) {
        status.text = "$name 에 연결하는 중…"
        io.execute {
            val a = RemoteApi(host, port)
            try {
                val inf = a.info()
                runOnUiThread { if (api == null) connected(a, inf) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "연결하지 못했어요: ${e.message}" }
            }
        }
    }

    private fun connected(a: RemoteApi, inf: RemoteApi.Info) {
        api = a; info = inf
        finder?.stop()
        getSharedPreferences("browse", MODE_PRIVATE).edit().putString("last", a.host).apply()
        if (!inf.allAccess) msg("${inf.name}의 이북 링크 앱에서 '모든 파일 접근 허용하기'를 켜 주세요. 안 켜면 폴더가 비어 보일 수 있어요.")
        go(inf.roots.firstOrNull()?.path ?: "/")
    }

    // ───────────── 폴더 보기 ─────────────

    private fun go(path: String, select: String? = null) {
        val a = api ?: return
        status.text = "불러오는 중…"
        io.execute {
            try {
                val list = a.list(path).sortedWith(compareBy<RemoteApi.Item>({ !it.dir }, { it.name.lowercase() }))
                runOnUiThread {
                    cur = path; items = list; selected.clear()
                    status.text = ""
                    render()
                    if (select != null) {
                        val i = items.indexOfFirst { it.name == select }
                        if (i >= 0) this.list.setSelection(i)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "열지 못했어요: ${e.message}"; if (cur == null) render() }
            }
        }
    }

    private fun reload() { cur?.let { go(it) } }

    private fun isRoot(p: String) = info?.roots?.any { it.path == p } == true

    private fun display(p: String): String {
        val r = info?.roots?.filter { p == it.path || p.startsWith(it.path.trimEnd('/') + "/") }?.maxByOrNull { it.path.length }
            ?: return p
        return r.label + p.substring(r.path.trimEnd('/').length)
    }

    private fun onTap(pos: Int) {
        if (api == null) {
            val d = devices.getOrNull(pos) ?: return
            connect(d.second, d.third, d.first)
            return
        }
        val it = items.getOrNull(pos) ?: return
        if (selected.isNotEmpty()) { toggle(it); return }
        if (it.dir) go(it.path)
        else fileMenu(it)
    }

    private fun onLongTap(pos: Int) {
        if (api == null) return
        items.getOrNull(pos)?.let { toggle(it) }
    }

    private fun toggle(it: RemoteApi.Item) {
        if (!selected.remove(it.path)) selected += it.path
        render()
    }

    private fun fileMenu(it: RemoteApi.Item) {
        AlertDialog.Builder(this)
            .setTitle(it.name)
            .setItems(arrayOf("폰으로 받기", "받아서 열기", "이름 바꾸기", "삭제")) { _, w ->
                when (w) {
                    0 -> download(listOf(it), open = false)
                    1 -> download(listOf(it), open = true)
                    2 -> rename(it)
                    3 -> delete(listOf(it))
                }
            }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            busy -> cancel.set(true)
            selected.isNotEmpty() -> { selected.clear(); render() }
            api != null && cur != null && !isRoot(cur!!) -> {
                val from = RemoteApi.name(cur!!)
                go(RemoteApi.parent(cur!!), from)
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ───────────── 화면 그리기 ─────────────

    private fun render() {
        val a = api
        val inf = info
        rootsRow.removeAllViews()
        actions.removeAllViews()
        if (a == null || inf == null) {
            title.text = "기기 고르기"
            pathText.text = if (devices.isEmpty()) "같은 와이파이에서 이북 링크 앱이 켜진 기기를 찾는 중…" else "연결할 기기를 눌러 주세요"
            action("IP로 연결") { askIp() }
            action("닫기") { finish() }
            adapter.notifyDataSetChanged()
            return
        }
        title.text = inf.name
        for (r in inf.roots) {
            val on = cur?.let { it == r.path || it.startsWith(r.path.trimEnd('/') + "/") } == true
            rootsRow.addView(chip(r.label, on) { go(r.path) })
        }
        pathText.text = cur?.let { display(it) } ?: ""
        if (busy) {
            action("취소") { cancel.set(true) }
        } else if (selected.isNotEmpty()) {
            val sel = items.filter { it.path in selected }
            action("폰으로 받기 (${sel.size})") { download(sel, open = false) }
            action("삭제") { delete(sel) }
            if (sel.size == 1) action("이름 바꾸기") { rename(sel[0]) }
            action("모두 선택") { selected.clear(); items.forEach { selected += it.path }; render() }
            action("선택 해제") { selected.clear(); render() }
        } else {
            if (shared.isNotEmpty()) action("여기에 올리기 (${shared.size}개)", bold = true) { val s = shared; shared = emptyList(); upload(s) }
            action("폰에서 올리기") { pick() }
            action("새 폴더") { newFolder() }
            action("새로고침") { reload() }
            action("기기 바꾸기") { showDevices() }
        }
        adapter.notifyDataSetChanged()
    }

    private fun action(text: String, bold: Boolean = false, click: () -> Unit) {
        actions.addView(Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 15f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
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

    inner class RowAdapter : BaseAdapter() {
        override fun getCount() = if (api == null) devices.size else items.size
        override fun getItem(p: Int): Any = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@BrowseActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply { textSize = 16f; setTextColor(Color.BLACK) })
                    addView(TextView(context).apply { textSize = 12f; setTextColor(Color.GRAY) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val icon = row.getChildAt(0) as ImageView
            val texts = row.getChildAt(1) as LinearLayout
            val name = texts.getChildAt(0) as TextView
            val sub = texts.getChildAt(1) as TextView
            if (api == null) {
                val d = devices[p]
                icon.setImageResource(R.drawable.ic_launcher)
                name.text = d.first
                sub.text = d.second
                row.setBackgroundColor(Color.WHITE)
            } else {
                val it = items[p]
                icon.setImageResource(if (it.dir) R.drawable.ic_folder else R.drawable.ic_file)
                name.text = it.name
                val date = if (it.mtime > 0) fmt.format(Date(it.mtime)) else ""
                sub.text = if (it.dir) date else "$date   ${size(it.size)}"
                row.setBackgroundColor(if (it.path in selected) Color.rgb(205, 225, 250) else Color.WHITE)
            }
            return row
        }
    }

    // ───────────── 작업 ─────────────

    /** 오래 걸리는 작업: 진행 상황을 아래 줄에 보여 주고, 취소 가능 */
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

    private val pickReq = 11

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, pickReq)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickReq || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isNotEmpty()) upload(uris)
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

    private fun upload(uris: List<Uri>) {
        val a = api ?: return
        val dir = cur ?: return
        val srcs = uris.map { describe(it) }
        val exist = items.map { it.name }.toSet()
        val dup = srcs.filter { it.name in exist }
        fun start(list: List<Src>) {
            if (list.isEmpty()) return
            job({ report ->
                val total = list.sumOf { maxOf(it.size, 0) }
                var done = 0L
                list.forEachIndexed { i, s ->
                    if (cancel.get()) throw Exception("취소")
                    // 크기를 모르는 파일은 잠깐 폰 안에 복사해서 크기를 알아낸 뒤 보냄
                    val tmp: File? = if (s.size >= 0) null else File(cacheDir, "up.tmp").also { t ->
                        contentResolver.openInputStream(s.uri)!!.use { inp -> FileOutputStream(t).use { o -> inp.copyTo(o) } }
                    }
                    val len = tmp?.length() ?: s.size
                    try {
                        val input = tmp?.inputStream() ?: contentResolver.openInputStream(s.uri)!!
                        input.use { inp ->
                            a.upload(RemoteApi.join(dir, s.name), inp, len, 0, { n ->
                                done += n
                                val pct = if (total > 0) " ${done * 100 / total}%" else ""
                                report("올리는 중 ${i + 1}/${list.size}: ${s.name}$pct")
                            }, cancel)
                        }
                    } finally {
                        tmp?.delete()
                    }
                }
            }) { go(dir, list.firstOrNull()?.name) }
        }
        if (dup.isEmpty()) { start(srcs); return }
        AlertDialog.Builder(this)
            .setTitle("같은 이름이 있어요")
            .setMessage("${dup.size}개가 이미 있어요." + (if (dup.size == 1) "\n(${dup[0].name})" else ""))
            .setPositiveButton("덮어쓰기") { _, _ -> start(srcs) }
            .setNegativeButton("건너뛰기") { _, _ -> start(srcs - dup.toSet()) }
            .setNeutralButton("취소", null)
            .show()
    }

    /** 폰의 '다운로드/이북 링크' 폴더에 저장 */
    private fun download(sel: List<RemoteApi.Item>, open: Boolean) {
        val a = api ?: return
        var firstUri: Uri? = null
        job({ report ->
            // 받을 파일 목록 만들기 (폴더는 안까지)
            val files = mutableListOf<Pair<RemoteApi.Item, String>>()   // 항목, 폰 안 하위 폴더
            fun walk(it: RemoteApi.Item, sub: String) {
                if (cancel.get()) throw Exception("취소")
                if (it.dir) a.list(it.path).forEach { c -> walk(c, if (sub.isEmpty()) it.name else "$sub/${it.name}") }
                else files += it to sub
            }
            report("받을 파일 확인 중…")
            sel.forEach { walk(it, "") }
            val total = files.sumOf { it.first.size }
            var done = 0L
            files.forEachIndexed { i, (it, sub) ->
                if (cancel.get()) throw Exception("취소")
                val (uri, out) = openOut(it.name, sub)
                if (firstUri == null) firstUri = uri
                out.use { o ->
                    a.download(it.path, o, { n ->
                        done += n
                        val pct = if (total > 0) " ${done * 100 / total}%" else ""
                        report("받는 중 ${i + 1}/${files.size}: ${it.name}$pct")
                    }, cancel)
                }
            }
        }) {
            if (!cancel.get()) {
                status.text = "폰의 '다운로드/이북 링크' 폴더에 저장했어요"
                if (open) firstUri?.let { u ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, contentResolver.getType(u) ?: "*/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure { msg("이 파일을 열 앱이 없어요") }
                }
                selected.clear(); render()
            }
        }
    }

    private fun openOut(name: String, sub: String): Pair<Uri, OutputStream> {
        val rel = "이북 링크" + (if (sub.isEmpty()) "" else "/$sub")
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + rel)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw Exception("저장할 수 없어요")
            return uri to contentResolver.openOutputStream(uri)!!
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), rel).apply { mkdirs() }
        val f = File(dir, name)
        return Uri.fromFile(f) to FileOutputStream(f)
    }

    private fun delete(sel: List<RemoteApi.Item>) {
        val a = api ?: return
        val what = if (sel.size == 1) "'${sel[0].name}'" else "${sel.size}개 항목"
        AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage("$what 을(를) 이북리더에서 완전히 지울까요?\n(휴지통으로 가지 않아요)")
            .setPositiveButton("지우기") { _, _ ->
                job({ report ->
                    sel.forEachIndexed { i, it ->
                        if (cancel.get()) throw Exception("취소")
                        report("지우는 중 ${i + 1}/${sel.size}: ${it.name}")
                        a.delete(it.path)
                    }
                }) { reload() }
            }
            .setNegativeButton("아니요", null)
            .show()
    }

    private fun rename(item: RemoteApi.Item) {
        val a = api ?: return
        askText("이름 바꾸기", item.name) { name ->
            if (name == item.name) return@askText
            if (name.contains('/')) { msg("이름에 / 는 쓸 수 없어요"); return@askText }
            job({ _ -> a.rename(item.path, RemoteApi.join(RemoteApi.parent(item.path), name)) }) { cur?.let { c -> go(c, name) } }
        }
    }

    private fun newFolder() {
        val a = api ?: return
        val dir = cur ?: return
        val names = items.map { it.name }.toSet()
        var def = "새 폴더"
        var n = 2
        while (def in names) def = "새 폴더 (${n++})"
        askText("새 폴더", def) { name ->
            job({ _ -> a.mkdir(RemoteApi.join(dir, name)) }) { go(dir, name) }
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
        AlertDialog.Builder(this).setMessage(t).setPositiveButton("확인", null).show()
    }

    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        else -> "${(b + 1023) / 1024} KB"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
