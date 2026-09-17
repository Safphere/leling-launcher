package com.safphere.launcher.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的 chat/completions 客户端（默认智谱GLM开放平台，可在子女模式改任何兼容服务）。
 * POST {baseUrl}  Body: {model, messages:[{role,content}...]}
 * Authorization: Bearer {apiKey}
 */
object LlmClient {

    private const val SYSTEM_PROMPT =
        "你是「小乐」，一台老人手机桌面上的语音助手。回答规则：口语化、说人话；" +
            "每次最多三句话；不要用任何表情符号、英文或专业术语；" +
            "说话要亲切，像自家孩子跟爸妈聊天。如果被问健康问题，提醒去医院问医生。"

    data class Msg(val role: String, val content: String)

    /** 同步请求，需在后台线程调用。失败抛异常。按URL自动识别协议：
     *  - OpenAI 兼容（.../chat/completions）
     *  - Anthropic Messages（URL含 anthropic 或以 /v1/messages 结尾，自动补 /v1/messages） */
    fun chat(baseUrl: String, apiKey: String, model: String, history: List<Msg>): String {
        val url = baseUrl.trim()
        if (!url.startsWith("https://")) {
            android.util.Log.w("LlmClient", "non-https endpoint, key may go cleartext")
        }
        val isAnthropic = url.contains("anthropic", ignoreCase = true) ||
            url.substringAfterLast('/').equals("messages", ignoreCase = true)
        return if (isAnthropic) chatAnthropic(url, apiKey, model, history)
        else chatOpenAi(url, apiKey, model, history)
    }

    private fun chatOpenAi(baseUrl: String, apiKey: String, model: String, history: List<Msg>): String {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            history.takeLast(8).forEach {
                put(JSONObject().put("role", it.role).put("content", it.content))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.6)
            .put("max_tokens", 300)

        val conn = URL(baseUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val text = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = text?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            } ?: throw RuntimeException("HTTP $code 无响应")

            if (code !in 200..299) {
                throw RuntimeException("HTTP $code: ${response.take(160)}")
            }
            JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } finally {
            conn.disconnect()
        }
    }
    /** Anthropic Messages 协议（Claude 官方/GLM 中转等）：x-api-key 鉴权，content[] 取 text 块 */
    private fun chatAnthropic(baseUrl: String, apiKey: String, model: String, history: List<Msg>): String {
        val endpoint = if (baseUrl.substringAfterLast('/').equals("messages", ignoreCase = true))
            baseUrl else baseUrl.trimEnd('/') + "/v1/messages"

        val msgs = JSONArray().apply {
            history.takeLast(8).filter { it.role != "system" }.forEach {
                put(JSONObject().put("role", it.role).put("content", it.content))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("system", SYSTEM_PROMPT)
            .put("messages", msgs)
            .put("max_tokens", 400)

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            } ?: throw RuntimeException("HTTP $code 无响应")
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${response.take(160)}")

            // content[] 数组：拼接所有 type=="text" 的块（跳过 thinking 等中间块）
            val sb = StringBuilder()
            val blocks = JSONObject(response).optJSONArray("content") ?: JSONArray()
            for (i in 0 until blocks.length()) {
                val b = blocks.optJSONObject(i) ?: continue
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) throw RuntimeException("模型未返回文本")
            text
        } finally {
            conn.disconnect()
        }
    }

}
