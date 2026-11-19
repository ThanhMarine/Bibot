package com.bibot.app.feature.weather

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import com.bibot.app.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibot.app.ui.player.ControlInfoText
import com.bibot.app.ui.player.ControlPlayButton
import com.bibot.app.ui.player.FloatingControlScaffold
import com.bibot.app.ui.player.PlayerViewModel
import com.bibot.app.ui.player.contentBottomInset

data class DayForecast(
    val date: String,      // "2026-09-28"
    val dayName: String,   // "Thứ 2"
    val condition: String, // tiếng Việt (bảng WMO)
    val icon: String,
    val tempMax: Double,
    val tempMin: Double,
    val precipProb: Int    // %
)

data class WeatherData(
    val temperature: Double,
    val condition: String,
    val icon: String,
    val humidity: Int,
    val windSpeed: Double,
    val windDirText: String, // "" hoặc "(Tây Nam)"
    val location: String,
    val feelsLike: Double,
    val tempMax: Double,     // hôm nay (daily[0])
    val tempMin: Double,
    val cloudCover: Int,
    val uvIndex: Double,
    val pressure: Double,
    val forecast: List<DayForecast>
)

data class City(val id: String, val label: String, val chip: String, val lat: Double, val lon: Double)

object WeatherApi {
    private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    // Đủ 5 thành phố như bản Raspberry Pi (weather.py LOCATIONS)
    val CITIES = listOf(
        City("hcm", "Thành phố Hồ Chí Minh", "TP.HCM", 10.8231, 106.6297),
        City("hanoi", "Hà Nội", "Hà Nội", 21.0285, 105.8542),
        City("danang", "Đà Nẵng", "Đà Nẵng", 16.0544, 108.2022),
        City("haiphong", "Hải Phòng", "Hải Phòng", 20.8449, 106.6881),
        City("cantho", "Cần Thơ", "Cần Thơ", 10.0452, 105.7469)
    )

    // Bảng WMO tiếng Việt — port từ services/weather.py WMO_CODES
    private val WMO_CODES = mapOf(
        0 to "Trời quang đãng, không mây",
        1 to "Trời chủ yếu có nắng, ít mây",
        2 to "Trời nhiều mây rải rác",
        3 to "Trời âm u, nhiều mây",
        45 to "Có sương mù",
        48 to "Có sương mù dày",
        51 to "Mưa phùn nhẹ",
        53 to "Mưa phùn vừa",
        55 to "Mưa phùn dày hạt",
        56 to "Mưa phùn buốt nhẹ",
        57 to "Mưa phùn buốt dày",
        61 to "Có mưa rào nhẹ",
        63 to "Có mưa vừa",
        65 to "Có mưa to",
        66 to "Mưa đóng băng nhẹ",
        67 to "Mưa đóng băng nặng",
        71 to "Tuyết rơi nhẹ",
        73 to "Tuyết rơi vừa",
        75 to "Tuyết rơi dày",
        77 to "Hạt tuyết rơi",
        80 to "Mưa rào từng cơn nhẹ",
        81 to "Mưa rào vừa",
        82 to "Mưa rào rất to",
        85 to "Mưa tuyết nhẹ",
        86 to "Mưa tuyết nặng",
        95 to "Có dông sét",
        96 to "Dông kèm mưa đá nhỏ",
        99 to "Dông kèm mưa đá to"
    )

    private fun iconFor(code: Int): String = when (code) {
        0 -> "sunny"
        1, 2 -> "partly"
        3 -> "cloudy"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
        71, 73, 75, 77, 85, 86 -> "snow"
        95, 96, 99 -> "storm"
        else -> "temp"
    }

    /** Map key điều kiện thời tiết → Material Icon (đồng bộ UI, không dùng emoji). */
    fun weatherIcon(key: String): ImageVector = when (key) {
        "sunny" -> Icons.Filled.WbSunny
        "partly" -> Icons.Filled.WbCloudy
        "cloudy" -> Icons.Filled.Cloud
        "fog" -> Icons.Filled.FilterDrama
        "drizzle" -> Icons.Filled.Grain
        "rain" -> Icons.Filled.Umbrella
        "snow" -> Icons.Filled.AcUnit
        "storm" -> Icons.Filled.Thunderstorm
        else -> Icons.Filled.Thermostat
    }

    private fun windDirectionText(deg: Double?): String {
        if (deg == null) return ""
        val dirs = listOf(
            "Bắc", "Bắc Đông Bắc", "Đông Bắc", "Đông Đông Bắc",
            "Đông", "Đông Đông Nam", "Đông Nam", "Nam Đông Nam",
            "Nam", "Nam Tây Nam", "Tây Nam", "Tây Tây Nam",
            "Tây", "Tây Tây Bắc", "Tây Bắc", "Bắc Tây Bắc"
        )
        val idx = (Math.round(deg / 22.5) % 16 + 16) % 16
        return "(${dirs[idx.toInt()]})"
    }

    private fun dayNameOf(dateIso: String): String = try {
        val names = listOf("Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7", "Chủ Nhật")
        names[LocalDate.parse(dateIso).dayOfWeek.value - 1]
    } catch (_: Exception) { dateIso }

    /** Ten thu tieng Anh cho UI EN (du lieu goc van tieng Viet). */
    @androidx.compose.runtime.Composable
    fun localizedDayName(viName: String): String = when (viName) {
        "Thứ 2" -> stringResource(R.string.wx_dow_mon)
        "Thứ 3" -> stringResource(R.string.wx_dow_tue)
        "Thứ 4" -> stringResource(R.string.wx_dow_wed)
        "Thứ 5" -> stringResource(R.string.wx_dow_thu)
        "Thứ 6" -> stringResource(R.string.wx_dow_fri)
        "Thứ 7" -> stringResource(R.string.wx_dow_sat)
        "Chủ Nhật" -> stringResource(R.string.wx_dow_sun)
        else -> viName
    }

    /**
     * URL dùng `current=` (KHÔNG phải `current_weather=true`) — khớp với parser
     * đọc current.temperature_2m / weather_code / … như bản Pi weather.py.
     */
    private fun buildUrl(city: City): String =
        "$BASE_URL?latitude=${city.lat}&longitude=${city.lon}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation," +
            "weather_code,wind_speed_10m,wind_direction_10m,surface_pressure,cloud_cover,uv_index,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=3&timezone=Asia%2FHo_Chi_Minh"

    fun cacheFile(context: Context, cityId: String): java.io.File =
        java.io.File(com.bibot.app.data.files.FilePaths.cacheRoot(context), "weather_$cityId.json").also {
            try { it.parentFile?.mkdirs() } catch (_: Exception) {}
        }

    /** Cache TTL 30 phút; forceRefresh=true bỏ qua (nút Làm mới). Tải 1 lần → parse + cache. */
    suspend fun fetchCached(context: Context, city: City, forceRefresh: Boolean = false): Result<WeatherData> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                try {
                    val f = cacheFile(context, city.id)
                    if (f.exists()) {
                        val wrapper = JSONObject(f.readText())
                        val age = System.currentTimeMillis() - wrapper.optLong("cachedAt", 0L)
                        if (age in 0 until CACHE_TTL_MS) {
                            return@withContext Result.success(parse(wrapper.getJSONObject("payload"), city.label))
                        }
                    }
                } catch (_: Exception) { /* cache hỏng/hết hạn → tải mới */ }
            }
            try {
                val json = downloadJson(city)
                try {
                    val wrapper = JSONObject().put("cachedAt", System.currentTimeMillis()).put("payload", json)
                    cacheFile(context, city.id).writeText(wrapper.toString())
                } catch (_: Exception) {}
                Result.success(parse(json, city.label))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun downloadJson(city: City): JSONObject {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(buildUrl(city)).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("Máy chủ thời tiết phản hồi HTTP $code")
            return JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parse(json: JSONObject, location: String): WeatherData {
        val cur = json.getJSONObject("current")
        val code = cur.optInt("weather_code", 0)
        val temp = cur.optDouble("temperature_2m", 0.0)

        val daily = json.optJSONObject("daily")
        fun dailyArr(key: String) = daily?.optJSONArray(key)
        val maxArr = dailyArr("temperature_2m_max")
        val minArr = dailyArr("temperature_2m_min")
        val codeArr = dailyArr("weather_code")
        val probArr = dailyArr("precipitation_probability_max")
        val times = dailyArr("time")

        val forecast = mutableListOf<DayForecast>()
        if (times != null) {
            for (i in 0 until times.length()) {
                val date = times.optString(i)
                val dc = codeArr?.optInt(i, 0) ?: 0
                forecast += DayForecast(
                    date = date,
                    dayName = dayNameOf(date),
                    condition = WMO_CODES[dc] ?: "Thời tiết bình thường",
                    icon = iconFor(dc),
                    tempMax = maxArr?.optDouble(i, temp) ?: temp,
                    tempMin = minArr?.optDouble(i, temp) ?: temp,
                    precipProb = probArr?.optInt(i, 0) ?: 0
                )
            }
        }

        return WeatherData(
            temperature = temp,
            condition = WMO_CODES[code] ?: "Thời tiết bình thường",
            icon = iconFor(code),
            humidity = cur.optInt("relative_humidity_2m", 0),
            windSpeed = cur.optDouble("wind_speed_10m", 0.0),
            windDirText = windDirectionText(cur.optDouble("wind_direction_10m")),
            location = location,
            feelsLike = cur.optDouble("apparent_temperature", temp),
            tempMax = forecast.firstOrNull()?.tempMax ?: temp,
            tempMin = forecast.firstOrNull()?.tempMin ?: temp,
            cloudCover = cur.optInt("cloud_cover", 0),
            uvIndex = cur.optDouble("uv_index", 0.0),
            pressure = cur.optDouble("surface_pressure", 0.0),
            forecast = forecast
        )
    }

    /** Loi ky thuat -> thong bao de hieu theo ngon ngu UI. */
    fun humanError(context: android.content.Context, e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> context.getString(R.string.wx_err_noconn)
        is java.net.ConnectException -> context.getString(R.string.wx_err_noreach)
        is java.net.SocketTimeoutException -> context.getString(R.string.wx_err_slow)
        is org.json.JSONException -> context.getString(R.string.wx_err_invalid)
        else -> e.message ?: context.getString(R.string.news_unknown_err)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(
    modifier: Modifier = Modifier,
    // A3.2: controller audio chung (ExoPlayer + engine TTS) — đọc thời tiết đi qua đây.
    playerVm: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var weatherData by remember { mutableStateOf<WeatherData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var city by remember { mutableStateOf(WeatherApi.CITIES[0]) }
    var isSpeaking by remember { mutableStateOf(false) }

    suspend fun loadWeather(selected: City, forceRefresh: Boolean = false) {
        isLoading = true
        error = null
        val result = WeatherApi.fetchCached(context, selected, forceRefresh)
        result.fold(
            onSuccess = { weatherData = it },
            onFailure = { error = WeatherApi.humanError(context, it) }
        )
        isLoading = false
    }

    LaunchedEffect(city) { loadWeather(city) }

    fun buildWeatherText(w: WeatherData): String {
        val sb = StringBuilder()
        sb.append("Thời tiết tại ${w.location} hiện tại: ${w.condition}. ")
        sb.append("Nhiệt độ ${w.temperature.toInt()} độ C, cảm giác thực tế như ${w.feelsLike.toInt()} độ C. ")
        sb.append("Độ ẩm ${w.humidity} phần trăm, sức gió ${w.windSpeed.toInt()} kilomet mỗi giờ ${w.windDirText}. ")
        sb.append("Cao nhất hôm nay ${w.tempMax.toInt()} độ, thấp nhất ${w.tempMin.toInt()} độ C. ")
        if (w.forecast.size > 1) {
            sb.append("Dự báo các ngày tới: ")
            for (f in w.forecast.drop(1)) {
                sb.append("${f.dayName}: ${f.condition}, cao ${f.tempMax.toInt()} độ, thấp ${f.tempMin.toInt()} độ. ")
            }
        }
        return sb.toString()
    }

    fun speakWeather() {
        weatherData?.let { w ->
            val text = buildWeatherText(w)
            scope.launch {
                isSpeaking = true
                // A3.2: đọc qua controller — tự dừng ExoPlayer trước (không 2 nguồn chồng).
                try { playerVm.speakOffline(text) } finally { isSpeaking = false }
            }
        }
    }

    fun stopSpeaking() {
        // A3.2: 1 lệnh dừng duy nhất của app (ExoPlayer + engine TTS).
        playerVm.stopAll()
        isSpeaking = false
    }

    // 12.x: thanh điều khiển nổi + nền trong suốt (dùng chung) — không trừ chiều cao.
    val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    FloatingControlScaffold(
        alignment = if (isLandscape) Alignment.BottomEnd else Alignment.BottomCenter,
        barVisible = weatherData != null,
        info = {
            weatherData?.let { w ->
                ControlInfoText("${city.chip} • ${w.temperature.toInt()}°C ${w.condition}")
            }
        },
        controls = {
            ControlPlayButton(
                isPlaying = isSpeaking,
                onClick = { if (isSpeaking) stopSpeaking() else speakWeather() },
                contentDescription = if (isSpeaking) stringResource(R.string.news_cd_stop) else stringResource(R.string.wx_read)
            )
        }
    ) {
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(16.dp),
            contentPadding = PaddingValues(bottom = contentBottomInset()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(city.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { loadWeather(city, forceRefresh = true) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_refresh), fontSize = 12.sp)
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WeatherApi.CITIES.forEach { c ->
                        FilterChip(
                            selected = c.id == city.id,
                            onClick = { if (c.id != city.id) city = c },
                            label = { Text(c.chip, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (isLoading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            error?.let {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Text(stringResource(R.string.wx_check_net), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            weatherData?.let { w ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = WeatherApi.weatherIcon(w.icon),
                                    contentDescription = w.condition,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text("${w.temperature.toInt()}°C", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                    Text(w.condition, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(stringResource(R.string.wx_feels, w.location, w.feelsLike.toInt()), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                item { Text(stringResource(R.string.wx_details), style = MaterialTheme.typography.titleMedium) }

                val details = listOf(
                    Icons.Filled.WaterDrop to "Độ ẩm: ${w.humidity}%",
                    Icons.Filled.Air to "Gió: ${w.windSpeed.toInt()} km/h ${w.windDirText}".trimEnd(),
                    Icons.Filled.Thermostat to "Cao nhất hôm nay: ${w.tempMax.toInt()}°C",
                    Icons.Filled.NightsStay to "Thấp nhất hôm nay: ${w.tempMin.toInt()}°C",
                    Icons.Filled.Cloud to "Mây che phủ: ${w.cloudCover}%",
                    Icons.Filled.WbSunny to "Chỉ số UV: ${w.uvIndex}",
                    Icons.Filled.Compress to "Áp suất: ${w.pressure.toInt()} hPa"
                )

                items(details) { detail ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = detail.first,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(detail.second, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (w.forecast.isNotEmpty()) {
                    item { Spacer(Modifier.height(4.dp)); Text(stringResource(R.string.wx_forecast_days, w.forecast.size), style = MaterialTheme.typography.titleMedium) }
                    items(w.forecast) { f ->
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = WeatherApi.weatherIcon(f.icon),
                                    contentDescription = f.condition,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${WeatherApi.localizedDayName(f.dayName)} (${f.date})", style = MaterialTheme.typography.titleSmall)
                                    Text(f.condition, style = MaterialTheme.typography.bodySmall)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${f.tempMax.toInt()}° / ${f.tempMin.toInt()}°", style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.wx_precip, f.precipProb), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (!isLoading && weatherData == null && error == null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.wx_tap_refresh), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
