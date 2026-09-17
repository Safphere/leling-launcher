package com.safphere.launcher.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.safphere.launcher.contacts.ContactEditActivity
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.databinding.ActivitySetupWizardBinding

/** 首次配置向导（面向子女，4步）：欢迎 → 设为默认桌面 → 权限 → 添加联系人 → 完成 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding
    private var step = 0

    private val stepViews by lazy {
        listOf(binding.step0, binding.step1, binding.step2, binding.step3)
    }
    private val dots by lazy {
        listOf(binding.dot0, binding.dot1, binding.dot2, binding.dot3)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSetHome.setOnClickListener {
            // 打开系统「默认应用-主屏幕应用」设置
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                .onFailure {
                    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    startActivity(Intent.createChooser(home, "选择乐龄桌面"))
                }
        }

        binding.btnGrantPerms.setOnClickListener { requestAllPermissions() }
        binding.btnPermStatus.setOnClickListener {
            startActivity(android.content.Intent(this, com.safphere.launcher.perm.PermissionStatusActivity::class.java))
        }

        binding.btnAddContact.setOnClickListener {
            startActivity(Intent(this, ContactEditActivity::class.java))
        }

        binding.btnPrev.setOnClickListener {
            if (step > 0) { step--; render() }
        }
        binding.btnNext.setOnClickListener {
            if (step < 3) { step++; render() } else finish()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        renderPermTexts()
    }

    private fun render() {
        stepViews.forEachIndexed { i, v -> v.visibility = if (i == step) View.VISIBLE else View.GONE }
        dots.forEachIndexed { i, v ->
            v.setBackgroundResource(
                if (i <= step) com.safphere.launcher.R.drawable.bg_btn_blue
                else com.safphere.launcher.R.drawable.bg_add_contact
            )
        }
        binding.btnPrev.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        binding.btnNext.text = if (step == 3) "完成 🎉" else "下一步"
        renderPermTexts()
    }

    private fun requiredPermissions(): List<String> {
        val list = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        ).toMutableList()
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun requestAllPermissions() {
        val need = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) {
            android.widget.Toast.makeText(this, "全部权限已授予 ✓", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            requestPermissions(need.toTypedArray(), 100)
        }
    }

    private fun renderPermTexts() {
        if (step != 2) return
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        binding.txtPermPhone.text = mark(granted(Manifest.permission.CALL_PHONE)) + " 电话（拨号）"
        binding.txtPermContacts.text = mark(granted(Manifest.permission.READ_CONTACTS)) + " 通讯录（导入联系人）"
        binding.txtPermMic.text = mark(granted(Manifest.permission.RECORD_AUDIO)) + " 麦克风（语音问答）"
        binding.txtPermSms.text = mark(granted(Manifest.permission.SEND_SMS)) + " 短信（每天自动查询流量）"
    }

    private fun mark(ok: Boolean) = if (ok) "✅" else "❌"
}
