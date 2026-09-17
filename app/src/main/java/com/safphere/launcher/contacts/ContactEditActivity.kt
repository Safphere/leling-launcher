package com.safphere.launcher.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.safphere.launcher.data.Contact
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.databinding.ActivityContactEditBinding
import com.safphere.launcher.databinding.ItemPresetAvatarBinding
import com.safphere.launcher.util.AvatarUtils

/**
 * 联系人添加/编辑：姓名 + 长号 + 短号 + 头像（相册/预设/通讯录导入）。
 * 编辑入口：intent extra "contact_id"；缺省为新增。
 */
class ContactEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "contact_id"
        fun edit(context: android.content.Context, id: Long): Intent =
            Intent(context, ContactEditActivity::class.java).putExtra(EXTRA_ID, id)
    }

    private lateinit var binding: ActivityContactEditBinding
    private var contactId: Long = -1L
    private var currentAvatar: String = ""
    private var oldAvatarFile: String = ""

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            AvatarUtils.importFromGallery(this, it)?.let { path ->
                currentAvatar = path
                renderAvatar()
            } ?: toast("图片读取失败，请换一张")
        }
    }

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        
        val uri = result.data?.data ?: return@registerForActivityResult
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("需要「通讯录」权限才能导入")
            return@registerForActivityResult
        }
        importFromSystemContacts(uri)
    }

    private val requestReadContacts = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactId = intent.getLongExtra(EXTRA_ID, -1L)
        val editing = contactId > 0
        val contact = if (editing) Prefs.contactById(contactId) else null
        if (editing && contact == null) { finish(); return }

        contact?.let {
            binding.editName.setText(it.name)
            binding.editPhone.setText(it.phone)
            binding.editShort.setText(it.shortNum)
            currentAvatar = it.avatar
            oldAvatarFile = if (it.avatar.startsWith("preset:") || it.avatar.isBlank()) "" else it.avatar
        }
        renderAvatar()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDelete.visibility = if (editing) View.VISIBLE else View.GONE
        binding.btnPickAvatar.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnImport.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestReadContacts.launch(Manifest.permission.READ_CONTACTS)
            }
            pickContact.launch(
                Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
            )
        }
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { delete() }

        binding.presetGrid.layoutManager = GridLayoutManager(this, 4)
        binding.presetGrid.adapter = PresetAdapter { emoji ->
            currentAvatar = "preset:$emoji"
            renderAvatar()
        }
    }

    private fun renderAvatar() {
        val temp = Contact(contactId.takeIf { it > 0 } ?: 0L, binding.editName.text.toString()
            .ifBlank { "乐" }, "", "", currentAvatar)
        AvatarUtils.render(this, temp, binding.avatarImg, binding.avatarPreset)
    }

    private fun importFromSystemContacts(uri: Uri) {
        try {
            val cr = contentResolver
            cr.query(uri, arrayOf(ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
                
                if (!c.moveToFirst()) return
                val id = c.getString(0)
                val name = c.getString(1) ?: ""
                
                binding.editName.setText(name)

                val phone = StringBuilder()
                cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id), null
                )?.use { p ->
                    
                    if (p.moveToFirst()) {
                        phone.append(
                            p.getString(
                                p.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            ).replace(" ", "")
                        )
                    }
                }
                binding.editPhone.setText(phone.toString())

                val act = this
                Thread {
                    val path = AvatarUtils.importFromSystemContact(act, uri)
                    runOnUiThread {
                        path?.let { currentAvatar = it }
                        renderAvatar()
                    }
                }.start()
            }
        } catch (t: Throwable) {
            android.util.Log.w("Safphere", "import failed", t)
            toast("导入失败：${t.message}")
        }
    }

    private fun save() {
        val name = binding.editName.text.toString().trim()
        val phone = binding.editPhone.text.toString().trim().replace(" ", "")
        val short = binding.editShort.text.toString().trim().replace(" ", "")
        if (name.isEmpty() || phone.isEmpty()) {
            toast("请填写姓名和电话号码")
            return
        }
        val emergency = existingEmergency(contactId)
        val list = Prefs.contacts()
        if (contactId > 0) {
            val idx = list.indexOfFirst { it.id == contactId }
            if (idx >= 0) {
                val old = list[idx]
                if (old.avatar != currentAvatar) AvatarUtils.deleteAvatarFile(old.avatar)
                list[idx] = Contact(contactId, name, phone, short, currentAvatar, emergency)
            }
        } else {
            list.add(Contact(System.currentTimeMillis(), name, phone, short, currentAvatar, false))
        }
        Prefs.saveContacts(list)
        toast("已保存")
        finish()
    }

    private fun delete() {
        if (contactId <= 0) return
        val removing = Prefs.contactById(contactId)
        Prefs.saveContacts(Prefs.contacts().filterNot { it.id == contactId })
        removing?.let { AvatarUtils.deleteAvatarFile(it.avatar) }
        toast("已删除")
        finish()
    }

    /** 编辑已有联系人时保留其紧急标记（紧急联系人在子女模式中配置） */
    private fun existingEmergency(id: Long): Boolean =
        Prefs.contacts().firstOrNull { it.id == id }?.emergency ?: false

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 预设头像选择条 */
    private inner class PresetAdapter(val onPick: (String) -> Unit) :
        RecyclerView.Adapter<PresetAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPresetAvatarBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = AvatarUtils.PRESETS.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val emoji = AvatarUtils.PRESETS[position]
            holder.b.presetItem.text = emoji
            holder.b.presetItem.setOnClickListener { onPick(emoji) }
        }

        inner class VH(val b: ItemPresetAvatarBinding) : RecyclerView.ViewHolder(b.root)
    }
}
