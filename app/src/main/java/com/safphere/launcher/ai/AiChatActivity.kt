package com.safphere.launcher.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.safphere.launcher.R
import com.safphere.launcher.data.Prefs
import com.safphere.launcher.databinding.ActivityAiChatBinding
import com.safphere.launcher.databinding.ItemMsgBinding
import com.safphere.launcher.tts.Speaker
import kotlin.concurrent.thread

/**
 * AI语音问答：按住大麦克风说话（SpeechRecognizer），
 * 本地技能优先，其次大模型（OpenAI兼容，默认GLM），回答大字显示+TTS朗读。
 */
class AiChatActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var binding: ActivityAiChatBinding
    private val messages = mutableListOf<LlmClient.Msg>()
    private lateinit var adapter: MsgAdapter
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        adapter = MsgAdapter()
        binding.msgList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true   // 消息贴底，向上滚动看历史
        }
        binding.msgList.itemAnimator = null
        binding.msgList.adapter = adapter
        addAi("您好，我是小乐。按住下面的大麦克风说话，也可以打字问我。")

        binding.btnSend.setOnClickListener {
            val q = binding.editAsk.text.toString().trim()
            if (q.isNotEmpty()) {
                binding.editAsk.setText("")
                addUser(q)
                handleAsk(q)
            }
        }

        binding.btnMic.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startListening()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopListening()
            }
            true
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    // ---------------- 语音识别 ----------------

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(this)
            }
        }
        return recognizer
    }

    private fun startListening() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val r = ensureRecognizer()
        if (r == null) {
            binding.statusText.text = "这台手机没有语音功能\n请用打字问我"
            return
        }
        listening = true
        binding.statusText.text = getString(R.string.ai_listening)
        binding.btnMic.alpha = 0.6f
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // 语言由子女模式配置（普通话zh-CN/粤语/英语…方言识别取决于设备引擎，见文档）
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.aiLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        binding.btnMic.alpha = 1f
        binding.statusText.text = getString(R.string.ai_thinking)
        runCatching { recognizer?.stopListening() }
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        binding.statusText.text = getString(R.string.ai_hint)
        if (text.isNullOrBlank()) {
            onSpeechFail()
        } else {
            addUser(text)
            handleAsk(text)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.let { binding.statusText.text = it }
    }

    override fun onError(error: Int) {
        binding.statusText.text = getString(R.string.ai_hint)
        onSpeechFail()
    }

    private fun onSpeechFail() {
        if (binding.statusText.text != getString(R.string.ai_hint)) return
        binding.statusText.text = getString(R.string.ai_no_answer)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------------- 问答逻辑 ----------------

    private fun handleAsk(query: String) {
        // 1) 离线技能
        val local = LocalSkills.match(this, query, Prefs.contacts())
        if (local != null) {
            if (local is LocalSkills.Answer.Call) {
                addAi(local.text)
                speak(local.text)
                dial(local.contact)
            } else {
                addAi((local as LocalSkills.Answer.Text).text)
                speak(local.text)
            }
            return
        }

        // 2) 大模型
        if (Prefs.aiKey.isBlank()) {
            val fallback = "这个问题需要联网才能回答。可以先问我时间、日期、电量，或者说「打电话给谁」。"
            addAi(fallback)
            speak(fallback)
            return
        }
        messages.add(LlmClient.Msg("user", query))
        addAi(getString(R.string.ai_thinking))
        thread(name = "llm") {
            val reply = runCatching {
                LlmClient.chat(Prefs.aiUrl, Prefs.aiKey, Prefs.aiModel, messages)
            }.getOrElse {
                messages.removeLastOrNull()
                "网络不太好，稍后再问我一次。"
            }
            messages.add(LlmClient.Msg("assistant", reply))
            runOnUiThread {
                adapter.replaceLast(reply)
                speak(reply)
            }
        }
    }

    private fun dial(contact: com.safphere.launcher.data.Contact) {
        val number = contact.phone.ifBlank { contact.shortNum }
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    }

    private fun speak(text: String) {
        if (Prefs.aiReadAloud) Speaker.speak(text)
    }

    private fun addUser(text: String) {
        adapter.add(user = text)
        binding.msgList.scrollToPosition(adapter.itemCount - 1)
    }

    private fun addAi(text: String) {
        adapter.add(ai = text)
        binding.msgList.scrollToPosition(adapter.itemCount - 1)
    }

    // ---------------- 消息列表 ----------------

    data class Row(val user: String? = null, val ai: String? = null)

    inner class MsgAdapter : RecyclerView.Adapter<MsgVH>() {
        private val rows = mutableListOf<Row>()

        fun add(user: String? = null, ai: String? = null) {
            rows.add(Row(user, ai))
            if (rows.size > 40) rows.removeAt(0)
            notifyItemInserted(rows.size - 1)
        }

        fun replaceLast(ai: String) {
            if (rows.isNotEmpty()) {
                rows[rows.size - 1] = Row(ai = ai)
                notifyItemChanged(rows.size - 1)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgVH =
            MsgVH(ItemMsgBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: MsgVH, position: Int) {
            val row = rows[position]
            holder.b.msgUser.visibility = if (row.user != null) View.VISIBLE else View.GONE
            holder.b.msgUser.text = row.user ?: ""
            holder.b.msgAiBox.visibility = if (row.ai != null) View.VISIBLE else View.GONE
            holder.b.msgAi.text = row.ai ?: ""
            // 单条消息重听（只对助手消息有效）
            holder.b.btnSpeakMsg.setOnClickListener {
                row.ai?.let { Speaker.speak(it) }
            }
        }
    }

    class MsgVH(val b: ItemMsgBinding) : RecyclerView.ViewHolder(b.root)
}
