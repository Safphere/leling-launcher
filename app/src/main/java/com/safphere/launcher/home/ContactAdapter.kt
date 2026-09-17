package com.safphere.launcher.home

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.safphere.launcher.data.Contact
import com.safphere.launcher.databinding.ItemAddContactBinding
import com.safphere.launcher.databinding.ItemContactBinding

/**
 * 桌面联系人网格：1寸照片式小卡（照片+名字），点击=直接呼叫，
 * 长按=菜单（发短信/编辑）。列数由子女模式设置（每行1/2/3个）。
 * 紧急联系人带 SOS 红标并排在最前。
 */
class ContactAdapter(
    private var contacts: List<Contact> = emptyList(),
    private val onCall: (Contact) -> Unit,
    private val onSms: (Contact) -> Unit,
    private val onEdit: (Contact) -> Unit,
    private val onAdd: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_CONTACT = 0
        private const val TYPE_ADD = 1
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Contact>) {
        contacts = list
        notifyDataSetChanged()
    }

    /** 紧急联系人排在最前 */
    private fun sorted(): List<Contact> =
        contacts.sortedByDescending { it.emergency }

    override fun getItemCount(): Int = contacts.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position < contacts.size) TYPE_CONTACT else TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CONTACT) {
            ContactHolder(ItemContactBinding.inflate(inf, parent, false))
        } else {
            AddHolder(ItemAddContactBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ContactHolder -> {
                val c = sorted()[position]
                val density = holder.itemView.context.resources.displayMetrics.density
                val dp = { v: Int -> (v * density).toInt() }

                // 照片高度随列数（紧凑：每行1/2/3个 → 大/中/小）
                val cols = com.safphere.launcher.data.Prefs.contactColumns
                holder.b.photoFrame.layoutParams.height =
                    dp(when (cols) { 1 -> 190; 2 -> 118; else -> 84 })
                holder.b.nameText.textSize = when (cols) { 1 -> 22f; 2 -> 18f; else -> 15f }

                // 照片圆角裁切（1寸照片式圆角矩形）
                val radius = dp(16).toFloat()
                holder.b.photoFrame.clipToOutline = true
                holder.b.photoFrame.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }

                com.safphere.launcher.util.AvatarUtils.render(
                    holder.itemView.context, c, holder.b.avatarImg, holder.b.avatarPreset
                )
                holder.b.nameText.text = c.name
                // 点击=直接呼叫；长按=菜单（发短信/编辑）
                holder.b.card.setOnClickListener { onCall(c) }
                holder.b.card.setOnLongClickListener {
                    showMenu(c, holder.b.card); true
                }
            }
            is AddHolder -> {
                // 高度与联系人照片卡一致（照片高 + 名字区），避免被网格撑满整屏
                val cols = com.safphere.launcher.data.Prefs.contactColumns
                val photoH = when (cols) { 1 -> 190; 2 -> 118; else -> 84 }
                holder.b.card.layoutParams.height =
                    dp(holder.b.card.context, photoH + 44)
                holder.b.card.setOnClickListener { onAdd() }
            }
        }
    }

    private fun dp(context: android.content.Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private fun showMenu(c: Contact, anchor: View) {
        androidx.appcompat.app.AlertDialog.Builder(anchor.context)
            .setTitle(c.name)
            .setItems(arrayOf("✉️ 发短信", "✏️ 编辑")) { _, which ->
                if (which == 0) onSms(c) else onEdit(c)
            }
            .show()
    }

    class ContactHolder(val b: ItemContactBinding) : RecyclerView.ViewHolder(b.root)
    class AddHolder(val b: ItemAddContactBinding) : RecyclerView.ViewHolder(b.root)
}
