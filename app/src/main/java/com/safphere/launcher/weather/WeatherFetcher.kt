package com.safphere.launcher.weather

import android.content.Context
import com.safphere.launcher.data.Prefs
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * 在线天气：Open-Meteo（免密钥）。
 * 城市名 → 经纬度（geocoding，结果缓存），再取当前天气+4日预报；结果缓存供离线展示。
 */
object WeatherFetcher {

    data class DayInfo(val emoji: String, val desc: String, val label: String, val min: Int, val max: Int)
    data class Weather(
        val temp: Int,          // 当前温度
        val emoji: String,      // 当前天气图标
        val desc: String,       // 当前天气描述
        val todayMin: Int, val todayMax: Int,
        val days: List<DayInfo>,// 未来几天（含明天）
        val tip: String         // 老人友好提示
    )

    private const val FETCH_INTERVAL_MS = 30 * 60 * 1000L   // 30分钟内不重复请求

    /** WMO天气码 → (emoji, 中文描述) */
    fun codeInfo(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "晴"
        1, 2 -> "⛅" to "多云"
        3 -> "☁️" to "阴"
        45, 48 -> "🌫️" to "雾"
        51, 53, 55 -> "🌦️" to "毛毛雨"
        56, 57 -> "🌧️" to "冻雨"
        61, 63 -> "🌧️" to "雨"
        65 -> "🌧️" to "大雨"
        66, 67 -> "🌧️" to "冻雨"
        71, 73 -> "❄️" to "雪"
        75, 77 -> "❄️" to "大雪"
        80, 81 -> "🌦️" to "阵雨"
        82 -> "⛈️" to "强阵雨"
        85, 86 -> "🌨️" to "阵雪"
        95 -> "⛈️" to "雷阵雨"
        96, 99 -> "⛈️" to "雷雨冰雹"
        else -> "🌤️" to "天气"
    }

    private fun tipOf(temp: Int, code: Int): String {
        val rainy = code in 51..67 || code in 80..82 || code >= 95
        return when {
            rainy -> "今天有雨，出门记得带伞"
            temp >= 33 -> "天气热，少晒太阳多喝水"
            temp <= 5 -> "天气冷，注意保暖加衣服"
            code == 0 || code == 1 -> "天气不错，适合出门散步"
            else -> "温度合适，出门走走吧"
        }
    }

    /** 缓存的上次结果（离线可显示）；无缓存返回null */
    fun cached(context: Context): Weather? {
        val json = Prefs.weatherCacheJson
        if (json.isBlank()) return null
        return runCatching { parse(JSONObject(json)) }.getOrNull()
    }

    fun isFresh(): Boolean =
        System.currentTimeMillis() - Prefs.weatherFetchedAt < FETCH_INTERVAL_MS

    /** 异步刷新（城市→经纬度→天气），成功/失败回调主线程 */
    fun refreshAsync(context: Context, onDone: (Weather?) -> Unit) {
        thread(name = "weather") {
            val w = runCatching { fetch(context) }.getOrNull()
            if (w != null) {
                Prefs.weatherFetchedAt = System.currentTimeMillis()
            }
            android.os.Handler(context.mainLooper).post { onDone(w) }
        }
    }

    private fun fetch(context: Context): Weather {
        // 自动定位：设备最后已知位置 → 坐标直取天气 → 反解城市名；失败回退手动城市
        if (Prefs.weatherAutoLocation) {
            val loc = lastKnownLocation(context)
            if (loc != null) {
                runCatching {
                    val url = "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                        "&current_weather=true&daily=temperature_2m_max,temperature_2m_min,weather_code" +
                        "&timezone=auto&forecast_days=4"
                    val weather = parse(JSONObject(httpGet(url)))
                    Prefs.weatherLocateName = reverseCityName(context, loc)
                    return weather
                }
            }
            // 定位失败 → 落到下方手动城市流程
        }

        var latlon = Prefs.weatherLatLon
        if (Prefs.weatherAutoLocation) {
            // 自动定位模式下不用手动城市缓存（避免拿着北京的缓存坐标）
            latlon = ""
        }
        if (latlon.isBlank()) {
            latlon = geocode(Prefs.weatherCity) ?: "39.9042,116.4074" // 兜底北京
            Prefs.weatherLatLon = latlon
        }
        val (lat, lon) = latlon.split(",").let { it[0] to it[1] }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current_weather=true&daily=temperature_2m_max,temperature_2m_min,weather_code" +
            "&timezone=auto&forecast_days=4"
        val json = httpGet(url)
        val weather = parse(JSONObject(json))
        Prefs.weatherCacheJson = json
        return weather
    }

    /** 最后已知位置：gps/network/passive 三源取最新（7 天内有效；天气只需城市级精度） */
    private fun lastKnownLocation(context: Context): android.location.Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return null
        var best: android.location.Location? = null
        for (p in listOf(android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER)) {
            runCatching {
                val l = lm.getLastKnownLocation(p) ?: return@runCatching
                if (System.currentTimeMillis() - l.time > 7L * 24 * 3600 * 1000) return@runCatching
                if (best == null || l.time > best!!.time) best = l
            }
        }
        return best
    }

    /** 坐标 → 城市显示名（系统 Geocoder 反解；失败显示"当前位置"） */
    private fun reverseCityName(context: Context, loc: android.location.Location): String {
        return runCatching {
            val geo = android.location.Geocoder(context, java.util.Locale.CHINA)
            if (!android.location.Geocoder.isPresent()) return "当前位置"
            val list = geo.getFromLocation(loc.latitude, loc.longitude, 1)
            val a = list?.firstOrNull()
            a?.locality?.removeSuffix("市")
                ?: a?.subAdminArea?.removeSuffix("区")
                ?: a?.adminArea?.removeSuffix("市")
                ?: "当前位置"
        }.getOrDefault("当前位置")
    }

    /** 天气卡显示用的城市名（自动定位=定位解析名，否则手动城市） */
    fun displayName(): String =
        if (Prefs.weatherAutoLocation && Prefs.weatherLocateName.isNotBlank())
            Prefs.weatherLocateName else Prefs.weatherCity

    private fun geocode(city: String): String? {
        val q = URLEncoder.encode(city, "UTF-8")
        val json = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=zh")
        val result = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return null
        return "${result.getDouble("latitude")},${result.getDouble("longitude")}"
    }

    private fun parse(o: JSONObject): Weather {
        val cur = o.getJSONObject("current_weather")
        val temp = cur.getDouble("temperature").toInt()
        val code = cur.getInt("weathercode")
        val (emoji, desc) = codeInfo(code)

        val daily = o.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val maxs = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        val codes = daily.getJSONArray("weather_code")

        val days = mutableListOf<DayInfo>()
        for (i in 0 until times.length()) {
            val (e, d) = codeInfo(codes.getInt(i))
            val label = when (i) {
                0 -> "今天"
                1 -> "明天"
                2 -> "后天"
                else -> times.getString(i).substring(5).replace("-", "/")
            }
            days.add(DayInfo(e, d, label, mins.getInt(i), maxs.getInt(i)))
        }
        return Weather(
            temp = temp, emoji = emoji, desc = desc,
            todayMin = days.firstOrNull()?.min ?: temp,
            todayMax = days.firstOrNull()?.max ?: temp,
            days = days.drop(1),
            tip = tipOf(temp, code)
        )
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
        } finally {
            conn.disconnect()
        }
    }
}
