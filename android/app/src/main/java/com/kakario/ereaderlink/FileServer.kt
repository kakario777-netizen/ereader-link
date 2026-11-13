package com.kakario.ereaderlink

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * PC 프로그램이 쓰는 작은 파일 서버.
 *  GET  /info               기기 이름 + 내장/외장 메모리
 *  GET  /list?p=폴더         폴더 안 목록
 *  GET  /file?p=파일         파일 받기
 *  PUT  /file?p=파일&m=시각   파일 올리기 (본문 = 파일 내용)
 *  POST /mkdir?p=           새 폴더
 *  POST /delete?p=          지우기 (폴더는 통째로)
 *  POST /rename?p=&to=      이름 바꾸기 / 옮기기
 *  POST /copy?p=&to=        기기 안에서 복사
 */
class FileServer(private val ctx: Context) : NanoHTTPD(LinkState.HTTP_PORT) {

    override fun serve(s: IHTTPSession): Response {
        LinkState.touch(s.remoteIpAddress)
        return try {
            route(s)
        } catch (e: Exception) {
            LinkState.error = e.message
            text(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun route(s: IHTTPSession): Response {
        val p = s.parameters["p"]?.firstOrNull()
        return when (s.uri) {
            "/info" -> info()
            "/list" -> list(need(p))
            "/file" -> when (s.method) {
                Method.GET -> download(need(p))
                Method.PUT, Method.POST -> upload(s, need(p))
                else -> text(Response.Status.METHOD_NOT_ALLOWED, "method")
            }
            "/mkdir" -> { val d = File(need(p)); if (!d.isDirectory && !d.mkdirs()) fail("폴더를 만들 수 없어요"); ok() }
            "/delete" -> delete(need(p))
            "/rename" -> rename(need(p), need(s.parameters["to"]?.firstOrNull()))
            "/copy" -> copy(need(p), need(s.parameters["to"]?.firstOrNull()))
            else -> text(Response.Status.NOT_FOUND, "없는 주소")
        }
    }

    private fun need(v: String?): String = if (v.isNullOrEmpty()) fail("경로가 없어요") else v
    private fun fail(msg: String): Nothing = throw IOException(msg)
    private fun ok() = text(Response.Status.OK, "ok")
    private fun text(st: Response.Status, t: String) = newFixedLengthResponse(st, "text/plain; charset=utf-8", t)
    private fun json(o: Any) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun info(): Response {
        val roots = JSONArray()
        for (r in Storage.roots(ctx)) {
            roots.put(JSONObject()
                .put("label", r.label)
                .put("path", r.dir.absolutePath)
                .put("free", Storage.free(r.dir))
                .put("total", Storage.total(r.dir)))
        }
        return json(JSONObject()
            .put("name", deviceName())
            .put("allAccess", Storage.canAccessAll())
            .put("roots", roots))
    }

    private fun list(path: String): Response {
        val dir = File(path)
        if (!dir.isDirectory) fail("폴더가 아니에요")
        val files = dir.listFiles() ?: fail("폴더를 읽을 수 없어요 (모든 파일 접근 허용이 필요할 수 있어요)")
        val arr = JSONArray()
        for (f in files) {
            arr.put(JSONObject()
                .put("n", f.name)
                .put("d", f.isDirectory)
                .put("s", if (f.isDirectory) 0 else f.length())
                .put("m", f.lastModified()))
        }
        return json(JSONObject().put("items", arr))
    }

    private fun download(path: String): Response {
        val f = File(path)
        if (!f.isFile) fail("파일이 없어요")
        LinkState.doing("보내는 중: ${f.name}")
        val r = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", FileInputStream(f), f.length())
        r.addHeader("X-Mtime", f.lastModified().toString())
        return r
    }

    private fun upload(s: IHTTPSession, path: String): Response {
        val len = s.headers["content-length"]?.toLongOrNull() ?: fail("크기 정보가 없어요")
        val target = File(path)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, ".${target.name}.part")
        LinkState.doing("받는 중: ${target.name}")
        val input = s.inputStream
        val buf = ByteArray(256 * 1024)
        var left = len
        try {
            FileOutputStream(part).use { out ->
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) fail("전송이 끊겼어요")
                    out.write(buf, 0, n)
                    left -= n
                    LinkState.touch(null)
                }
                out.fd.sync()
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) { part.delete(); fail("파일을 저장할 수 없어요") }
        s.parameters["m"]?.firstOrNull()?.toLongOrNull()?.let { target.setLastModified(it) }
        MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), null, null)
        LinkState.doing("받음: ${target.name}")
        return ok()
    }

    private fun delete(path: String): Response {
        val f = File(path)
        if (isRoot(f)) fail("메모리 전체는 지울 수 없어요")
        LinkState.doing("지우는 중: ${f.name}")
        if (f.exists() && !f.deleteRecursively()) fail("지우지 못한 파일이 있어요")
        return ok()
    }

    private fun rename(from: String, to: String): Response {
        val a = File(from); val b = File(to)
        if (isRoot(a)) fail("메모리 자체는 옮길 수 없어요")
        if (!a.exists()) fail("원본이 없어요")
        if (b.exists()) fail("같은 이름이 이미 있어요")
        b.parentFile?.mkdirs()
        if (!a.renameTo(b)) {
            // 내장↔외장처럼 메모리가 다르면 복사 후 지우기
            LinkState.doing("옮기는 중: ${a.name}")
            if (!a.copyRecursively(b, overwrite = false)) fail("옮기지 못했어요")
            a.deleteRecursively()
        }
        return ok()
    }

    private fun copy(from: String, to: String): Response {
        val a = File(from); val b = File(to)
        if (!a.exists()) fail("원본이 없어요")
        if (b.absolutePath.startsWith(a.absolutePath + "/")) fail("폴더를 자기 안으로 복사할 수 없어요")
        LinkState.doing("복사 중: ${a.name}")
        a.copyRecursively(b, overwrite = true)
        return ok()
    }

    private fun isRoot(f: File) = Storage.roots(ctx).any { it.dir.absolutePath == f.absolutePath }

    companion object {
        fun deviceName(): String {
            val m = Build.MODEL ?: "이북리더"
            val b = Build.MANUFACTURER ?: ""
            return if (b.isNotEmpty() && !m.lowercase().startsWith(b.lowercase())) "$b $m" else m
        }
    }
}
