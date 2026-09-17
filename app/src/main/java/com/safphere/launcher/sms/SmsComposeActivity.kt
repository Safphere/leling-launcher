package com.safphere.launcher.sms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.safphere.launcher.databinding.ActivitySmsComposeBinding
import com.safphere.launcher.databinding.ItemPhraseBinding
import com.safphere.launcher.tts.Speaker

/**
 * 给联系人发短信：大输入框 + 常用短语一键填入 + 大发送按钮。
 * 优先 SmsManager 直接发送（老人少一步）；无权限时落到系统短信应用预填。
 */
class SmsComposeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_PHONE = "phone"

        val PHRASES = listOf(
            "我很好，勿念",
            "吃饭了吗？",
            "记得吃药",
            "我出门了，晚上回",
            "到家了",
            "有空常联系 ❤️"
        )
    }

    private lateinit var binding: ActivitySmsComposeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val phone = (intent.getStringExtra(EXTRA_PHONE) ?: "").replace(" ", "")

        binding.smsTo.text = if (name.isBlank()) "发短信" else "给$name 发短信"
        binding.smsNumber.text = phone
        binding.btnBack.setOnClickListener { finish() }

        binding.phraseList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.phraseList.adapter = object : RecyclerView.Adapter<PhraseVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhraseVH =
                PhraseVH(ItemPhraseBinding.inflate(layoutInflater, parent, false))

            override fun getItemCount(): Int = PHRASES.size

            override fun onBindViewHolder(holder: PhraseVH, position: Int) {
                holder.b.phraseText.text = PHRASES[position]
                holder.b.phraseText.setOnClickListener {
                    binding.editSms.setText(PHRASES[position])
                    binding.editSms.setSelection(PHRASES[position].length)
                }
            }
        }

        binding.btnSendSms.setOnClickListener {
            val text = binding.editSms.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "先写点内容再发", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            send(phone, text)
        }
    }

    private fun send(phone: String, text: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            sendNow(phone, text)
        } else {
            Speaker.speak("需要短信权限")
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                binding.editSms.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    sendNow(intent.getStringExtra(EXTRA_PHONE) ?: "", it)
                }
            } else {
                fallbackToSmsApp(binding.editSms.text.toString().trim())
            }
        }
    }

    private fun sendNow(phone: String, text: String) {
        runCatching {
            @Suppress("DEPRECATION")
            android.telephony.SmsManager.getDefault().sendTextMessage(phone, null, text, null, null)
            Speaker.speak("短信已发送")
            Toast.makeText(this, "已发送 ✓", Toast.LENGTH_SHORT).show()
            finish()
        }.onFailure {
            // 发送失败（无SIM等）落到系统短信应用
            fallbackToSmsApp(text)
        }
    }

    /** 无权限/发送失败：打开系统短信应用并预填 */
    private fun fallbackToSmsApp(text: String) {
        val phone = (intent.getStringExtra(EXTRA_PHONE) ?: "").replace(" ", "")
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_SENDTO,
                    android.net.Uri.parse("smsto:$phone")
                ).putExtra("sms_body", text)
            )
            finish()
        }
    }

    private class PhraseVH(val b: ItemPhraseBinding) : RecyclerView.ViewHolder(b.root)
}
