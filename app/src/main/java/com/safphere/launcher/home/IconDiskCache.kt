package com.safphere.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 应用图标二级缓存（冷启动"秒出"的关键）：
 * 1) 内存 LRU —— 进程内零解码；
 * 2) 磁盘 PNG —— 重启后免 PackageManager 解码（loadIcon 单个可达几十毫秒），
 *    首帧从磁盘解码（1~3ms/个）；
 * 3) 全未命中（首次安装新应用）才走 loadIcon，并在后台把结果渲染成 PNG 落盘。
 * 后台线程可在枚举时调用 [prefetch] 把图标预热进内存，主线程完全不解码。
 */
object IconDiskCache {

    private const val TAG = "IconDiskCache"
    private const val PX = 144   // 落盘尺寸：应用页52dp/快捷栏48dp 都够用

    private val mem = ConcurrentHashMap<String, Drawable>()
    private val disk = Executors.newSingleThreadExecutor()

    private fun dir(context: Context): File = File(context.filesDir, "icons").apply { mkdirs() }

    /** 主线程取图标：内存 → 磁盘 → 实时加载（异步落盘） */
    fun get(context: Context, pkg: String, live: () -> Drawable?): Drawable? {
        mem[pkg]?.let { return it }
        val f = File(dir(context), "${pkg}.png")
        if (f.exists()) {
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                if (bmp != null) {
                    val d = android.graphics.drawable.BitmapDrawable(
                        context.resources, bmp)
                    mem[pkg] = d
                    return d
                }
            }
        }
        val d = runCatching { live() }.getOrNull() ?: return null
        mem[pkg] = d
        writeAsync(context, pkg, d)
        return d
    }

    /** 后台预热：枚举线程调用，加载并写内存+磁盘，主线程之后零成本 */
    fun prefetch(context: Context, pkg: String, live: () -> Drawable?) {
        if (mem.containsKey(pkg)) return
        val f = File(dir(context), "${pkg}.png")
        if (f.exists()) {
            // 磁盘有就直接解码进内存，主线程取时零IO
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                if (bmp != null) {
                    mem[pkg] = android.graphics.drawable.BitmapDrawable(
                        context.resources, bmp)
                    return
                }
            }
        }
        val d = runCatching { live() }.getOrNull() ?: return
        mem[pkg] = d
        writeAsync(context, pkg, d)
    }

    private fun writeAsync(context: Context, pkg: String, d: Drawable) {
        val app = context.applicationContext
        disk.execute {
            runCatching {
                val bmp = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                d.setBounds(0, 0, PX, PX)
                d.draw(canvas)
                val f = File(dir(app), "${pkg}.png")
                val tmp = File(dir(app), "${pkg}.tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(f)) tmp.delete()
                bmp.recycle()
            }.onFailure { Log.w(TAG, "write $pkg failed: ${it.message}") }
        }
    }

    /** 应用更新/卸载：立即失效内存与磁盘缓存（下次渲染重新加载） */
    fun invalidate(context: Context, pkg: String) {
        mem.remove(pkg)
        disk.execute {
            runCatching { File(dir(context), "${pkg}.png").delete() }
        }
    }

    /** 清理已卸载应用的缓存文件（枚举后后台调用） */
    fun prune(context: Context, keepPkgs: Set<String>) {
        disk.execute {
            runCatching {
                dir(context).listFiles()?.forEach { f ->
                    val pkg = f.name.removeSuffix(".png")
                    if (pkg !in keepPkgs) f.delete()
                }
            }
        }
    }
}
