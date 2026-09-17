package com.safphere.launcher.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.safphere.launcher.ai.LlmClient
import org.json.JSONObject
import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import java.io.ByteArrayOutputStream
import com.safphere.launcher.data.Prefs

/**
 * AI Agent 控制器：
 * 截图 → 视觉模型分析 → 解析动作(tap/swipe/back/home/done) → 执行 → 再截图验证
 * 循环最多5次，每次动作间等待2秒让系统响应。
 */
object AgentController {

    data class AgentResult(
        val success: Boolean,
        val reply: String,          // 给用户的说明
        val steps: Int = 0
    )

    /** 下采样截图到 base64 PNG（控制请求体大小） */
    fun bitmapToBase64(bmp: Bitmap, maxDim: Int = 540): String {
        val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 60, out)
        small.recycle()
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /**
     * Agent 循环：
     * 1. 截屏 → 2. 发给视觉模型带用户请求 → 3. 解析动作并执行 → 4. 重复直到 done 或超次
     * 全程后台线程，回调主线程。
     */
    fun runAgent(
        context: Context,
        userRequest: String,
        maxSteps: Int = 5,
        onStep: (stepNum: Int, description: String) -> Unit = { _, _ -> },
        onDone: (AgentResult) -> Unit
    ) {
        Thread {
            var lastReply = ""
            var step = 0
            try {
                for (i in 1..maxSteps) {
                    step = i
                    val svc = AgentAccessibilityService.instance
                        ?: throw RuntimeException("无障碍服务未运行")
                    // 1. 截图
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var bmp: Bitmap? = null
                    runOnMain { svc.takeScreenshot { b -> bmp = b; latch.countDown() } }
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    val screenshot = bmp ?: throw RuntimeException("截图失败")
                    val b64 = bitmapToBase64(screenshot)

                    // 2. 发给视觉模型
                    val response = queryVisionModel(context, userRequest, b64, i)
                    val json = JSONObject(response)

                    if (json.optBoolean("done", false)) {
                        lastReply = json.optString("reply", "操作完成")
                        break
                    }

                    // 3. 执行动作
                    val action = json.optString("action", "")
                    when (action) {
                        "tap" -> {
                            val x = json.optDouble("x", 0.5) / 1000.0 * getScreenWidth(context)
                            val y = json.optDouble("y", 0.5) / 1000.0 * getScreenHeight(context)
                            val latch2 = java.util.concurrent.CountDownLatch(1)
                            runOnMain {
                                AgentGestures.tap(x.toFloat(), y.toFloat()) { latch2.countDown() }
                            }
                            latch2.await(3, java.util.concurrent.TimeUnit.SECONDS)
                            Thread.sleep(1500) // 等界面响应
                        }
                        "swipe" -> {
                            val x1 = json.optDouble("x1", 0.5) / 1000.0 * getScreenWidth(context)
                            val y1 = json.optDouble("y1", 0.5) / 1000.0 * getScreenHeight(context)
                            val x2 = json.optDouble("x2", 0.5) / 1000.0 * getScreenWidth(context)
                            val y2 = json.optDouble("y2", 0.5) / 1000.0 * getScreenHeight(context)
                            val latch2 = java.util.concurrent.CountDownLatch(1)
                            runOnMain {
                                AgentGestures.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()) { latch2.countDown() }
                            }
                            latch2.await(3, java.util.concurrent.TimeUnit.SECONDS)
                            Thread.sleep(1500)
                        }
                        "back" -> {
                            runOnMain {
                                AgentAccessibilityService.instance?.performGlobalAction(
                                    AccessibilityService.GLOBAL_ACTION_BACK)
                            }
                            Thread.sleep(1500)
                        }
                        "home" -> {
                            runOnMain {
                                AgentAccessibilityService.instance?.performGlobalAction(
                                    AccessibilityService.GLOBAL_ACTION_HOME)
                            }
                            Thread.sleep(1500)
                        }
                    }
                    onStep(i, json.optString("reply", "执行: $action"))
                }
                if (lastReply.isBlank()) lastReply = "操作完成"
            } catch (e: Exception) {
                lastReply = lastReply.ifBlank { "操作失败：${e.message?.take(80)}" }
            }
            val ctx = context.applicationContext
            android.os.Handler(ctx.mainLooper).post {
                onDone(AgentResult(true, lastReply, step))
            }
        }.start()
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun getScreenWidth(context: Context): Int =
        context.resources.displayMetrics.widthPixels

    private fun getScreenHeight(context: Context): Int =
        context.resources.displayMetrics.heightPixels

    /** 调视觉模型：截屏 + 用户请求 → 返回 JSON 动作或 done */
    private fun queryVisionModel(context: Context, userRequest: String, screenshotB64: String, step: Int): String {
        val baseUrl = Prefs.aiUrl
        val apiKey = Prefs.aiKey
        val model = Prefs.aiModel
        if (apiKey.isBlank()) throw RuntimeException("API Key 未配置")

        val isAnthropic = baseUrl.contains("anthropic", true)

        val screenDesc = "这是手机屏幕截图（分辨率约1080x2400）。当前是第${step}步操作。"
        val systemPrompt = """你是一个老人手机助手，帮助老年人在手机上完成操作。
用户想：$userRequest
请分析截图，返回严格的 JSON（不要 markdown 代码块，不要多余文本）：
{"action":"tap","x":500,"y":1200,"reply":"帮您点了xx"}
或 {"action":"swipe","x1":540,"y1":1800,"x2":540,"y2":600,"reply":"帮您滑了"}
或 {"action":"back","reply":"返回上一页"}
或 {"action":"home","reply":"回到桌面"}
或 {"action":"text","text":"要输入的文字","reply":"输入了xx"}
或 {"done":true,"reply":"操作完成了"}
坐标是0-1000范围内的相对位置。如果截屏里已经能看到完成用户需求的结果，返回 {"done":true,"reply":"已完成"}。
只返回 JSON，不要其他任何内容。"""

        if (isAnthropic) {
            return anthropicVisionCall(baseUrl, apiKey, model, screenDesc, userRequest, screenshotB64)
        } else {
            return openAiVisionCall(baseUrl, apiKey, model, screenDesc, userRequest, screenshotB64)
        }
    }

    private fun anthropicVisionCall(
        baseUrl: String, apiKey: String, model: String,
        screenDesc: String, userRequest: String, b64: String
    ): String {
        val endpoint = if (baseUrl.substringAfterLast('/').equals("messages", true))
            baseUrl else baseUrl.trimEnd('/') + "/v1/messages"

        val body = org.json.JSONObject().apply {
            put("model", model)
            put("max_tokens", 500)
            put("system", AGENT_SYSTEM_PROMPT + "\n用户想: " + userRequest)
            put("messages", org.json.JSONArray().put(
                org.json.JSONObject().put("role", "user").put("content",
                    org.json.JSONArray().put(
                        org.json.JSONObject().put("type", "image")
                            .put("source", org.json.JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/jpeg")
                                .put("data", b64))
                    ).put(org.json.JSONObject().put("type", "text")
                        .put("text", "$screenDesc\n用户想: $userRequest\n请分析屏幕并返回JSON动作。"))
                )))
        }

        val conn = java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.use {
                java.io.BufferedReader(java.io.InputStreamReader(it, Charsets.UTF_8)).readText()
            } ?: throw RuntimeException("HTTP $code")
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${response.take(120)}")
            extractAnthropicText(response)
        } finally { conn.disconnect() }
    }

    private fun openAiVisionCall(
        baseUrl: String, apiKey: String, model: String,
        screenDesc: String, userRequest: String, b64: String
    ): String {
        val body = org.json.JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("role", "user")
                put("content", org.json.JSONArray()
                    .put(org.json.JSONObject().put("type", "text")
                        .put("text", "$AGENT_SYSTEM_PROMPT\n$screenDesc\n用户想: $userRequest\n请分析屏幕并返回JSON动作。"))
                    .put(org.json.JSONObject().put("type", "image_url")
                        .put("image_url", org.json.JSONObject()
                            .put("url", "data:image/jpeg;base64,$b64"))))
            }))
        }
        val conn = java.net.URL(baseUrl).openConnection() as java.net.HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.use {
                java.io.BufferedReader(java.io.InputStreamReader(it, Charsets.UTF_8)).readText()
            } ?: throw RuntimeException("HTTP $code")
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${response.take(120)}")
            org.json.JSONObject(response)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } finally { conn.disconnect() }
    }

    /** 从 Anthropic Messages 响应中提取所有 text 块内容 */
    private fun extractAnthropicText(response: String): String {
        val content = org.json.JSONObject(response).optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim()
    }

    private const val AGENT_SYSTEM_PROMPT =
        "你是一个老人手机助手的操作引擎。用户告诉你想做什么，你分析手机截屏并返回JSON格式的操作指令。" +
        "坐标范围0-1000（相对坐标，左上角(0,0)，右下角(1000,2400)）。" +
        "只返回JSON，不要markdown代码块，不要解释。"
}
