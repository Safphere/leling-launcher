package com.safphere.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.View
import java.io.File
import kotlin.concurrent.thread

/**
 * 自定义壁纸：子女从相册选一张图，拷贝进应用私有目录（降采样省内存），
 * 桌面背景即时生效。叠一层 70% 白纱罩——壁纸好看的同时保住大字可读性（老人优先）。
 */
object Wallpaper {

    private const val MAX_DIM = 1440          // 存储与解码上限，够 1080p 全屏
    private const val SCRIM_ALPHA = 0xB3      // 70% 白纱罩

    fun file(context: Context): File = File(context.filesDir, "home_wallpaper.jpg")

    /** 相册选图后调用：拷贝+降采样到私有目录。耗时 IO，请在后台线程调用。 */
    fun setFromUri(context: Context, uri: Uri): Boolean {
        return runCatching {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            val out = file(context)
            out.outputStream().use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
            bmp.recycle()
            true
        }.getOrDefault(false)
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** 桌面根布局应用壁纸（含白纱罩）；无壁纸时返回 null（调用方还原默认底色）。IO，请后台线程。 */
    fun decorate(context: Context): Drawable? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
            // 铺满拉伸由 root 尺寸决定；这里直接给 BitmapDrawable（拉伸即可，壁纸本就按屏裁剪）
            val scrim = ColorDrawable(Color.argb(SCRIM_ALPHA, 0xFA, 0xFA, 0xFA))
            LayerDrawable(arrayOf(android.graphics.drawable.BitmapDrawable(
                context.resources, bmp), scrim))
        }.getOrNull()
    }

    /** 后台应用壁纸到指定根布局（含换壁纸/清壁纸的增量判断） */
    fun refreshAsync(context: Context, root: View, lastStamp: Long, onDone: (Long) -> Unit) {
        thread {
            val f = file(context)
            val stamp = if (f.exists()) f.lastModified() else -1L
            if (stamp == lastStamp) {
                onDone(stamp)
                return@thread
            }
            val d = decorate(context)
            root.post {
                root.background = d ?: ColorDrawable(
                    androidx.core.content.ContextCompat.getColor(context,
                        com.safphere.launcher.R.color.bg))
                onDone(stamp)
            }
        }
    }
}
