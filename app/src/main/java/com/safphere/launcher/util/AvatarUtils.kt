package com.safphere.launcher.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.ContactsContract
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.safphere.launcher.data.Contact
import java.io.File
import kotlin.math.abs

object AvatarUtils {

    val PRESETS = listOf("👴", "👵", "👨", "👩", "👦", "👧", "🧑‍🌾", "👮")

    private val PALETTE = intArrayOf(
        0xFFFFB74D.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(), 0xFFF06292.toInt(),
        0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt(), 0xFFFFD54F.toInt(), 0xFFA1887F.toInt()
    )

    fun colorFor(name: String): Int = PALETTE[abs(name.hashCode()) % PALETTE.size]

    /** 圆形纯色背景 */
    fun circleBg(color: Int, stroke: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (stroke) setStroke(3, Color.WHITE)
        }

    /**
     * 把联系人头像渲染到「图片视图 + 预设视图」组合上：
     * - 文件头像 → 后台解码（带内存缓存），主线程零解码
     * - preset:emoji → 大号 emoji + 彩色底
     * - 都没有 → 姓名首字 + 彩色底
     */
    private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // 位图内存缓存：按应用可用堆的 1/8 配额（KB），避免固定条目数在小内存设备上占用过高
    private val memCache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun render(context: Context, contact: Contact, img: ImageView, preset: TextView) {
        when {
            contact.avatar.startsWith("preset:") -> {
                val emoji = contact.avatar.removePrefix("preset:")
                showPreset(preset, img, emoji, contact.name)
            }
            contact.avatar.isNotBlank() && File(contact.avatar).exists() -> {
                val path = contact.avatar
                val target = img.layoutParams.width.takeIf { it > 0 } ?: 256
                // 先显示彩色占位，后台解码完成且视图仍绑定同一头像时才上屏
                showPreset(preset, img, contact.name.firstOrNull()?.toString() ?: "人", contact.name)
                img.tag = path
                val cached = memCache.get(path)
                if (cached != null) {
                    showBitmap(preset, img, cached)
                } else {
                    decodeExecutor.execute {
                        val bmp = decodeRound(path, target)
                        if (bmp != null) memCache.put(path, bmp)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (img.tag == path) {
                                bmp?.let { showBitmap(preset, img, it) }
                            }
                        }
                    }
                }
            }
            else -> showPreset(preset, img, contact.name.firstOrNull()?.toString() ?: "人", contact.name)
        }
    }

    private fun showBitmap(preset: TextView, img: ImageView, bmp: Bitmap) {
        preset.visibility = android.view.View.GONE
        img.visibility = android.view.View.VISIBLE
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.setImageBitmap(bmp)
    }

    private fun showPreset(preset: TextView, img: ImageView, content: String, name: String) {
        img.setImageDrawable(null)
        img.visibility = android.view.View.INVISIBLE
        preset.visibility = android.view.View.VISIBLE
        preset.text = content
        preset.setTextColor(Color.WHITE)
        // 照片式：彩色整幅铺满（emoji/首字居中），圆角由外层 photoFrame 裁切
        preset.clipToOutline = false
        preset.background = GradientDrawable().apply { setColor(colorFor(name)) }
    }

    private fun decodeRound(path: String, target: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > target * 2) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** 把相册选中的图片复制到私有目录，返回路径 */
    fun importFromGallery(context: Context, uri: Uri): String? = runCatching {
        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
        val out = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return null
        out.absolutePath
    }.getOrNull()

    /** 从系统通讯录读取联系人头像并复制到私有目录 */
    fun importFromSystemContact(context: Context, contactUri: Uri): String? = runCatching {
        val stream = ContactsContract.Contacts.openContactPhotoInputStream(
            context.contentResolver, contactUri, true /* 高清 */
        ) ?: return null
        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
        val out = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        out.absolutePath
    }.getOrNull()

    fun deleteAvatarFile(avatar: String) {
        if (avatar.isNotBlank() && !avatar.startsWith("preset:")) {
            runCatching { File(avatar).delete() }
        }
    }
}
