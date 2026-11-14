package com.kakario.ereaderlink

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

typealias Entry = RemoteApi.Item

/** 탐색기가 다루는 '기기' — 내 폰(LocalFs) 또는 상대 기기(RemoteFs) */
interface Fs {
    val title: String
    fun roots(): List<RemoteApi.Root>
    fun list(dir: String): List<Entry>
    fun mkdir(p: String)
    fun delete(p: String)
    fun rename(p: String, to: String)
    fun copy(p: String, to: String)
}

class LocalFs(private val ctx: Context) : Fs {
    override val title = "내 폰"

    override fun roots() = Storage.roots(ctx).map { RemoteApi.Root(it.label, it.dir.absolutePath, Storage.free(it.dir)) }

    override fun list(dir: String): List<Entry> {
        val d = File(dir)
        val files = d.listFiles() ?: throw IOException(
            if (!Storage.canAccessAll()) "이 폰에서 '모든 파일 접근'을 허용해야 볼 수 있어요" else "폴더를 열 수 없어요")
        return files.map { Entry(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
    }

    override fun mkdir(p: String) { val f = File(p); if (!f.isDirectory && !f.mkdirs()) throw IOException("폴더를 만들 수 없어요") }

    override fun delete(p: String) { val f = File(p); if (f.exists() && !f.deleteRecursively()) throw IOException("지우지 못한 파일이 있어요") }

    override fun rename(p: String, to: String) {
        val a = File(p); val b = File(to)
        if (b.exists()) throw IOException("같은 이름이 이미 있어요")
        b.parentFile?.mkdirs()
        if (!a.renameTo(b)) {
            if (!a.copyRecursively(b, overwrite = false)) throw IOException("옮기지 못했어요")
            a.deleteRecursively()
        }
        scan(b)
    }

    override fun copy(p: String, to: String) {
        val a = File(p); val b = File(to)
        if (b.absolutePath.startsWith(a.absolutePath + "/")) throw IOException("폴더를 자기 안으로 복사할 수 없어요")
        a.copyRecursively(b, overwrite = true)
        scan(b)
    }

    fun scan(f: File) {
        val paths = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.take(500).map { it.absolutePath }.toList() else listOf(f.absolutePath)
        if (paths.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null) }
    }

    fun input(p: String) = FileInputStream(File(p))

    /** 받은 내용을 임시 파일에 쓴 뒤 제 이름으로 바꿈 */
    fun receive(p: String, mtime: Long, write: (FileOutputStream) -> Unit) {
        val target = File(p)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, ".${target.name}.part")
        try {
            FileOutputStream(part).use { write(it) }
        } catch (e: Exception) {
            part.delete(); throw e
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) { part.delete(); throw IOException("저장하지 못했어요") }
        if (mtime > 0) target.setLastModified(mtime)
        scan(target)
    }
}

class RemoteFs(val api: RemoteApi, val info: RemoteApi.Info) : Fs {
    override val title get() = info.name
    override fun roots() = info.roots
    override fun list(dir: String) = api.list(dir)
    override fun mkdir(p: String) = api.mkdir(p)
    override fun delete(p: String) = api.delete(p)
    override fun rename(p: String, to: String) = api.rename(p, to)
    override fun copy(p: String, to: String) = api.copy(p, to)
}
