package com.kakario.ereaderlink

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/** 내장 메모리 / 외장 메모리(SD카드) 위치 찾기 */
object Storage {
    data class Root(val label: String, val dir: File)

    fun roots(ctx: Context): List<Root> {
        val out = mutableListOf<Root>()
        out += Root("내장 메모리", Environment.getExternalStorageDirectory())
        val seen = mutableSetOf(out[0].dir.absolutePath)

        val sm = ctx.getSystemService(StorageManager::class.java)
        var sd = 0
        for (v in sm?.storageVolumes.orEmpty()) {
            if (v.isPrimary) continue
            if (v.state != Environment.MEDIA_MOUNTED) continue
            val dir: File? = if (Build.VERSION.SDK_INT >= 30) v.directory else runCatching {
                v.javaClass.getMethod("getPathFile").invoke(v) as File
            }.getOrNull()
            if (dir == null || !seen.add(dir.absolutePath)) continue
            sd++
            val label = if (v.isRemovable) (if (sd == 1) "외장 메모리" else "외장 메모리 $sd")
                        else (v.getDescription(ctx) ?: dir.name)
            out += Root(label, dir)
        }

        // 위 방법으로 못 찾는 기기 대비: 앱 전용 폴더 경로에서 SD카드 뿌리를 거꾸로 계산
        if (sd == 0) {
            for (f in ctx.getExternalFilesDirs(null).drop(1)) {
                if (f == null) continue
                val p = f.absolutePath
                val i = p.indexOf("/Android/data/")
                if (i <= 0) continue
                val dir = File(p.substring(0, i))
                if (seen.add(dir.absolutePath)) out += Root("외장 메모리", dir)
            }
        }
        return out
    }

    fun free(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
    fun total(dir: File): Long = runCatching { StatFs(dir.path).totalBytes }.getOrDefault(0L)

    fun canAccessAll(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
}
