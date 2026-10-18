package com.cobra.busantravel

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as PlatformColor
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PLAN_FILE = "travel_plan.json"
private const val IMAGE_FOLDER = "images"
private val PHONE_PATTERN = Regex("(?<!\\d)(0\\d{1,3}-\\d{3,5}-\\d{4})(?!\\d)")
private val WEB_PATTERN = Regex("https?://[^\\s]+")

/** A card can query several alternative buses that use the same stops. */
private fun routeNumberKey(value: String): String = value.trim()
    .removeSuffix("번")
    .replace(" ", "")
    // TAGO sometimes calls the same service "급행1003", while people normally
    // enter "1003".  They must not become two arrival rows in one card.
    .removePrefix("급행")
    .uppercase()

private fun splitRouteNumbers(value: String): List<String> = value
    .split(',', '，', '\n')
    .map { it.trim().removeSuffix("번") }
    .filter { it.isNotBlank() }
    .fold(mutableListOf()) { numbers, number ->
        if (numbers.none { routeNumberKey(it) == routeNumberKey(number) }) numbers += number
        numbers
    }

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

data class TravelImage(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val displayName: String,
) {
    fun json() = JSONObject().put("id", id).put("file_name", fileName).put("display_name", displayName)
    companion object {
        fun from(value: JSONObject) = TravelImage(
            id = value.optString("id", UUID.randomUUID().toString()),
            fileName = value.optString("file_name"),
            displayName = value.optString("display_name", "이미지"),
        )
    }
}

data class TravelBusInfo(
    val cityCode: String,
    val cityName: String,
    val routeId: String,
    val routeNumber: String,
    val routeNumbers: List<String> = listOf(routeNumber),
    val boardingStopId: String,
    val boardingStopName: String,
    val boardingOrder: Int,
    val destinationStopId: String,
    val destinationStopName: String,
    val destinationOrder: Int,
    val routeStops: List<TravelBusStop> = emptyList(),
) {
    fun json() = JSONObject()
        .put("city_code", cityCode).put("city_name", cityName)
        .put("route_id", routeId).put("route_number", routeNumber)
        .put("route_numbers", JSONArray().apply { routeNumbers.forEach(::put) })
        .put("boarding_stop_id", boardingStopId).put("boarding_stop_name", boardingStopName).put("boarding_order", boardingOrder)
        .put("destination_stop_id", destinationStopId).put("destination_stop_name", destinationStopName).put("destination_order", destinationOrder)
        .put("route_stops", JSONArray().apply { routeStops.forEach { put(it.json()) } })

    companion object {
        fun from(value: JSONObject?): TravelBusInfo? {
            if (value == null) return null
            val cityCode = value.optString("city_code")
            val routeId = value.optString("route_id")
            val boardingId = value.optString("boarding_stop_id")
            val destinationId = value.optString("destination_stop_id")
            if (cityCode.isBlank() || routeId.isBlank() || boardingId.isBlank() || destinationId.isBlank()) return null
            val stops = value.optJSONArray("route_stops") ?: JSONArray()
            val primaryNumber = value.optString("route_number")
            val routeNumbers = value.optJSONArray("route_numbers")?.let { numbers ->
                List(numbers.length()) { numbers.optString(it) }.filter(String::isNotBlank)
            }.orEmpty().ifEmpty { listOf(primaryNumber).filter(String::isNotBlank) }
            return TravelBusInfo(
                cityCode, value.optString("city_name"), routeId, primaryNumber, routeNumbers,
                boardingId, value.optString("boarding_stop_name"), value.optInt("boarding_order", 0),
                destinationId, value.optString("destination_stop_name"), value.optInt("destination_order", 0),
                List(stops.length()) { TravelBusStop.from(stops.optJSONObject(it)) }.filterNotNull(),
            )
        }
    }
}

data class TravelBusStop(
    val id: String,
    val name: String,
    val order: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("order", order)
        .put("latitude", latitude ?: JSONObject.NULL).put("longitude", longitude ?: JSONObject.NULL)
    companion object {
        fun from(value: JSONObject?): TravelBusStop? {
            val row = value ?: return null
            val id = row.optString("id")
            val name = row.optString("name")
            if (id.isBlank() || name.isBlank()) return null
            return TravelBusStop(
                id, name, row.optInt("order", Int.MAX_VALUE),
                row.optDouble("latitude", Double.NaN).takeUnless(Double::isNaN),
                row.optDouble("longitude", Double.NaN).takeUnless(Double::isNaN),
            )
        }
    }
}

/**
 * A bus route returned by TAGO can contain its whole circuit.  The travel
 * card, however, represents only the journey the user chose.  Keep every
 * consumer (GPS tracking and the route strip) on that exact directed slice.
 */
internal fun selectedBusTripStops(
    allStops: List<TravelBusStop>,
    bus: TravelBusInfo,
): List<TravelBusStop> {
    val boardingIndex = allStops.indexOfFirst { it.id == bus.boardingStopId }
    val destinationIndex = allStops.indexOfFirst { it.id == bus.destinationStopId }
    return if (boardingIndex >= 0 && destinationIndex >= boardingIndex) {
        allStops.subList(boardingIndex, destinationIndex + 1)
    } else {
        // Keep older imported cards usable if TAGO has since changed an ID.
        allStops
    }
}

data class TravelKtxStop(val name: String, val latitude: Double, val longitude: Double) {
    fun json() = JSONObject().put("name", name).put("latitude", latitude).put("longitude", longitude)
    companion object {
        fun from(value: JSONObject?): TravelKtxStop? {
            val row = value ?: return null
            val name = row.optString("name")
            val latitude = row.optDouble("latitude", Double.NaN)
            val longitude = row.optDouble("longitude", Double.NaN)
            return if (name.isBlank() || latitude.isNaN() || longitude.isNaN()) null else TravelKtxStop(name, latitude, longitude)
        }
    }
}

data class TravelKtxInfo(
    val direction: String,
    val stops: List<TravelKtxStop>,
    val departureStation: String = "",
    val arrivalStation: String = "",
    val departureTime: String = "",
    val arrivalTime: String = "",
    val trainNumber: String = "",
    val seatDetails: String = "",
) {
    fun json() = JSONObject()
        .put("direction", direction)
        .put("stops", JSONArray().apply { stops.forEach { put(it.json()) } })
        .put("departure_station", departureStation)
        .put("arrival_station", arrivalStation)
        .put("departure_time", departureTime)
        .put("arrival_time", arrivalTime)
        .put("train_number", trainNumber)
        .put("seat_details", seatDetails)
    companion object {
        fun from(value: JSONObject?): TravelKtxInfo? {
            val row = value ?: return null
            val stops = row.optJSONArray("stops") ?: return null
            val result = List(stops.length()) { TravelKtxStop.from(stops.optJSONObject(it)) }.filterNotNull()
            return result.takeIf { it.size >= 2 }?.let {
                TravelKtxInfo(
                    direction = row.optString("direction"),
                    stops = it,
                    departureStation = row.optString("departure_station", it.first().name),
                    arrivalStation = row.optString("arrival_station", it.last().name),
                    departureTime = row.optString("departure_time"),
                    arrivalTime = row.optString("arrival_time"),
                    trainNumber = row.optString("train_number"),
                    seatDetails = row.optString("seat_details"),
                )
            }
        }
    }
}

private val BUSAN_KTX_STOPS = listOf(
    TravelKtxStop("대전역", 36.3322, 127.4341),
    TravelKtxStop("김천구미역", 36.1215, 128.1190),
    TravelKtxStop("동대구역", 35.8798, 128.6283),
    TravelKtxStop("울산역", 35.5510, 129.1380),
    TravelKtxStop("부산역", 35.1151, 129.0412),
)

private fun busanKtxRoute(departureStation: String, arrivalStation: String): TravelKtxInfo? {
    val departure = BUSAN_KTX_STOPS.indexOfFirst { it.name == departureStation }
    val arrival = BUSAN_KTX_STOPS.indexOfFirst { it.name == arrivalStation }
    if (departure < 0 || arrival < 0 || departure == arrival) return null
    val stops = if (departure < arrival) BUSAN_KTX_STOPS.subList(departure, arrival + 1)
        else BUSAN_KTX_STOPS.subList(arrival, departure + 1).reversed()
    return TravelKtxInfo("$departureStation → $arrivalStation", stops, departureStation, arrivalStation)
}

data class TravelSubwayInfo(
    val direction: String,
    val timetableAsset: String,
    val stops: List<String>,
) {
    fun json() = JSONObject().put("direction", direction).put("timetable_asset", timetableAsset)
        .put("stops", JSONArray().apply { stops.forEach(::put) })
    companion object {
        fun from(value: JSONObject?): TravelSubwayInfo? {
            val row = value ?: return null
            val stops = row.optJSONArray("stops") ?: return null
            val result = List(stops.length()) { stops.optString(it) }.filter(String::isNotBlank)
            return result.takeIf { it.size >= 2 }?.let {
                TravelSubwayInfo(row.optString("direction"), row.optString("timetable_asset"), it)
            }
        }
    }
}

private fun daeguLine1Route(toAnsim: Boolean): TravelSubwayInfo {
    val down = listOf("동대구역", "동구청역", "아양교역", "동촌역", "해안역", "방촌역", "용계역", "율하역", "신기역", "반야월역", "각산역", "안심역")
    return if (toAnsim) {
        TravelSubwayInfo("대구 1호선 · 동대구역 → 안심역", "timetables/line1_down_20241007.csv", down)
    } else {
        TravelSubwayInfo("대구 1호선 · 안심역 → 동대구역", "timetables/line1_up.csv", down.reversed())
    }
}

data class TravelCard(
    val id: String = UUID.randomUUID().toString(),
    val day: String = "",
    val sortDate: String = "",
    val time: String = "",
    val title: String = "",
    val description: String = "",
    val busInfo: TravelBusInfo? = null,
    /** Multiple independent bus paths can belong to one travel card. */
    val busInfos: List<TravelBusInfo> = emptyList(),
    val ktxInfo: TravelKtxInfo? = null,
    val subwayInfo: TravelSubwayInfo? = null,
    val subwayInfos: List<TravelSubwayInfo> = emptyList(),
    val urls: List<String> = emptyList(),
    val mapDestination: String = "",
    val mapAppPackage: String = "",
    val mapAppLabel: String = "",
    val images: List<TravelImage> = emptyList(),
    val state: String = "권장",
) {
    val allBusInfos: List<TravelBusInfo>
        get() = busInfos.ifEmpty { listOfNotNull(busInfo) }
    val allSubwayInfos: List<TravelSubwayInfo>
        get() = subwayInfos.ifEmpty { listOfNotNull(subwayInfo) }

    fun json() = JSONObject()
        .put("id", id).put("day", day).put("sort_date", sortDate).put("time", time).put("title", title)
        .put("description", description).put("bus_info", allBusInfos.firstOrNull()?.json() ?: JSONObject.NULL)
        .put("bus_infos", JSONArray().apply { allBusInfos.forEach { put(it.json()) } })
        .put("ktx_info", ktxInfo?.json() ?: JSONObject.NULL)
        .put("subway_info", allSubwayInfos.firstOrNull()?.json() ?: JSONObject.NULL)
        .put("subway_infos", JSONArray().apply { allSubwayInfos.forEach { put(it.json()) } }).put("state", state)
        .put("urls", JSONArray().apply { urls.forEach(::put) })
        .put("map_destination", mapDestination)
        .put("map_app_package", mapAppPackage).put("map_app_label", mapAppLabel)
        .put("images", JSONArray().apply { images.forEach { put(it.json()) } })

    companion object {
        fun from(value: JSONObject): TravelCard {
            val urls = value.optJSONArray("urls") ?: JSONArray()
            val images = value.optJSONArray("images") ?: JSONArray()
            val busItems = value.optJSONArray("bus_infos")
            val buses = busItems?.let { items ->
                List(items.length()) { TravelBusInfo.from(items.optJSONObject(it)) }.filterNotNull()
            }.orEmpty().ifEmpty { listOfNotNull(TravelBusInfo.from(value.optJSONObject("bus_info"))) }
            val subwayItems = value.optJSONArray("subway_infos")
            val subways = subwayItems?.let { items ->
                List(items.length()) { TravelSubwayInfo.from(items.optJSONObject(it)) }.filterNotNull()
            }.orEmpty().ifEmpty { listOfNotNull(TravelSubwayInfo.from(value.optJSONObject("subway_info"))) }
            return TravelCard(
                id = value.optString("id", UUID.randomUUID().toString()),
                day = value.optString("day"),
                sortDate = value.optString("sort_date"),
                time = value.optString("time"),
                title = value.optString("title"),
                description = value.optString("description"),
                busInfo = buses.firstOrNull(),
                busInfos = buses,
                ktxInfo = TravelKtxInfo.from(value.optJSONObject("ktx_info")),
                subwayInfo = subways.firstOrNull(),
                subwayInfos = subways,
                urls = List(urls.length()) { urls.optString(it) }.filter(String::isNotBlank),
                mapDestination = value.optString("map_destination"),
                mapAppPackage = value.optString("map_app_package"),
                mapAppLabel = value.optString("map_app_label"),
                images = List(images.length()) { TravelImage.from(images.getJSONObject(it)) }
                    .filter { it.fileName.isNotBlank() },
                state = value.optString("state", "권장"),
            )
        }
    }
}

data class TravelPlan(val title: String, val cards: List<TravelCard>) {
    fun json() = JSONObject().put("title", title).put("cards", JSONArray().apply { cards.forEach { put(it.json()) } })
    companion object {
        fun from(value: JSONObject): TravelPlan {
            val cards = value.optJSONArray("cards") ?: JSONArray()
            return TravelPlan(value.optString("title", "여행"), List(cards.length()) { TravelCard.from(cards.getJSONObject(it)) })
        }
    }
}

private fun dateFromCard(card: TravelCard): LocalDate? {
    if (card.sortDate.isNotBlank()) return runCatching { LocalDate.parse(card.sortDate) }.getOrNull()
    val match = Regex("(\\d{1,2})/(\\d{1,2})").find(card.day) ?: return null
    return runCatching { LocalDate.of(2026, match.groupValues[1].toInt(), match.groupValues[2].toInt()) }.getOrNull()
}

private fun travelTimeOrder(time: String): Int {
    // A range such as 10:00-11:10 is sorted by its first (start) time.
    val start = time.substringBefore('-').substringBefore('–')
    val match = Regex("(\\d{1,2}):(\\d{2})").find(start) ?: return Int.MAX_VALUE
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
}

private fun cardTimeOrder(card: TravelCard): Int {
    // KTX cards use the dedicated KTX departure time as their source of truth.
    val ktxDeparture = card.ktxInfo?.departureTime?.takeIf { it.isNotBlank() }
    return travelTimeOrder(ktxDeparture ?: card.time)
}

private fun cardsInTravelOrder(cards: List<TravelCard>): List<TravelCard> {
    // A missing time is deliberately Int.MAX_VALUE, so the card remains at the
    // end of its own date instead of being left at its previous list position.
    // A missing/invalid date is placed after all dated travel cards.
    return cards.sortedWith(
        compareBy<TravelCard>(
            { dateFromCard(it) ?: LocalDate.MAX },
            ::cardTimeOrder,
            { it.title },
        )
    )
}

private fun saveSummary(card: TravelCard): String = when {
    card.allBusInfos.isNotEmpty() -> "저장했습니다 · 버스 경로 ${card.allBusInfos.size}개"
    card.allSubwayInfos.isNotEmpty() -> "저장했습니다 · 지하철 경로 ${card.allSubwayInfos.size}개"
    else -> "카드를 저장했습니다."
}

private fun travelDayLabel(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN))

private fun displayedDayKey(card: TravelCard): String =
    Regex("^\\s*(\\d{1,2})/(\\d{1,2})").find(card.day)?.let { match ->
        "%02d/%02d".format(Locale.US, match.groupValues[1].toInt(), match.groupValues[2].toInt())
    } ?: card.day.trim()

private fun parseTravelDate(value: String): LocalDate? {
    val digits = value.filter(Char::isDigit)
    val standard = when (digits.length) {
        6 -> "20${digits.take(2)}-${digits.substring(2, 4)}-${digits.drop(4)}"
        8 -> "${digits.take(4)}-${digits.substring(4, 6)}-${digits.drop(6)}"
        else -> value
    }
    return try { LocalDate.parse(standard) } catch (_: DateTimeParseException) { null }
}

private fun formatTravelTimeInput(value: String): String {
    fun oneTime(raw: String): String {
        val digits = raw.filter(Char::isDigit).take(4)
        return if (digits.length == 4) "${digits.take(2)}:${digits.drop(2)}" else digits
    }
    // Accept a normal hyphen, an en dash, or a tilde as a time-range divider.
    // Keep a trailing divider while the user is entering the end time.
    val normalized = value.replace('–', '-').replace('~', '-')
    val divider = normalized.indexOf('-')
    if (divider < 0) {
        // A range may be entered as eight uninterrupted digits. After 0940,
        // the fifth digit starts the end time: 09401100 -> 09:40–11:00.
        val digits = normalized.filter(Char::isDigit).take(8)
        if (digits.length <= 4) return oneTime(digits)
        return "${oneTime(digits.take(4))}–${oneTime(digits.drop(4))}"
    }
    return "${oneTime(normalized.substring(0, divider))}–${oneTime(normalized.substring(divider + 1))}"
}

private data class PhoneContact(val name: String, val number: String)
private enum class ContactTarget { TITLE, DESCRIPTION }
private enum class BusStopPicker { BOARDING, DESTINATION }
private enum class KtxStationPicker { DEPARTURE, ARRIVAL }
private data class BusPathCandidate(val boarding: TagoStop, val destination: TagoStop)

data class TravelTrackingStatus(
    val cardId: String,
    val date: String,
    val phase: String,
    val currentStopIndex: Int,
    val message: String,
    // KTX uses its fixed list index. Bus tracking additionally stores the
    // stable TAGO stop ID, so an index from a GPS-only list can never point to
    // a different row in the full display list.
    val currentStopId: String = "",
) {
    fun json() = JSONObject().put("card_id", cardId).put("date", date).put("phase", phase)
        .put("current_stop", currentStopIndex).put("message", message)
        .put("current_stop_id", currentStopId)
    companion object {
        fun from(value: String?): TravelTrackingStatus? = runCatching {
            val row = JSONObject(value.orEmpty())
            TravelTrackingStatus(
                row.getString("card_id"), row.getString("date"), row.getString("phase"),
                row.optInt("current_stop", 0), row.optString("message"),
                row.optString("current_stop_id"),
            )
        }.getOrNull()
    }
}

data class SubwayBoardingStatus(
    val cardId: String,
    val routeIndex: Int,
    val date: String,
    val departureEpochMillis: Long,
    val arrivalsEpochMillis: List<Long>,
    val twoStopsAlerted: Boolean = false,
    val oneStopAlerted: Boolean = false,
    val alighted: Boolean = false,
) {
    fun json() = JSONObject().put("card_id", cardId).put("route_index", routeIndex).put("date", date)
        .put("departure", departureEpochMillis)
        .put("arrivals", JSONArray().apply { arrivalsEpochMillis.forEach(::put) })
        .put("two_stops_alerted", twoStopsAlerted).put("one_stop_alerted", oneStopAlerted).put("alighted", alighted)
    companion object {
        fun from(value: String?): SubwayBoardingStatus? = runCatching {
            val row = JSONObject(value.orEmpty())
            val arrivals = row.optJSONArray("arrivals") ?: return@runCatching null
            SubwayBoardingStatus(
                row.getString("card_id"), row.optInt("route_index", 0), row.getString("date"), row.getLong("departure"),
                List(arrivals.length()) { arrivals.optLong(it) },
                row.optBoolean("two_stops_alerted"), row.optBoolean("one_stop_alerted"), row.optBoolean("alighted"),
            )
        }.getOrNull()
    }
}

internal class TravelStore(private val context: Context) {
    private val planFile = File(context.filesDir, PLAN_FILE)
    private val imageDir = File(context.filesDir, IMAGE_FOLDER).apply { mkdirs() }
    private val automaticBackupDir = context.getDir("travel_automatic_backups", Context.MODE_PRIVATE).apply { mkdirs() }
    private val tagoKeyVault = TagoApiKeyVault(context)
    private val trackingPreferences = context.applicationContext.getSharedPreferences("travel_tracking", Context.MODE_PRIVATE)

    fun load(): TravelPlan {
        if (planFile.isFile) return TravelPlan.from(JSONObject(planFile.readText()))
        val seeded = runCatching {
            context.assets.open("travel_plan.json").bufferedReader().use { TravelPlan.from(JSONObject(it.readText())) }
        }.getOrElse { TravelPlan("여행", emptyList()) }
        save(seeded)
        return seeded
    }

    fun save(plan: TravelPlan) {
        planFile.writeText(plan.json().toString(2))
        // A deleted card can be restored through the 10-step undo history.
        // Keep unused image files until a future explicit cleanup action.
    }

    fun imageFile(image: TravelImage): File = File(imageDir, image.fileName)

    fun tagoApiKey(): String? = tagoKeyVault.get()
    fun hasTagoApiKey(): Boolean = tagoKeyVault.isConfigured()
    fun saveTagoApiKey(value: String) = tagoKeyVault.save(value)
    fun appContext(): Context = context.applicationContext

    fun trackingStatus(cardId: String): TravelTrackingStatus? = TravelTrackingStatus.from(trackingPreferences.getString("status_$cardId", null))
        ?.takeIf { it.date == LocalDate.now().toString() }

    fun saveTrackingStatus(status: TravelTrackingStatus) {
        trackingPreferences.edit().putString("status_${status.cardId}", status.json().toString()).apply()
    }

    fun subwayBoarding(cardId: String, routeIndex: Int): SubwayBoardingStatus? =
        SubwayBoardingStatus.from(trackingPreferences.getString("subway_${cardId}_$routeIndex", null))
            ?.takeIf { it.date == LocalDate.now().toString() }

    fun saveSubwayBoarding(status: SubwayBoardingStatus) {
        trackingPreferences.edit().putString("subway_${status.cardId}_${status.routeIndex}", status.json().toString()).apply()
    }

    fun openExternal(raw: String, preferredPackage: String = "") {
        val isPhone = raw.startsWith("tel:")
        val normalized = when {
            isPhone -> raw
            // Browser URLs and installed-app deep links (for example nmap://)
            // must keep their original scheme.  Prefix only a plain domain name.
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw) -> raw
            else -> "https://$raw"
        }
        val intent = when {
            isPhone -> Intent(Intent.ACTION_DIAL, Uri.parse(normalized))
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
        }
        if (preferredPackage.isNotBlank()) intent.setPackage(preferredPackage)
        // TravelStore has the application context, not an Activity context.
        // External browser/dialer launches therefore need a new task.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                val message = if (preferredPackage.isNotBlank()) "선택한 지도 앱을 열지 못했습니다. 앱이 설치되어 있는지 확인하세요." else "열 수 있는 앱을 찾지 못했습니다."
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
    }

    fun copyImages(uris: List<Uri>): List<TravelImage> = uris.mapNotNull { uri ->
        runCatching {
            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.take(80)?.ifBlank { "이미지" } ?: "이미지"
            val extension = when (context.contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val image = TravelImage(fileName = "${UUID.randomUUID()}.$extension", displayName = displayName)
            context.contentResolver.openInputStream(uri)?.use { input -> imageFile(image).outputStream().use(input::copyTo) }
                ?: error("이미지를 읽지 못했습니다.")
            image
        }.getOrNull()
    }

    fun deleteImages(images: List<TravelImage>) = images.forEach { imageFile(it).delete() }

    data class AutomaticBackup(val file: File, val createdAt: Long)

    fun createAutomaticBackup(plan: TravelPlan) {
        val stamp = System.currentTimeMillis()
        val file = File(automaticBackupDir, "travel_$stamp.zip")
        FileOutputStream(file).use { writeZip(it, plan) }
        automaticBackupDir.listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }
            ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach(File::delete)
    }

    fun automaticBackups(): List<AutomaticBackup> = automaticBackupDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith("travel_") && it.extension.equals("zip", true) }
        ?.sortedByDescending { it.lastModified() }
        ?.map { AutomaticBackup(it, it.lastModified()) }
        ?: emptyList()

    fun restoreAutomaticBackup(backup: AutomaticBackup): TravelPlan =
        importPlan(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", backup.file))

    fun sharePlan(plan: TravelPlan) {
        val shareFile = File(context.cacheDir, "여행-${System.currentTimeMillis()}.zip")
        FileOutputStream(shareFile).use { output -> writeZip(output, plan) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("여행.zip", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "여행 파일 공유")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    /**
     * Render every card in an attached, off-screen ComposeView.  A ComposeView cannot be
     * measured before it joins a window: doing so causes "cannot locate windowRecomposer".
     */
    fun shareFullPlanImage(
        plan: TravelPlan,
        hostContext: Context,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val activity = hostContext.findActivity()
        if (activity == null) {
            onFailure(IllegalStateException("캡처 화면을 준비할 수 없습니다."))
            return
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val width = hostContext.resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val captureView = ComposeView(activity)
        // Keep the temporary view outside the visible content. It is nevertheless attached,
        // so Compose can create its window recomposer normally.
        val layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = width + 8
        }
        content.addView(captureView, layoutParams)
        captureView.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                TravelCaptureSheet(plan, this@TravelStore)
            }
        }
        captureView.post {
            // Wait for one attached UI pass so the composition and natural card height exist.
            captureView.post {
                var bitmap: Bitmap? = null
                try {
                    captureView.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                    )
                    val height = captureView.measuredHeight.coerceAtLeast(1)
                    // Keep it beyond the right edge while it is drawn into our bitmap.
                    captureView.layout(width + 8, 0, (width * 2) + 8, height)
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    captureView.draw(Canvas(bitmap))
                    val shareFile = File(context.cacheDir, "여행_전체일정_${System.currentTimeMillis()}.png")
                    FileOutputStream(shareFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(shareFile.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "여행 전체 캡처 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    onSuccess()
                } catch (error: Throwable) {
                    onFailure(error)
                } finally {
                    bitmap?.recycle()
                    captureView.disposeComposition()
                    content.removeView(captureView)
                }
            }
        }
    }

    private fun writeZip(output: FileOutputStream, plan: TravelPlan) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(PLAN_FILE))
            zip.write(plan.json().toString(2).toByteArray())
            zip.closeEntry()
            plan.cards.flatMap { it.images }.distinctBy { it.fileName }.forEach { image ->
                val source = imageFile(image)
                if (!source.isFile) return@forEach
                zip.putNextEntry(ZipEntry("$IMAGE_FOLDER/${image.fileName}"))
                FileInputStream(source).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun importPlan(uri: Uri): TravelPlan {
        val stage = File(context.cacheDir, "travel-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val allowed = name == PLAN_FILE || Regex("$IMAGE_FOLDER/[A-Za-z0-9._-]+").matches(name)
                        require(allowed && !entry.isDirectory) { "여행 꾸러미 형식이 아닙니다." }
                        val target = File(stage, name)
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zip.copyTo(it) }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("파일을 읽지 못했습니다.")
            val imported = TravelPlan.from(JSONObject(File(stage, PLAN_FILE).readText()))
            val needed = imported.cards.flatMap { it.images }.map { it.fileName }.toSet()
            require(needed.all { File(stage, "$IMAGE_FOLDER/$it").isFile }) { "이미지 일부가 누락된 여행 꾸러미입니다." }
            // Keep the current image set while undo is available. Image file
            // names are UUIDs, so imported images can safely be copied beside it.
            File(stage, IMAGE_FOLDER).listFiles()?.forEach { source -> source.copyTo(File(imageDir, source.name), overwrite = true) }
            save(imported)
            return imported
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun removeUnreferencedImages(plan: TravelPlan) {
        val used = plan.cards.flatMap { it.images }.map { it.fileName }.toSet()
        imageDir.listFiles()?.filter { it.name !in used }?.forEach(File::delete)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 밝은 배경에서 상태표시줄·하단바의 시간, 통신, 배터리 아이콘이 검정색으로 보이게 명시한다.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = PlatformColor.rgb(255, 249, 245)
        window.navigationBarColor = PlatformColor.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent { MaterialTheme(colorScheme = lightColorScheme()) { BusanTravelApp(TravelStore(applicationContext)) } }
    }
}

private fun startTravelTracking(context: Context) {
    val intent = Intent(context, TravelTrackingService::class.java)
    ContextCompat.startForegroundService(context, intent)
}

private fun requestKtxLocationRefresh(context: Context, cardId: String) {
    val intent = Intent(context, TravelTrackingService::class.java)
        .setAction(TravelTrackingService.ACTION_REFRESH_KTX_LOCATION)
        .putExtra(TravelTrackingService.EXTRA_CARD_ID, cardId)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopTravelTracking(context: Context) {
    context.stopService(Intent(context, TravelTrackingService::class.java))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BusanTravelApp(store: TravelStore) {
    var plan by remember { mutableStateOf(store.load()) }
    var editingCard by remember { mutableStateOf<TravelCard?>(null) }
    var creatingCard by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TravelCard?>(null) }
    var deleteAllRequested by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var pendingBackupRestore by remember { mutableStateOf<TravelStore.AutomaticBackup?>(null) }
    var automaticBackupSaving by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<TravelImage?>(null) }
    var notice by remember { mutableStateOf("") }
    var trackingPermissionWarning by remember { mutableStateOf("") }
    var trackingTick by remember { mutableIntStateOf(0) }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val appScope = rememberCoroutineScope()
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val locationGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val notificationGranted = Build.VERSION.SDK_INT < 33 || granted[Manifest.permission.POST_NOTIFICATIONS] == true
        if (locationGranted && notificationGranted) {
            startTravelTracking(appContext)
        } else {
            val missing = buildList {
                if (!locationGranted) add("위치")
                if (!notificationGranted) add("알림")
            }.joinToString("·")
            trackingPermissionWarning = "$missing 권한을 허용하지 않아 자동 탑승·현재 위치 확인을 시작하지 않았습니다. 설정에서 허용하면 바로 사용할 수 있습니다."
            notice = trackingPermissionWarning
            stopTravelTracking(appContext)
        }
    }
    val undoHistory = remember { mutableStateListOf<TravelPlan>() }
    val redoHistory = remember { mutableStateListOf<TravelPlan>() }
    val listState = rememberLazyListState()
    var draggingCardId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var expandedDayKey by remember { mutableStateOf(plan.cards.firstOrNull()?.let(::displayedDayKey).orEmpty()) }
    var showAllDays by remember { mutableStateOf(false) }
    fun saveAutomaticBackupInBackground(snapshot: TravelPlan) {
        appScope.launch {
            automaticBackupSaving = true
            val result = withContext(Dispatchers.IO) { runCatching { store.createAutomaticBackup(snapshot) } }
            automaticBackupSaving = false
            result.exceptionOrNull()?.let { notice = "자동 백업을 저장하지 못했습니다." }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val beforeImport = plan
            appScope.launch {
                automaticBackupSaving = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        store.createAutomaticBackup(beforeImport)
                        store.importPlan(uri)
                    }
                }
                automaticBackupSaving = false
                result.onSuccess { imported ->
                    if (undoHistory.size == 10) undoHistory.removeAt(0)
                    undoHistory += beforeImport
                    redoHistory.clear()
                    plan = imported
                    store.save(imported)
                    notice = "여행 전체를 최신 ZIP으로 교체했습니다."
                }.onFailure { notice = "불러오지 못했습니다: ${it.message}" }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            trackingTick += 1
        }
    }
    LaunchedEffect(plan.cards.map { it.id to (it.allBusInfos.isNotEmpty() || it.ktxInfo != null || it.allSubwayInfos.isNotEmpty()) }) {
        if (plan.cards.none { it.allBusInfos.isNotEmpty() || it.ktxInfo != null || it.allSubwayInfos.isNotEmpty() }) return@LaunchedEffect
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if ((fine || coarse) && notifications) {
            startTravelTracking(appContext)
        } else {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            locationPermission.launch(permissions)
        }
    }
    fun update(next: TravelPlan) {
        if (next == plan) return
        saveAutomaticBackupInBackground(plan)
        if (undoHistory.size == 10) undoHistory.removeAt(0)
        undoHistory += plan
        redoHistory.clear()
        plan = next
        store.save(next)
    }
    fun undo() {
        if (undoHistory.isEmpty()) return
        if (redoHistory.size == 10) redoHistory.removeAt(0)
        redoHistory += plan
        plan = undoHistory.removeAt(undoHistory.lastIndex)
        store.save(plan)
        notice = "마지막 변경을 되돌렸습니다."
    }
    fun redo() {
        if (redoHistory.isEmpty()) return
        if (undoHistory.size == 10) undoHistory.removeAt(0)
        undoHistory += plan
        plan = redoHistory.removeAt(redoHistory.lastIndex)
        store.save(plan)
        notice = "되돌린 변경을 다시 적용했습니다."
    }
    fun move(card: TravelCard, distance: Int) {
        val at = plan.cards.indexOfFirst { it.id == card.id }
        val target = (at + distance).coerceIn(0, plan.cards.lastIndex)
        if (at == target) return
        val reordered = plan.cards.toMutableList().also { item -> val moving = item.removeAt(at); item.add(target, moving) }
        update(plan.copy(cards = reordered))
    }
    fun beginCardMove(card: TravelCard) {
        draggingCardId = card.id
        dragOffsetY = 0f
        notice = "이동 버튼을 누른 채 인접 카드의 가운데를 넘기면 순서가 바뀝니다."
    }
    fun dragCard(card: TravelCard, delta: Float) {
        if (draggingCardId != card.id) return
        dragOffsetY += delta
        val at = plan.cards.indexOfFirst { it.id == card.id }
        if (at < 0) return
        val target = when {
            dragOffsetY < 0f && at > 0 -> at - 1
            dragOffsetY > 0f && at < plan.cards.lastIndex -> at + 1
            else -> return
        }
        val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == card.id } ?: return
        val neighbour = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == plan.cards[target].id } ?: return
        val draggedCenter = itemInfo.offset + itemInfo.size / 2f + dragOffsetY
        val neighbourCenter = neighbour.offset + neighbour.size / 2f
        if ((dragOffsetY < 0f && draggedCenter < neighbourCenter) || (dragOffsetY > 0f && draggedCenter > neighbourCenter)) {
            move(card, target - at)
            dragOffsetY = 0f
        }
    }
    fun endCardMove() {
        draggingCardId = null
        dragOffsetY = 0f
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFFF9F5)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Status-bar padding remains; reduce the formerly oversized blank band.
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("여행", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showAllDays = !showAllDays }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text(if (showAllDays) "날짜별 보기" else "전체 펼침")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        Button(onClick = { creatingCard = true }, modifier = Modifier.height(38.dp)) { Text("+ 카드") }
                        TextButton(onClick = ::undo, enabled = undoHistory.isNotEmpty(), modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Undo, contentDescription = "되돌리기", modifier = Modifier.size(42.dp)) }
                        TextButton(onClick = ::redo, enabled = redoHistory.isNotEmpty(), modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Redo, contentDescription = "원래대로", modifier = Modifier.size(42.dp)) }
                        OutlinedButton(onClick = { update(plan.copy(cards = cardsInTravelOrder(plan.cards))); notice = "날짜·시간 순으로 정렬했습니다. 시간 없는 카드는 해당 날짜의 마지막에 둡니다." }, modifier = Modifier.height(38.dp)) { Text("정렬") }
                        TextButton(onClick = {
                            notice = "여행 전체 캡처를 만드는 중…"
                            store.shareFullPlanImage(
                                plan = plan,
                                hostContext = appContext,
                                onSuccess = { notice = "여행 전체 캡처를 만들고 공유 화면을 열었습니다." },
                                onFailure = { notice = "전체 캡처를 만들지 못했습니다: ${it.message}" },
                            )
                        }, modifier = Modifier.size(46.dp)) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = "전체 캡처 및 공유", modifier = Modifier.size(28.dp))
                        }
                }
                if (notice.isNotBlank()) Text(notice, modifier = Modifier.padding(top = 8.dp), color = Color(0xFF00695C), style = MaterialTheme.typography.bodySmall)
                if (automaticBackupSaving) Text("자동 백업 저장 중…", modifier = Modifier.padding(top = 4.dp), color = Color(0xFF4E5C64), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f).imePadding(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(plan.cards, key = { _, card -> card.id }) { index, card ->
                    val dayKey = displayedDayKey(card)
                    val startsDay = index == 0 || dayKey != displayedDayKey(plan.cards[index - 1])
                    Column {
                        if (startsDay) {
                            TravelDateHeader(card.day, expanded = showAllDays || expandedDayKey == dayKey) {
                                if (showAllDays) {
                                    showAllDays = false
                                    expandedDayKey = dayKey
                                } else {
                                    expandedDayKey = if (expandedDayKey == dayKey) {
                                        plan.cards.drop(index + 1).firstOrNull { displayedDayKey(it) != dayKey }?.let(::displayedDayKey)
                                            ?: plan.cards.take(index).lastOrNull()?.let(::displayedDayKey).orEmpty()
                                    } else dayKey
                                }
                            }
                        }
                        if (showAllDays || expandedDayKey == dayKey) Box(
                            Modifier
                                // Keep the dragged card and its neighbours visibly
                                // sliding into place rather than swapping abruptly.
                                // A longer, eased placement makes an order change
                                // visible instead of looking like an instant swap.
                                .animateItem(placementSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing))
                                .graphicsLayer { translationY = if (draggingCardId == card.id) dragOffsetY else 0f },
                        ) {
                            TravelCardView(
                                card = card,
                                store = store,
                                trackingStatus = store.trackingStatus(card.id).also { trackingTick },
                                onEdit = { editingCard = card },
                                onDelete = { deleteTarget = card },
                                onImage = { viewingImage = it },
                                onMoveStart = { beginCardMove(card) },
                                onMove = { dragCard(card, it) },
                                onMoveEnd = ::endCardMove,
                            )
                        }
                    }
                }
                if (plan.cards.isEmpty()) item { Text("아직 카드가 없습니다. ‘카드 추가’를 눌러 시작하세요.") }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFF3F0EC),
                        border = BorderStroke(1.dp, Color(0xFFD7D0C8)),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = {
                                runCatching { store.sharePlan(plan) }
                                    .onSuccess { notice = "여행 ZIP을 만들고 공유 화면을 열었습니다." }
                                    .onFailure { notice = "내보내지 못했습니다: ${it.message}" }
                            }, modifier = Modifier.height(36.dp)) { Text("내보내기") }
                            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, modifier = Modifier.height(36.dp)) { Text("불러오기") }
                            OutlinedButton(onClick = { deleteAllRequested = true }, enabled = plan.cards.isNotEmpty(), modifier = Modifier.height(36.dp)) { Text("모두 삭제") }
                            TextButton(onClick = { settingsOpen = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.Settings, contentDescription = "설정·백업·복원", modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
            // This is a fixed visual safe area, not list padding. It stays
            // plain white and never contains a card or a button.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(Color.White),
            )
        }
    }
    if (creatingCard) CardEditorDialog(
        source = null,
        store = store,
        onDismiss = { creatingCard = false },
        onSave = { saved ->
            // New cards stay at the bottom in the order they were entered.
            // Date/time sorting is an explicit action through the Sort button.
            update(plan.copy(cards = plan.cards + saved))
            notice = "마지막에 카드를 추가했습니다. ${saveSummary(saved)}"
            creatingCard = false
        },
    )
    editingCard?.let { source -> CardEditorDialog(
        source = source,
        store = store,
        onDismiss = { editingCard = null },
        onSave = { saved ->
            update(plan.copy(cards = plan.cards.map { if (it.id == saved.id) saved else it }))
            notice = saveSummary(saved)
            editingCard = null
        },
    ) }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("카드 삭제") },
            text = { Text("‘${target.title}’ 카드를 삭제할까요?") },
            confirmButton = { Button(onClick = { update(plan.copy(cards = plan.cards.filterNot { it.id == target.id })); deleteTarget = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
    if (deleteAllRequested) AlertDialog(
        onDismissRequest = { deleteAllRequested = false },
        title = { Text("모든 카드 삭제") },
        text = { Text("현재 여행 카드 ${plan.cards.size}개를 모두 삭제할까요? 바로 뒤의 ‘되돌리기’로 복구할 수 있습니다.") },
        confirmButton = { Button(onClick = { update(plan.copy(cards = emptyList())); deleteAllRequested = false }) { Text("모두 삭제") } },
        dismissButton = { TextButton(onClick = { deleteAllRequested = false }) { Text("취소") } },
    )
    if (settingsOpen) AlertDialog(
        onDismissRequest = { settingsOpen = false },
        title = { Text("설정 · 백업 · 복원") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("카드와 첨부 이미지를 포함해 수정·삭제 전 자동 백업을 최근 7개까지 보관합니다.", style = MaterialTheme.typography.bodySmall)
                Text("외부 보관이나 가족 공유는 화면 하단의 ‘내보내기’를 사용하세요. 내보낸 ZIP은 여기의 ‘백업 파일 복원’으로 다시 불러올 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { settingsOpen = false; importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, modifier = Modifier.fillMaxWidth()) { Text("백업 파일 복원") }
                Text("자동 백업", fontWeight = FontWeight.Bold)
                val backups = store.automaticBackups()
                if (backups.isEmpty()) Text("아직 자동 백업이 없습니다.", style = MaterialTheme.typography.bodySmall)
                backups.forEach { backup ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(java.text.SimpleDateFormat("M/d HH:mm", Locale.KOREAN).format(java.util.Date(backup.createdAt)), modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { pendingBackupRestore = backup }) { Text("복원") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("닫기") } },
    )
    pendingBackupRestore?.let { backup -> AlertDialog(
        onDismissRequest = { pendingBackupRestore = null },
        title = { Text("자동 백업 복원") },
        text = { Text("선택한 자동 백업으로 카드와 첨부 이미지를 바꿉니다. 현재 상태는 먼저 자동 백업으로 보관합니다.") },
        confirmButton = { Button(onClick = {
            val beforeRestore = plan
            pendingBackupRestore = null
            settingsOpen = false
            appScope.launch {
                automaticBackupSaving = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        store.createAutomaticBackup(beforeRestore)
                        store.restoreAutomaticBackup(backup)
                    }
                }
                automaticBackupSaving = false
                result.onSuccess { restored ->
                    if (undoHistory.size == 10) undoHistory.removeAt(0)
                    undoHistory += beforeRestore
                    redoHistory.clear()
                    plan = restored
                    store.save(restored)
                    notice = "자동 백업을 복원했습니다."
                }.onFailure { notice = "자동 백업을 복원하지 못했습니다." }
            }
        }) { Text("복원") } },
        dismissButton = { TextButton(onClick = { pendingBackupRestore = null }) { Text("취소") } },
    ) }
    if (trackingPermissionWarning.isNotBlank()) AlertDialog(
        onDismissRequest = { trackingPermissionWarning = "" },
        title = { Text("자동 이동 확인을 시작하지 않았습니다") },
        text = { Text(trackingPermissionWarning) },
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appContext.packageName, null))
                appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                trackingPermissionWarning = ""
            }) { Text("앱 설정 열기") }
        },
        dismissButton = { TextButton(onClick = { trackingPermissionWarning = "" }) { Text("나중에") } },
    )
    viewingImage?.let { image ->
        val cardImages = plan.cards.firstOrNull { card -> card.images.any { it.id == image.id } }?.images ?: listOf(image)
        ImageViewerDialog(cardImages, image.id, store, onDismiss = { viewingImage = null })
    }
}

@Composable
private fun TravelDateHeader(day: String, expanded: Boolean = true, onClick: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF0F1F3),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📅  $day", fontWeight = FontWeight.Bold, color = Color(0xFF37474F))
            Spacer(Modifier.weight(1f))
            if (onClick != null) Text(if (expanded) "접기 ▲" else "펼치기 ▼", color = Color(0xFF4E5C64), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TravelCaptureSheet(plan: TravelPlan, store: TravelStore) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFFFFF9F5)) {
        Column(Modifier.padding(start = 16.dp, top = 22.dp, end = 16.dp, bottom = 22.dp)) {
            Text("여행", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("카드 전체", color = Color(0xFF4E5C64), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            plan.cards.forEachIndexed { index, card ->
                if (index == 0 || displayedDayKey(card) != displayedDayKey(plan.cards[index - 1])) TravelDateHeader(card.day)
                TravelCardView(
                    card = card,
                    store = store,
                    trackingStatus = store.trackingStatus(card.id),
                    onEdit = {},
                    onDelete = {},
                    onImage = {},
                    onMoveStart = {},
                    onMove = {},
                    onMoveEnd = {},
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun TravelCardView(
    card: TravelCard,
    store: TravelStore,
    trackingStatus: TravelTrackingStatus?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onImage: (TravelImage) -> Unit,
    onMoveStart: () -> Unit,
    onMove: (Float) -> Unit,
    onMoveEnd: () -> Unit,
) {
    val tint = when (card.state) {
        "확정" -> Color(0xFF0B6E69)
        "확인 필요" -> Color(0xFFB45309)
        "후보" -> Color(0xFF6B5B95)
        else -> Color(0xFF2D5D86)
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFC))) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {},
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                    modifier = Modifier.pointerInput(card.id) {
                        detectDragGestures(
                            onDragStart = { onMoveStart() },
                            onDragCancel = onMoveEnd,
                            onDragEnd = onMoveEnd,
                            onDrag = { _, amount -> onMove(amount.y) },
                        )
                    },
                ) { Text("↕ 위치변경") }
                Spacer(Modifier.width(8.dp))
                Text(card.day, color = tint, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(8.dp))
                Text(card.time, color = Color(0xFF38474F), style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
                Spacer(Modifier.weight(1f))
                Text(card.state, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
            Text(card.title, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            trackingStatus?.let { status ->
                val statusColor = when (status.phase) { "탑승 중" -> Color(0xFF00796B); "도착" -> Color(0xFF4B6584); else -> Color(0xFF8A5A00) }
                Text(status.message, modifier = Modifier.padding(top = 3.dp), color = statusColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            if (card.description.isNotBlank()) PhoneAndUrlText(card.description, Modifier.padding(top = 4.dp))
            card.allBusInfos.forEachIndexed { index, bus ->
                BusArrivalPanel(
                    bus = bus,
                    store = store,
                    trackingStatus = trackingStatus,
                    pathIndex = index,
                    pathCount = card.allBusInfos.size,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            card.ktxInfo?.let {
                KtxRoutePanel(
                    it,
                    trackingStatus,
                    onRefreshLocation = { requestKtxLocationRefresh(store.appContext(), card.id) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            card.allSubwayInfos.forEachIndexed { index, subway ->
                SubwayRoutePanel(card.id, index, subway, store, Modifier.padding(top = 8.dp))
            }
            val mapLinks = card.urls.filter { it.startsWith("nmap:", ignoreCase = true) }
            val ordinaryLinks = card.urls.filterNot { it.startsWith("nmap:", ignoreCase = true) }
            if (ordinaryLinks.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ordinaryLinks.forEach { url -> OutlinedButton(onClick = { store.openExternal(url) }) { Text(linkLabel(url), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            }
            if (mapLinks.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mapLinks.forEach { uri -> OutlinedButton(onClick = { store.openExternal(uri) }) { Text("네이버 지도 열기") } }
            }
            if (card.images.isNotEmpty()) {
                LazyRow(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(card.images, key = { it.id }) { image -> ImageThumbnail(store.imageFile(image), image.displayName) { onImage(image) } }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp), color = Color(0xFFE9E4DF))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("중앙 이동 버튼을 끌어 순서 변경", color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("수정") }
                TextButton(onClick = onDelete) { Text("삭제") }
            }
        }
    }
}

@Composable
private fun BusArrivalPanel(
    bus: TravelBusInfo,
    store: TravelStore,
    trackingStatus: TravelTrackingStatus?,
    pathIndex: Int,
    pathCount: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var loading by remember(bus) { mutableStateOf(false) }
    val panelKey = "${bus.cityCode}:${bus.routeId}:${bus.boardingStopId}:${bus.destinationStopId}"
    // LazyColumn disposes a card when it leaves the screen.  Keep the last
    // user-requested arrival result until the user explicitly refreshes it.
    var result by rememberSaveable(panelKey) { mutableStateOf("") }
    var routeStops by remember(bus) { mutableStateOf(bus.routeStops) }
    var currentStopOrder by rememberSaveable(panelKey) { mutableStateOf<Int?>(null) }
    var keyDialogOpen by remember { mutableStateOf(false) }
    fun refresh() {
        val key = store.tagoApiKey()
        if (key.isNullOrBlank()) { keyDialogOpen = true; return }
        loading = true
        result = ""
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = TagoBusClient(key)
                    val arrivalsByRoute = bus.routeNumbers
                        .ifEmpty { listOf(bus.routeNumber) }
                        .associateWith { routeNumber -> client.arrivals(bus.cityCode, bus.boardingStopId, routeNumber) }
                    arrivalsByRoute to client.routeStops(bus.cityCode, bus.routeId)
                }
            }.onSuccess { (arrivalsByRoute, refreshedStops) ->
                if (refreshedStops.isNotEmpty()) routeStops = refreshedStops.map { TravelBusStop(it.id, it.name, it.order, it.latitude, it.longitude) }
                // ARRPREVSTATIONCNT is the count before the boarding stop.
                // A negative/absent value cannot prove that the vehicle has
                // not already passed the stop, so never present it as boardable.
                val boardable = arrivalsByRoute.flatMap { (routeNumber, arrivals) ->
                    arrivals.filter { (it.stopsToBoarding ?: -1) >= 0 }.map { routeNumber to it }
                }.sortedBy { (_, arrival) -> arrival.seconds }
                result = boardable.firstOrNull()?.let { (routeNumber, arrival) ->
                    currentStopOrder = (bus.boardingOrder - (arrival.stopsToBoarding ?: 0)).coerceAtLeast(1)
                    val minutes = ((arrival.seconds + 59) / 60).coerceAtLeast(1)
                    val rideStops = (bus.destinationOrder - bus.boardingOrder).takeIf { it > 0 }
                    buildString {
                        append("${routeNumber}번 · 약 ${minutes}분 후 도착")
                        arrival.stopsToBoarding?.let { append(" · 승차까지 ${it}정거장") }
                        // Before boarding this is a route-plan distance, not the
                        // distance from the currently approaching vehicle.
                        rideStops?.let { append(" · 탑승 후 하차까지 ${it}정거장") }
                        val earliestEach = boardable.groupBy { it.first }.values
                            .mapNotNull { it.minByOrNull { (_, item) -> item.seconds } }
                            .filter { it.first != routeNumber }
                        earliestEach.forEach { (otherRoute, otherArrival) ->
                            val otherMinutes = ((otherArrival.seconds + 59) / 60).coerceAtLeast(1)
                            append("\n${otherRoute}번 · 약 ${otherMinutes}분 후")
                            otherArrival.stopsToBoarding?.let { append(" · 승차까지 ${it}정거장") }
                        }
                    }
                } ?: run { currentStopOrder = null; "승차 위치 이전 차량 정보 없음" }
            }.onFailure { result = "도착정보 없음" }
            loading = false
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF0F7F6),
        border = BorderStroke(1.dp, Color(0xFFB9D8D4)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "버스 경로 ${pathIndex + 1}/$pathCount · 도착정보 · ${bus.cityName}",
                fontWeight = FontWeight.Bold,
                color = Color(0xFF08675E),
            )
            Text("${bus.routeNumbers.ifEmpty { listOf(bus.routeNumber) }.joinToString(", ")}번  ${bus.boardingStopName} → ${bus.destinationStopName}", style = MaterialTheme.typography.bodyMedium)
            val tripStops = selectedBusTripStops(routeStops, bus)
            if (tripStops.isNotEmpty()) {
                val routeListState = rememberLazyListState()
                val onboardStatus = trackingStatus?.takeIf { it.phase == "탑승 중" || it.phase == "도착" }
                val currentStop = when {
                    !onboardStatus?.currentStopId.isNullOrBlank() ->
                        tripStops.firstOrNull { it.id == onboardStatus?.currentStopId }
                    onboardStatus != null -> routeStops.getOrNull(onboardStatus.currentStopIndex)
                        ?.let { stored -> tripStops.firstOrNull { it.id == stored.id } }
                    else -> currentStopOrder?.let { order ->
                        tripStops.minByOrNull { kotlin.math.abs(it.order - order) }
                    }
                }
                currentStop?.let { Text("현재 위치: ${it.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF9A5A00)) }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    state = routeListState,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Do not force-scroll to every new stop.  The trip starts
                    // on the left and the current marker advances to the
                    // right; longer routes remain manually scrollable.
                    items(tripStops, key = { it.id }) { stop ->
                        val isCurrent = stop.order == currentStop?.order
                        val isPassed = currentStop != null && stop.order < currentStop.order
                        val isBoarding = stop.id == bus.boardingStopId
                        val isDestination = stop.id == bus.destinationStopId
                        val background = when {
                            isCurrent -> Color(0xFFFFE0A6)
                            isPassed -> Color(0xFFD9EEDC)
                            isBoarding -> Color(0xFFD7EEEC)
                            isDestination -> Color(0xFFDCE8FF)
                            else -> Color(0xFFE8EDF0)
                        }
                        val marker = when {
                            isCurrent -> "현재 · "
                            isBoarding -> "승차 · "
                            isDestination -> "도착 · "
                            else -> ""
                        }
                        Surface(color = background, shape = RoundedCornerShape(14.dp)) {
                            Text(marker + stop.name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            val onboard = trackingStatus?.phase == "탑승 중"
            val arrived = trackingStatus?.phase == "도착"
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!onboard && !arrived) {
                    OutlinedButton(onClick = ::refresh, enabled = !loading, modifier = Modifier.padding(top = 4.dp)) {
                        Text(if (loading) "확인 중" else "도착정보 확인")
                    }
                } else {
                    Text(if (arrived) "자동 하차 완료" else "탑승 중", modifier = Modifier.padding(top = 8.dp), color = Color(0xFF00796B), fontWeight = FontWeight.Bold)
                }
                if (result.isNotBlank()) Text(result, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (keyDialogOpen) TagoKeyDialog(store, onDismiss = { keyDialogOpen = false }) { keyDialogOpen = false }
}

@Composable
private fun KtxRoutePanel(
    ktx: TravelKtxInfo,
    status: TravelTrackingStatus?,
    onRefreshLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF7F2FC),
        border = BorderStroke(1.dp, Color(0xFFD9C9EA)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("KTX 경로 · ${ktx.direction}", fontWeight = FontWeight.Bold, color = Color(0xFF5B3E76))
            if (ktx.departureTime.isNotBlank() || ktx.arrivalTime.isNotBlank()) {
                Text("${ktx.departureTime.ifBlank { "출발 시간 미입력" }} 출발 · ${ktx.arrivalTime.ifBlank { "도착 예정 미입력" }} 도착 예정", style = MaterialTheme.typography.bodyMedium)
            }
            if (ktx.trainNumber.isNotBlank()) Text("열차: ${ktx.trainNumber}", style = MaterialTheme.typography.bodyMedium)
            if (ktx.seatDetails.isNotBlank()) Text("호차·좌석: ${ktx.seatDetails}", style = MaterialTheme.typography.bodyMedium)
            val current = status?.currentStopIndex?.coerceIn(0, ktx.stops.lastIndex)
            current?.let { Text("현재 위치: ${ktx.stops[it].name}", color = Color(0xFF795548), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
            LazyRow(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                itemsIndexed(ktx.stops, key = { index, _ -> index }) { index, stop ->
                    val background = when {
                        current == index -> Color(0xFFFFE0A6)
                        current != null && index < current -> Color(0xFFD9EEDC)
                        index == 0 -> Color(0xFFDDEBFF)
                        index == ktx.stops.lastIndex -> Color(0xFFE9DFFF)
                        else -> Color(0xFFEDE8F2)
                    }
                    val marker = when {
                        current == index -> "현재 · "
                        index == 0 -> "출발 · "
                        index == ktx.stops.lastIndex -> "도착 · "
                        else -> ""
                    }
                    Surface(color = background, shape = RoundedCornerShape(14.dp)) {
                        Text(marker + stop.name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (status?.phase != "도착") {
                OutlinedButton(onClick = onRefreshLocation, modifier = Modifier.padding(top = 6.dp)) {
                    Text("현재 위치 새로 고침")
                }
            }
        }
    }
}

@Composable
private fun SubwayRoutePanel(
    cardId: String,
    routeIndex: Int,
    subway: TravelSubwayInfo,
    store: TravelStore,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var boardingInProgress by remember(cardId) { mutableStateOf(false) }
    var message by remember(cardId) { mutableStateOf("") }
    // The parent updates every three seconds.  Before the user taps boarding,
    // no current-station marker is displayed at all.
    val boarding = store.subwayBoarding(cardId, routeIndex)
    val now = System.currentTimeMillis()
    val currentIndex = boarding?.arrivalsEpochMillis
        ?.indexOfLast { it <= now }
        ?.coerceAtLeast(0)
        ?.coerceAtMost(subway.stops.lastIndex)
    val arrived = boarding?.alighted == true || (boarding != null && now >= (boarding.arrivalsEpochMillis.lastOrNull() ?: Long.MAX_VALUE))
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF0F5FC),
        border = BorderStroke(1.dp, Color(0xFFBFD2E7)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("지하철 경로 ${routeIndex + 1} · ${subway.direction}", fontWeight = FontWeight.Bold, color = Color(0xFF28577A))
            if (subway.timetableAsset.isBlank()) {
                Text(
                    "부산 시간표 API를 연결하면 탑승·현재 위치·하차 전 알림을 사용할 수 있습니다.",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF42615E),
                )
                return@Surface
            }
            if (boarding == null) {
                Text("내장 시간표 기준입니다. ‘탑승’을 누른 뒤에만 현재 위치를 표시합니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF42615E))
            } else {
                val departure = java.time.Instant.ofEpochMilli(boarding.departureEpochMillis)
                    .atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                val position = currentIndex ?: 0
                Text(
                    if (arrived) "시간표 기준 도착 · ${subway.stops.last()}" else "$departure 열차 탑승 중 · 현재 ${subway.stops[position]} 부근",
                    color = Color(0xFF28577A), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                )
            }
            LazyRow(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                itemsIndexed(subway.stops, key = { index, _ -> index }) { index, stop ->
                    val background = when {
                        currentIndex == index -> Color(0xFFFFE0A6)
                        currentIndex != null && index < currentIndex -> Color(0xFFD9EEDC)
                        index == 0 -> Color(0xFFDDEBFF)
                        index == subway.stops.lastIndex -> Color(0xFFE9DFFF)
                        else -> Color(0xFFEAF0F6)
                    }
                    val marker = when {
                        currentIndex == index -> "현재 · "
                        index == 0 -> "출발 · "
                        index == subway.stops.lastIndex -> "도착 · "
                        else -> ""
                    }
                    Surface(color = background, shape = RoundedCornerShape(14.dp)) {
                        Text(marker + stop, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (boarding == null) {
                OutlinedButton(
                    onClick = {
                        boardingInProgress = true; message = "시간표에서 가장 가까운 열차를 찾는 중…"
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { SubwayTimetableClient(store.appContext()).selectNearestTrip(subway, System.currentTimeMillis()) }
                            }.onSuccess { trip ->
                                store.saveSubwayBoarding(SubwayBoardingStatus(cardId, routeIndex, LocalDate.now().toString(), trip.departureEpochMillis, trip.arrivalsEpochMillis))
                                message = "시간표 열차를 선택했습니다."
                            }.onFailure { message = it.message ?: "지하철 시간표를 불러오지 못했습니다." }
                            boardingInProgress = false
                        }
                    },
                    enabled = !boardingInProgress,
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text(if (boardingInProgress) "탑승 처리 중" else "탑승") }
            } else if (!arrived) {
                OutlinedButton(
                    onClick = {
                        store.saveSubwayBoarding(boarding.copy(alighted = true))
                        message = "하차 처리했습니다."
                    },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("하차") }
            }
            if (message.isNotBlank()) Text(message, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Color(0xFF42615E))
        }
    }
}

@Composable
private fun TagoKeyDialog(store: TravelStore, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TAGO 버스 API 키 입력") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("공공데이터포털의 일반 인증키(Encoding)를 붙여넣으세요. 이 키는 이 휴대폰에만 암호화 저장되며 여행 ZIP에는 포함되지 않습니다.")
                OutlinedTextField(value, { value = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("일반 인증키") }, minLines = 3)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (value.isBlank()) error = "인증키를 붙여넣으세요."
                else { store.saveTagoApiKey(value); onSaved() }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun <T> TagoChoiceDialog(
    title: String,
    options: List<T>,
    label: (T) -> String,
    hint: String = "",
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember(options) { mutableStateOf("") }
    val filtered = remember(options, keyword) {
        options.filter { option -> keyword.isBlank() || label(option).contains(keyword.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hint.isNotBlank()) Text(hint, color = Color(0xFF42615E), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    keyword,
                    { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("이름 검색") },
                    singleLine = true,
                )
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    filtered.forEach { option ->
                        TextButton(onClick = { onChoose(option) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label(option), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (filtered.isEmpty()) Text("일치하는 항목이 없습니다.", modifier = Modifier.padding(12.dp), color = Color(0xFF64748B))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private fun phoneLink(raw: String): String? {
    val number = raw.trim().removePrefix("tel:").replace(" ", "")
    // This is a dedicated phone-number field.  Do not reject uncommon
    // business-number layouts: every nonblank entry is deliberately saved as
    // a dial link, exactly as the user entered it (apart from spaces/tel:).
    return number.takeIf { it.isNotBlank() }?.let { "tel:$it" }
}

private fun webLink(raw: String): String? {
    val value = raw.trim().removePrefix("url:")
    if (value.isBlank()) return null
    return when {
        // Keep an app's own URI intact: nmap:// opens Naver Map, while http(s)
        // remains a normal browser link. Prefix only plain domain names.
        Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value) -> value
        else -> "https://$value"
    }
}

private fun linkLabel(url: String): String = when {
    url.startsWith("tel:") -> url.removePrefix("tel:")
    url.startsWith("nmap:", ignoreCase = true) -> "네이버 지도 열기"
    else -> Uri.parse(url).host ?: "링크 열기"
}

@Composable
private fun ImageThumbnail(file: File, description: String, onClick: () -> Unit) {
    val bitmap = remember(file.path, file.lastModified()) { BitmapFactory.decodeFile(file.path) }
    if (bitmap == null) return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        modifier = Modifier.size(86.dp).clickable(onClick = onClick),
    )
}

@Composable
private fun PhoneAndUrlText(value: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val annotated = remember(value) { linkedText(value) }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF34444D)),
        onClick = { offset ->
            val phone = annotated.getStringAnnotations("phone", offset, offset).firstOrNull()?.item
            val web = annotated.getStringAnnotations("web", offset, offset).firstOrNull()?.item
            when {
                phone != null -> runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
                    .onFailure { Toast.makeText(context, "전화 앱을 열지 못했습니다.", Toast.LENGTH_SHORT).show() }
                web != null -> runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)))
                }
                    .onFailure { Toast.makeText(context, "링크를 열 수 없습니다.", Toast.LENGTH_SHORT).show() }
            }
        },
    )
}

private fun linkedText(value: String): AnnotatedString {
    val matches = (PHONE_PATTERN.findAll(value).map { it.range to "phone" } + WEB_PATTERN.findAll(value).map { it.range to "web" })
        .sortedBy { it.first.first }
    return buildAnnotatedString {
        var cursor = 0
        matches.forEach { (range, kind) ->
            if (range.first < cursor) return@forEach
            append(value.substring(cursor, range.first))
            val item = value.substring(range)
            pushStringAnnotation(kind, item)
            withStyle(SpanStyle(color = Color(0xFF006E7F), textDecoration = TextDecoration.Underline)) { append(item) }
            pop()
            cursor = range.last + 1
        }
        append(value.substring(cursor))
    }
}

@Composable
private fun CardEditorDialog(source: TravelCard?, store: TravelStore, onDismiss: () -> Unit, onSave: (TravelCard) -> Unit) {
    var dateText by remember(source) { mutableStateOf((dateFromCard(source ?: TravelCard()) ?: LocalDate.now()).toString()) }
    var timeInput by remember(source) {
        val initial = source?.time.orEmpty()
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    var title by remember(source) { mutableStateOf(source?.title ?: "") }
    var description by remember(source) { mutableStateOf(source?.description ?: "") }
    var ktxEnabled by remember(source) { mutableStateOf(source?.ktxInfo != null) }
    var ktxDepartureStation by remember(source) {
        mutableStateOf(source?.ktxInfo?.let { info ->
            info.departureStation.ifBlank { info.stops.firstOrNull()?.name.orEmpty() }
        }.orEmpty())
    }
    var ktxArrivalStation by remember(source) {
        mutableStateOf(source?.ktxInfo?.let { info ->
            info.arrivalStation.ifBlank { info.stops.lastOrNull()?.name.orEmpty() }
        }.orEmpty())
    }
    var ktxDepartureTime by remember(source) {
        val initial = source?.ktxInfo?.departureTime.orEmpty()
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    var ktxArrivalTime by remember(source) {
        val initial = source?.ktxInfo?.arrivalTime.orEmpty()
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    var ktxTrainNumber by remember(source) { mutableStateOf(source?.ktxInfo?.trainNumber.orEmpty()) }
    var ktxSeatDetails by remember(source) { mutableStateOf(source?.ktxInfo?.seatDetails.orEmpty()) }
    var ktxStationPicker by remember { mutableStateOf<KtxStationPicker?>(null) }
    // 기존 경로는 모두 확정된 목록으로 보관한다. 첫 경로를 새 입력칸에
    // 불러오면 다음 경로를 추가할 때 기존 경로를 덮어쓰게 된다.
    var savedSubwayPaths by remember(source) { mutableStateOf(source?.allSubwayInfos.orEmpty()) }
    var subwayEnabled by remember(source) { mutableStateOf(source?.allSubwayInfos?.isNotEmpty() == true) }
    var selectedSubwayRoute by remember(source) { mutableStateOf<TravelSubwayInfo?>(null) }
    var subwayLine by remember(source) { mutableStateOf("") }
    var subwayBoarding by remember(source) { mutableStateOf("") }
    var subwayDestination by remember(source) { mutableStateOf("") }
    var savedBusPaths by remember(source) { mutableStateOf(source?.allBusInfos.orEmpty()) }
    var busEnabled by remember(source) { mutableStateOf(source?.allBusInfos?.isNotEmpty() == true) }
    var selectedCity by remember(source) { mutableStateOf<TagoCity?>(null) }
    var routeNumber by remember(source) { mutableStateOf("") }
    var selectedRoute by remember(source) { mutableStateOf<TagoRoute?>(null) }
    var selectedBoarding by remember(source) { mutableStateOf<TagoStop?>(null) }
    var selectedDestination by remember(source) { mutableStateOf<TagoStop?>(null) }
    var busPathCandidates by remember { mutableStateOf(emptyList<BusPathCandidate>()) }
    var cityOptions: List<TagoCity> by remember { mutableStateOf(emptyList()) }
    var routeOptions: List<TagoRoute> by remember { mutableStateOf(emptyList()) }
    var stopOptions: List<TagoStop> by remember(source) { mutableStateOf(emptyList()) }
    var cityPickerOpen by remember { mutableStateOf(false) }
    var routePickerOpen by remember { mutableStateOf(false) }
    var stopPicker by remember { mutableStateOf<BusStopPicker?>(null) }
    var tagoKeyDialogOpen by remember { mutableStateOf(false) }
    var busLoading by remember { mutableStateOf(false) }
    var busMessage by remember { mutableStateOf("") }
    fun currentBusPath(): TravelBusInfo? {
        val city = selectedCity ?: return null
        val route = selectedRoute ?: return null
        val boarding = selectedBoarding ?: return null
        val destination = selectedDestination ?: return null
        if (destination.order <= boarding.order) return null
        return TravelBusInfo(
            city.code, city.name, route.id, route.number,
            splitRouteNumbers(routeNumber).ifEmpty { listOf(route.number) },
            boarding.id, boarding.name, boarding.order,
            destination.id, destination.name, destination.order,
            stopOptions.map { TravelBusStop(it.id, it.name, it.order, it.latitude, it.longitude) },
        )
    }
    fun clearCurrentBusPath() {
        selectedCity = null; selectedRoute = null; selectedBoarding = null; selectedDestination = null
        routeNumber = ""; routeOptions = emptyList(); stopOptions = emptyList(); busPathCandidates = emptyList()
    }
    fun currentKtxRoute(): TravelKtxInfo? =
        busanKtxRoute(ktxDepartureStation, ktxArrivalStation)?.copy(
            departureTime = ktxDepartureTime.text,
            arrivalTime = ktxArrivalTime.text,
            trainNumber = ktxTrainNumber.trim(),
            seatDetails = ktxSeatDetails.trim(),
        )
    fun currentSubwayPath(): TravelSubwayInfo? {
        val line = subwayLine.trim()
        val boarding = subwayBoarding.trim()
        val destination = subwayDestination.trim()
        if (line.isBlank() || boarding.isBlank() || destination.isBlank()) return null
        // The timetable asset is deliberately empty until the official Busan
        // timetable API request format is wired in.  Persist the route now,
        // but never pretend that it can track a train without that timetable.
        return TravelSubwayInfo("부산 ${line}호선 · $boarding → $destination", "", listOf(boarding, destination))
    }
    fun clearCurrentSubwayPath() {
        subwayLine = ""; subwayBoarding = ""; subwayDestination = ""; selectedSubwayRoute = null
    }
    // Older plans kept phone, web, and map links in one list. Keep storage
    // compatible, but expose separate inputs so app deep links never get
    // mistaken for web addresses.
    var phoneNumbers by remember(source) {
        mutableStateOf(source?.urls.orEmpty().filter { it.startsWith("tel:") }.joinToString("\n") { it.removePrefix("tel:") })
    }
    var webUrls by remember(source) {
        mutableStateOf(source?.urls.orEmpty().filterNot { it.startsWith("tel:") || it.startsWith("nmap:", ignoreCase = true) }.joinToString("\n"))
    }
    var naverMapUris by remember(source) {
        mutableStateOf(source?.urls.orEmpty().filter { it.startsWith("nmap:", ignoreCase = true) }.joinToString("\n"))
    }
    var images by remember(source) { mutableStateOf(source?.images ?: emptyList()) }
    var newlyAdded by remember { mutableStateOf(emptyList<TravelImage>()) }
    var state by remember(source) { mutableStateOf(source?.state ?: "권장") }
    var error by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(ContactTarget.DESCRIPTION) }
    var contactResult by remember { mutableStateOf(emptyList<PhoneContact>()) }
    var contactMessage by remember { mutableStateOf("") }
    var pendingQuery by remember { mutableStateOf(emptyList<String>()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    fun currentTagoKey(): String? {
        val key = store.tagoApiKey()
        if (key.isNullOrBlank()) tagoKeyDialogOpen = true
        return key
    }
    fun loadStops() {
        val city = selectedCity ?: return
        val route = selectedRoute ?: return
        val key = currentTagoKey() ?: return
        busLoading = true; busMessage = "정류장 목록을 불러오는 중입니다."
        coroutineScope.launch {
            runCatching { withContext(Dispatchers.IO) { TagoBusClient(key).routeStops(city.code, route.id) } }
                .onSuccess { stops ->
                    stopOptions = stops
                    busMessage = if (stops.isEmpty()) "이 노선의 정류장 정보가 없습니다." else "승차·도착 정류장을 선택하세요."
                }
                .onFailure { busMessage = "정류장 정보를 불러오지 못했습니다." }
            busLoading = false
        }
    }
    fun selectForwardRouteIfAvailable(boarding: TagoStop, destination: TagoStop) {
        val city = selectedCity ?: return
        val selected = selectedRoute ?: return
        val key = currentTagoKey() ?: return
        // The same stop name can occur twice within one round-trip list.  If
        // the user picked the return-side boarding stop, use the matching
        // earlier stop in this very route before trying another route ID.
        val matchingOutboundBoarding = stopOptions
            .filter { it.name.trim() == boarding.name.trim() && it.order < destination.order }
            .maxByOrNull { it.order }
        if (matchingOutboundBoarding != null) {
            selectedBoarding = matchingOutboundBoarding
            selectedDestination = destination
            busMessage = "정류장 순서가 맞도록 승차 정류장을 ${matchingOutboundBoarding.order}. ${matchingOutboundBoarding.name}(으)로 자동 변경했습니다."
            return
        }
        val alternatives = routeOptions.filter { it.id != selected.id }
        if (alternatives.isEmpty()) {
            busMessage = "선택한 정류장은 승차 정류장의 반대 방향입니다. 노선 방향을 다시 선택하세요."
            return
        }
        busLoading = true
        busMessage = "다른 방향 노선에서 정류장 순서를 확인하는 중입니다."
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = TagoBusClient(key)
                    alternatives.firstNotNullOfOrNull { route ->
                        val stops = client.routeStops(city.code, route.id)
                        val matchedBoarding = stops.firstOrNull { it.name.trim() == boarding.name.trim() }
                        val matchedDestination = stops.firstOrNull { it.name.trim() == destination.name.trim() }
                        if (matchedBoarding != null && matchedDestination != null && matchedDestination.order > matchedBoarding.order) {
                            Triple(route, matchedBoarding, matchedDestination) to stops
                        } else null
                    }
                }
            }.onSuccess { match ->
                if (match == null) {
                    busMessage = "이 승차·도착 조합으로 이동하는 같은 번호의 다른 방향 노선이 없습니다."
                } else {
                    val (routeAndStops, stops) = match
                    val (route, matchedBoarding, matchedDestination) = routeAndStops
                    selectedRoute = route
                    selectedBoarding = matchedBoarding
                    selectedDestination = matchedDestination
                    stopOptions = stops
                    busMessage = "정류장 순서가 맞는 ${route.number}번 방향으로 자동 선택했습니다."
                }
                busLoading = false
            }.onFailure {
                busLoading = false
                busMessage = "다른 방향 노선을 확인하지 못했습니다."
            }
        }
    }
    fun searchContacts(words: List<String>) {
        contactResult = findContacts(context, words)
        contactMessage = if (contactResult.isEmpty()) "일치하는 연락처가 없습니다." else "전화번호를 선택하세요. 선택한 번호는 설명에 넣습니다."
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) searchContacts(pendingQuery) else contactMessage = "연락처 권한을 허용한 뒤 다시 누르세요."
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val copied = store.copyImages(uris)
        images = images + copied
        newlyAdded = newlyAdded + copied
        if (uris.isNotEmpty() && copied.isEmpty()) error = "이미지를 첨부하지 못했습니다."
    }
    fun closeWithoutSave() { store.deleteImages(newlyAdded); onDismiss() }
    Dialog(onDismissRequest = ::closeWithoutSave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 720dp was cramped on a phone. 820dp gives about 8 mm more room at
        // both the top and bottom while still yielding to the IME.
        Surface(modifier = Modifier.fillMaxWidth().heightIn(max = 820.dp), shape = RoundedCornerShape(0.dp), color = Color(0xFFFFF9F5)) {
            Column(Modifier.fillMaxWidth().imePadding()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 18.dp, start = 18.dp, end = 18.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                Text(if (source == null) "새 이동 카드" else "이동 카드 수정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            dateText,
                            { dateText = it; if (error.startsWith("날짜")) error = "" },
                            Modifier.weight(1f).onFocusChanged { focus ->
                                if (!focus.isFocused) {
                                    val parsed = parseTravelDate(dateText)
                                    if (parsed == null) error = "날짜를 YYYY-MM-DD 형식으로 입력하세요."
                                    else dateText = parsed.toString()
                                }
                            },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                            label = { Text("날짜 (필수)", style = MaterialTheme.typography.bodyMedium) },
                            isError = error.startsWith("날짜"),
                        )
                        OutlinedTextField(
                            timeInput,
                            { incoming ->
                                val formatted = formatTravelTimeInput(incoming.text)
                                // Formatting inserts ':' and '–'. Put the
                                // caret after the generated text so the next
                                // digit cannot be inserted inside 09:40.
                                timeInput = TextFieldValue(formatted, selection = TextRange(formatted.length))
                            },
                            Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                            label = {
                                Text(
                                    if (ktxEnabled) "일정 시간 (KTX 아래 입력값 사용)" else "시간 (선택, 예: 10:00-11:10)",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                        )
                    }
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) target = ContactTarget.TITLE }, label = { Text("카드 제목") })
                    OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) target = ContactTarget.DESCRIPTION }, label = { Text("설명") }, minLines = 5)
                    OutlinedButton(onClick = {
                        val raw = if (target == ContactTarget.TITLE) title else description
                        pendingQuery = contactWords(raw)
                        if (pendingQuery.isEmpty()) contactMessage = "이름을 입력한 뒤 ‘연락처 찾기’를 누르세요."
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) searchContacts(pendingQuery)
                        else permission.launch(Manifest.permission.READ_CONTACTS)
                    }, modifier = Modifier.fillMaxWidth()) { Text("연락처 찾기") }
                    if (contactMessage.isNotBlank()) Text(contactMessage, color = Color(0xFF4E5C64), style = MaterialTheme.typography.bodySmall)
                    contactResult.forEach { contact ->
                        OutlinedButton(onClick = {
                            val insertion = "${contact.number} (${contact.name})"
                            if (!description.contains(contact.number)) description = (description.trimEnd() + "\n" + insertion).trim()
                            contactResult = emptyList(); contactMessage = "설명에 전화번호를 넣었습니다."
                        }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text(contact.name, fontWeight = FontWeight.Bold); Text(contact.number) } }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF9F5FF),
                        border = BorderStroke(1.dp, Color(0xFFD8C9EB)),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = ktxEnabled, onCheckedChange = { ktxEnabled = it })
                                Text("KTX 경로·현재 위치 표시", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clickable { ktxEnabled = !ktxEnabled })
                            }
                            if (ktxEnabled) {
                                Text("dev-1-new 기준으로 시간대·GPS 정확도·이동 속도를 확인해 자동 탑승 처리합니다.", color = Color(0xFF5B4B70), style = MaterialTheme.typography.bodySmall)
                                Text("출발역 → 도착역을 선택한 뒤 운행 정보를 입력하세요.", color = Color(0xFF5B4B70), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = { ktxStationPicker = KtxStationPicker.DEPARTURE },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(ktxDepartureStation.ifBlank { "1. 출발역 선택" }) }
                                OutlinedButton(
                                    onClick = { ktxStationPicker = KtxStationPicker.ARRIVAL },
                                    enabled = ktxDepartureStation.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(ktxArrivalStation.ifBlank { "2. 도착역 선택" }) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        ktxDepartureTime,
                                        { incoming ->
                                            val formatted = formatTravelTimeInput(incoming.text).take(5)
                                            ktxDepartureTime = TextFieldValue(formatted, selection = TextRange(formatted.length))
                                        },
                                        Modifier.weight(1f),
                                        label = { Text("출발 시간") },
                                        placeholder = { Text("10:00") },
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        ktxArrivalTime,
                                        { incoming ->
                                            val formatted = formatTravelTimeInput(incoming.text).take(5)
                                            ktxArrivalTime = TextFieldValue(formatted, selection = TextRange(formatted.length))
                                        },
                                        Modifier.weight(1f),
                                        label = { Text("도착 예정") },
                                        placeholder = { Text("12:00") },
                                        singleLine = true,
                                    )
                                }
                                OutlinedTextField(
                                    ktxTrainNumber,
                                    { ktxTrainNumber = it },
                                    Modifier.fillMaxWidth(),
                                    label = { Text("열차 번호") },
                                    placeholder = { Text("예: KTX 123") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    ktxSeatDetails,
                                    { ktxSeatDetails = it },
                                    Modifier.fillMaxWidth(),
                                    label = { Text("호차·좌석 (여러 개 자유 입력)") },
                                    placeholder = { Text("예: 5호차 8A, 5호차 8B\n6호차 2C") },
                                    minLines = 2,
                                )
                                Text(
                                    currentKtxRoute()?.direction ?: "출발역과 도착역을 다르게 선택하세요.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF0F5FC),
                        border = BorderStroke(1.dp, Color(0xFFBFD2E7)),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = subwayEnabled, onCheckedChange = { subwayEnabled = it })
                                Text("지하철 경로 메모 (자동 시간표 미지원)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clickable { subwayEnabled = !subwayEnabled })
                            }
                            if (subwayEnabled) {
                                Text("부산 → 호선 → 승차역 → 하차역 순서로 입력합니다.", color = Color(0xFF42615E), style = MaterialTheme.typography.bodySmall)
                                Text("지역: 부산", fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf("1", "2", "3", "4").forEach { line ->
                                        OutlinedButton(
                                            onClick = { subwayLine = line },
                                            modifier = Modifier.weight(1f),
                                            colors = if (subwayLine == line) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFDCECFB)) else ButtonDefaults.outlinedButtonColors(),
                                        ) { Text("${line}호선") }
                                    }
                                }
                                OutlinedTextField(subwayBoarding, { subwayBoarding = it }, Modifier.fillMaxWidth(), label = { Text("승차역") }, singleLine = true)
                                OutlinedTextField(subwayDestination, { subwayDestination = it }, Modifier.fillMaxWidth(), label = { Text("하차역") }, singleLine = true)
                                val pendingSubway = currentSubwayPath()
                                val shownSubways = savedSubwayPaths + listOfNotNull(pendingSubway)
                                if (shownSubways.isNotEmpty()) {
                                    Text("등록할 지하철 경로 ${shownSubways.size}개", fontWeight = FontWeight.Bold)
                                    shownSubways.forEachIndexed { index, route ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("${index + 1}. ${route.direction}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                            if (index < savedSubwayPaths.size) TextButton(onClick = { savedSubwayPaths = savedSubwayPaths.filterIndexed { savedIndex, _ -> savedIndex != index } }) { Text("삭제") }
                                            else Text("저장 시 확정", style = MaterialTheme.typography.bodySmall, color = Color(0xFF42615E))
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        val route = currentSubwayPath()
                                        if (route == null) error = "지하철 호선·승차역·하차역을 모두 입력하세요."
                                        else { savedSubwayPaths = savedSubwayPaths + route; clearCurrentSubwayPath() }
                                    },
                                    enabled = pendingSubway != null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("+ 지하철 경로 추가") }
                                Text("열차 현재 위치·하차 전 알림은 부산교통공사 시간표 API 연결 후에만 켜집니다.", color = Color(0xFF42615E), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF4FAF9),
                        border = BorderStroke(1.dp, Color(0xFFBEDAD6)),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = busEnabled,
                                    onCheckedChange = {
                                        busEnabled = it
                                        if (it && !store.hasTagoApiKey()) tagoKeyDialogOpen = true
                                    },
                                )
                                Text("버스 도착정보 표시", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clickable {
                                    busEnabled = !busEnabled
                                    if (busEnabled && !store.hasTagoApiKey()) tagoKeyDialogOpen = true
                                })
                            }
                            if (busEnabled) {
                                Text("TAGO 기준 · 지역 → 기준 버스 → 승차 → 도착 순서로 선택", color = Color(0xFF42615E), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = {
                                        val key = currentTagoKey() ?: return@OutlinedButton
                                        if (cityOptions.isNotEmpty()) cityPickerOpen = true
                                        else {
                                            busLoading = true; busMessage = "지역 목록을 불러오는 중입니다."
                                            coroutineScope.launch {
                                                runCatching { withContext(Dispatchers.IO) { TagoBusClient(key).cities() } }
                                                    .onSuccess { cities ->
                                                        cityOptions = cities
                                                        if (cities.isEmpty()) busMessage = "TAGO 제공 지역 정보가 없습니다." else cityPickerOpen = true
                                                    }
                                                    .onFailure { busMessage = "지역 목록을 불러오지 못했습니다." }
                                                busLoading = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(selectedCity?.name ?: "1. 지역 선택") }
                                if (selectedCity != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            routeNumber,
                                            { routeNumber = it; selectedRoute = null; selectedBoarding = null; selectedDestination = null; stopOptions = emptyList() },
                                            Modifier.weight(1f),
                                            label = { Text("2. 버스 번호 (쉼표로 여러 개)") },
                                            singleLine = true,
                                        )
                                        OutlinedButton(
                                            onClick = {
                                        val city = selectedCity ?: return@OutlinedButton
                                        val key = currentTagoKey() ?: return@OutlinedButton
                                        val requestedRoutes = splitRouteNumbers(routeNumber)
                                        if (requestedRoutes.isEmpty()) { busMessage = "버스 번호를 입력하세요."; return@OutlinedButton }
                                        busLoading = true; busMessage = "버스 노선을 찾는 중입니다."
                                        coroutineScope.launch {
                                            // The first number selects the shared route/stop sequence.
                                            // Other numbers are saved as alternatives and queried together.
                                            runCatching { withContext(Dispatchers.IO) { TagoBusClient(key).routes(city.code, requestedRoutes.first()) } }
                                                .onSuccess { routes ->
                                                    routeOptions = routes
                                                    if (routes.isEmpty()) busMessage = "일치하는 버스 노선이 없습니다." else routePickerOpen = true
                                                }
                                                .onFailure { busMessage = "버스 노선을 불러오지 못했습니다." }
                                            busLoading = false
                                        }
                                            },
                                            enabled = !busLoading,
                                        ) { Text("찾기") }
                                    }
                                    if (splitRouteNumbers(routeNumber).size > 1 && selectedRoute == null) {
                                        Text(
                                            "첫 번호로 정류장 순서를 고릅니다. 나머지 번호는 같은 승차·도착 정류장을 지나는 대체 버스로 함께 저장됩니다.",
                                            color = Color(0xFF42615E),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                if (selectedRoute != null) {
                                    Text("도착정보 조회: ${splitRouteNumbers(routeNumber).joinToString(", ")}번", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    OutlinedButton(
                                        onClick = { stopPicker = BusStopPicker.BOARDING; if (stopOptions.isEmpty()) loadStops() },
                                        enabled = !busLoading,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(selectedBoarding?.name ?: "3. 승차 정류장 선택") }
                                    if (selectedBoarding != null) {
                                        OutlinedButton(
                                            onClick = { stopPicker = BusStopPicker.DESTINATION; if (stopOptions.isEmpty()) loadStops() },
                                            enabled = !busLoading,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text(selectedDestination?.name ?: "4. 도착 정류장 선택") }
                                    }
                                }
                                val pendingBusPath = currentBusPath()
                                if (pendingBusPath != null) {
                                    Text(
                                        "저장 대기 경로: ${pendingBusPath.routeNumbers.joinToString(", ")}번 · ${pendingBusPath.boardingStopName} → ${pendingBusPath.destinationStopName}",
                                        color = Color(0xFF42615E),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    val hasBusInput = selectedCity != null || routeNumber.isNotBlank() || selectedRoute != null || selectedBoarding != null || selectedDestination != null
                                    Text(
                                        if (hasBusInput) "경로가 아직 완성되지 않았습니다. 승차 정류장 다음의 도착 정류장을 선택하세요."
                                        else "새 버스 경로를 입력하면 여기에서 목록에 추가할 수 있습니다.",
                                        color = Color(0xFF7A5B26),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        val path = pendingBusPath ?: return@OutlinedButton
                                        savedBusPaths = savedBusPaths + path
                                        clearCurrentBusPath()
                                        busMessage = "버스 경로 ${savedBusPaths.size}개를 등록했습니다. 다음 경로를 입력하거나 저장하세요."
                                    },
                                    enabled = pendingBusPath != null && !busLoading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("+ 버스 경로 추가") }
                                val visibleBusPaths = savedBusPaths + listOfNotNull(pendingBusPath)
                                if (visibleBusPaths.isNotEmpty()) {
                                    Text(
                                        if (pendingBusPath == null) "등록한 버스 경로 ${savedBusPaths.size}개"
                                        else "버스 경로 ${visibleBusPaths.size}개 · 마지막 경로는 저장 시 확정",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF08675E),
                                    )
                                    visibleBusPaths.forEachIndexed { index, path ->
                                        val isPending = index == savedBusPaths.size && pendingBusPath != null
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "${index + 1}. ${path.routeNumbers.joinToString(", ")}번 · ${path.boardingStopName} → ${path.destinationStopName} · ${if (isPending) "저장 시 확정" else "확정됨"}",
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            if (isPending) {
                                                TextButton(onClick = ::clearCurrentBusPath) { Text("취소") }
                                            } else {
                                                TextButton(onClick = { savedBusPaths = savedBusPaths.filterIndexed { itemIndex, _ -> itemIndex != index } }) { Text("삭제") }
                                            }
                                        }
                                    }
                                }
                                if (busLoading) Text("불러오는 중…", color = Color(0xFF42615E), style = MaterialTheme.typography.bodySmall)
                                if (busMessage.isNotBlank()) Text(busMessage, color = Color(0xFF42615E), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(
                        phoneNumbers,
                        { phoneNumbers = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("전화번호 (한 줄에 하나)") },
                        supportingText = { Text("tel: 없이 전화번호만 입력하세요.") },
                        minLines = 2,
                    )
                    OutlinedTextField(
                        webUrls,
                        { webUrls = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("웹 주소 (한 줄에 하나)") },
                        supportingText = { Text("http:// 또는 https://로 시작하는 웹 주소를 입력하세요.") },
                        minLines = 2,
                    )
                    OutlinedTextField(
                        naverMapUris,
                        { naverMapUris = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("네이버 지도 주소 (nmap://, 한 줄에 하나)") },
                        minLines = 1,
                    )
                    OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("+ 갤러리에서 이미지 선택") }
                    if (images.isNotEmpty()) images.forEach { image ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(image.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { images = images.filterNot { it.id == image.id }; if (image in newlyAdded) newlyAdded = newlyAdded - image }) { Text("이미지 삭제") }
                        }
                    }
                    Text("일정 상태", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("확정", "권장", "후보", "확인 필요").forEach { option ->
                            if (state == option) Button(onClick = { state = option }) { Text(option) }
                            else OutlinedButton(onClick = { state = option }) { Text(option) }
                        }
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(color = Color(0xFFE0DAD4))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Default action row was about 48dp. Add roughly 7 mm.
                        .height(92.dp)
                        .padding(start = 18.dp, end = 18.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    TextButton(onClick = ::closeWithoutSave) { Text("취소") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val selectedDate = parseTravelDate(dateText)
                        if (selectedDate == null) {
                            error = "날짜를 YYYY-MM-DD 형식으로 입력하세요."
                        } else if (title.isBlank()) {
                            error = "카드 제목을 입력하세요."
                        } else {
                            val savedKtx = if (!ktxEnabled) null else currentKtxRoute() ?: run {
                                error = "KTX 출발역과 도착역을 다르게 선택하세요."
                                return@Button
                            }
                            val validKtxTimes = savedKtx == null || listOf(savedKtx.departureTime, savedKtx.arrivalTime).all { value ->
                                Regex("\\d{2}:\\d{2}").matches(value) && runCatching { java.time.LocalTime.parse(value) }.isSuccess
                            }
                            if (!validKtxTimes) {
                                error = "KTX 출발 시간과 도착 예정 시간을 HH:MM 형식으로 입력하세요."
                                return@Button
                            }
                            val savedSubways = if (!subwayEnabled) emptyList() else (savedSubwayPaths + listOfNotNull(currentSubwayPath())).takeIf { it.isNotEmpty() } ?: run {
                                error = "지하철 경로를 하나 이상 선택하세요."
                                return@Button
                            }
                            val savedBuses = if (!busEnabled) emptyList() else {
                                val current = currentBusPath()
                                // Intermediate paths are committed with the add button.  The last
                                // selected path is committed by the card's Save action, so the user
                                // does not have to press an unnecessary add button at the end.
                                if (current != null) {
                                    savedBusPaths + current
                                } else {
                                    val hasUnfinishedBusSelection = selectedCity != null || routeNumber.isNotBlank() || selectedRoute != null || selectedBoarding != null || selectedDestination != null
                                    if (hasUnfinishedBusSelection) {
                                        error = "입력 중인 버스 경로를 완성한 뒤 저장하세요. 출발·도착 정류장은 같은 진행 방향이어야 합니다."
                                        return@Button
                                    }
                                    if (savedBusPaths.isEmpty()) {
                                        error = "버스 경로는 지역, 버스, 승차 정류장, 도착 정류장을 모두 선택한 뒤 추가하세요."
                                        return@Button
                                    }
                                    savedBusPaths
                                }
                            }
                            val cleanedPhones = phoneNumbers.lines().mapNotNull(::phoneLink)
                            val cleanedWebUrls = webUrls.lines().mapNotNull(::webLink)
                            val cleanedNaverMaps = naverMapUris.lines()
                                .map { it.trim() }
                                .filter { it.startsWith("nmap:", ignoreCase = true) }
                            onSave(TravelCard(
                                id = source?.id ?: UUID.randomUUID().toString(),
                                day = travelDayLabel(selectedDate),
                                sortDate = selectedDate.toString(),
                                // KTX 자동 탑승은 카드 시간에서 출발·도착 구간을
                                // 읽는다. KTX 입력값을 이 필드에도 확정해 추적 기준과
                                // 화면에 보이는 시간이 절대 어긋나지 않게 한다.
                                time = savedKtx?.let { "${it.departureTime}–${it.arrivalTime}" } ?: timeInput.text.trim(),
                                title = title.trim(),
                                description = description.trim(),
                                busInfo = savedBuses.firstOrNull(),
                                busInfos = savedBuses,
                                ktxInfo = savedKtx,
                                subwayInfo = savedSubways.firstOrNull(),
                                subwayInfos = savedSubways,
                                urls = cleanedPhones + cleanedWebUrls + cleanedNaverMaps,
                                images = images,
                                state = state,
                            ))
                        }
                    }) { Text("저장") }
                }
            }
        }
    }
    ktxStationPicker?.let { picker ->
        val options = BUSAN_KTX_STOPS.filter { stop ->
            picker != KtxStationPicker.ARRIVAL || stop.name != ktxDepartureStation
        }
        TagoChoiceDialog(
            title = if (picker == KtxStationPicker.DEPARTURE) "1. KTX 출발역 선택" else "2. KTX 도착역 선택",
            options = options,
            label = { it.name },
            hint = "현재는 대전역·김천구미역·동대구역·울산역·부산역 구간을 선택할 수 있습니다.",
            onChoose = { stop ->
                if (picker == KtxStationPicker.DEPARTURE) {
                    ktxDepartureStation = stop.name
                    if (ktxArrivalStation == stop.name) ktxArrivalStation = ""
                } else {
                    ktxArrivalStation = stop.name
                }
                ktxStationPicker = null
            },
            onDismiss = { ktxStationPicker = null },
        )
    }
    if (tagoKeyDialogOpen) TagoKeyDialog(store, onDismiss = { tagoKeyDialogOpen = false }) {
        tagoKeyDialogOpen = false
        busMessage = "TAGO API 키를 저장했습니다. 계속 선택하세요."
    }
    if (cityPickerOpen) TagoChoiceDialog(
        title = "1. 지역 선택",
        options = cityOptions,
        label = { it.name },
        onChoose = { city ->
            selectedCity = city
            selectedRoute = null; selectedBoarding = null; selectedDestination = null
            routeOptions = emptyList(); stopOptions = emptyList(); cityPickerOpen = false
            busMessage = "${city.name}을 선택했습니다. 버스 번호를 입력하세요."
        },
        onDismiss = { cityPickerOpen = false },
    )
    if (routePickerOpen) TagoChoiceDialog(
        title = "2. ${splitRouteNumbers(routeNumber).firstOrNull() ?: "기준"}번 기준 노선 선택",
        options = routeOptions,
        label = { route ->
            val ends = listOf(route.startName, route.endName).filter(String::isNotBlank).joinToString(" → ")
            if (ends.isBlank()) "${route.number}번" else "${route.number}번  $ends"
        },
        hint = buildList {
            val alternatives = splitRouteNumbers(routeNumber).drop(1)
            if (alternatives.isNotEmpty()) add("${alternatives.joinToString(", ")}번은 같은 정류장을 지나는 대체 버스로 함께 저장됩니다.")
            if (routeOptions.size > 1) add("같은 번호의 방향 후보입니다. 먼저 하나를 선택한 뒤 승차·도착 정류장을 고르세요. 순서가 반대이면 맞는 방향으로 자동 변경합니다.")
        }.joinToString("\n"),
        onChoose = { route ->
            selectedRoute = route
            val alternatives = splitRouteNumbers(routeNumber).filterNot { it == route.number }
            routeNumber = (listOf(route.number) + alternatives).joinToString(", ")
            selectedBoarding = null; selectedDestination = null; stopOptions = emptyList(); routePickerOpen = false
            loadStops()
        },
        onDismiss = { routePickerOpen = false },
    )
    stopPicker?.let { targetPicker ->
        // Stop names can repeat in a round-trip route.  The user chooses each
        // name once; after both names are known we resolve the actual pair.
        val pickerStops = when (targetPicker) {
            BusStopPicker.BOARDING -> stopOptions.distinctBy { it.name.trim() }
            BusStopPicker.DESTINATION -> stopOptions.distinctBy { it.name.trim() }
        }
        if (pickerStops.isNotEmpty()) TagoChoiceDialog(
            title = if (targetPicker == BusStopPicker.BOARDING) "3. 승차 정류장 선택" else "4. 도착 정류장 선택",
            options = pickerStops,
            label = { it.name },
            hint = if (targetPicker == BusStopPicker.DESTINATION && selectedBoarding != null) "승차·도착 이름을 고르면 진행 방향을 자동으로 판단합니다." else "",
            onChoose = { stop ->
                if (targetPicker == BusStopPicker.BOARDING) {
                    selectedBoarding = stop
                    selectedDestination = null
                    busPathCandidates = emptyList()
                    busMessage = "승차 정류장을 선택했습니다. 도착 정류장을 선택하세요."
                } else {
                    val boardingName = selectedBoarding?.name.orEmpty().trim()
                    val destinationName = stop.name.trim()
                    val candidates = stopOptions
                        .filter { it.name.trim() == boardingName }
                        .flatMap { boarding ->
                            stopOptions
                                .filter { destination -> destination.name.trim() == destinationName && destination.order > boarding.order }
                                .map { destination -> BusPathCandidate(boarding, destination) }
                        }
                        .distinctBy { "${it.boarding.id}:${it.destination.id}" }
                    when {
                        candidates.size == 1 -> {
                            selectedBoarding = candidates.single().boarding
                            selectedDestination = candidates.single().destination
                            busMessage = "${candidates.single().boarding.name} → ${candidates.single().destination.name} 경로를 자동 선택했습니다."
                        }
                        candidates.size > 1 -> {
                            selectedDestination = null
                            busPathCandidates = candidates
                            busMessage = "가능한 진행 방향이 ${candidates.size}개입니다. 경로를 선택하세요."
                        }
                        else -> {
                            selectedDestination = stop
                            selectedBoarding?.let { boarding -> selectForwardRouteIfAvailable(boarding, stop) }
                            busMessage = "같은 노선에서 진행 방향을 확인하는 중입니다."
                        }
                    }
                }
                stopPicker = null
            },
            onDismiss = { stopPicker = null },
        )
    }
    if (busPathCandidates.isNotEmpty()) TagoChoiceDialog(
        title = "진행 방향 선택",
        options = busPathCandidates,
        label = { candidate ->
            val nextStop = stopOptions.firstOrNull { it.order == candidate.boarding.order + 1 }?.name ?: "다음 정류장 정보 없음"
            val stopCount = candidate.destination.order - candidate.boarding.order
            "${candidate.boarding.name} → ${candidate.destination.name}\n다음 정류장: $nextStop · 총 ${stopCount}정거장"
        },
        hint = "같은 이름의 정류장이 여러 번 있어 경로를 골라야 합니다.",
        onChoose = { candidate ->
            selectedBoarding = candidate.boarding
            selectedDestination = candidate.destination
            busPathCandidates = emptyList()
            busMessage = "${candidate.boarding.name} → ${candidate.destination.name} 경로를 선택했습니다."
        },
        onDismiss = { busPathCandidates = emptyList() },
    )
}

private fun contactWords(text: String): List<String> = text.trim().split(Regex("\\s+")).map { it.trim() }
    .filter { it.length >= 2 && !it.matches(Regex("\\d{1,2}(:\\d{2})?")) && it !in setOf("전화", "하기", "연락", "연락처") }

private fun findContacts(context: Context, words: List<String>): List<PhoneContact> {
    if (words.isEmpty()) return emptyList()
    val found = linkedSetOf<PhoneContact>()
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null,
    )?.use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameIndex).orEmpty()
            if (words.all { name.contains(it, ignoreCase = true) }) found += PhoneContact(name, cursor.getString(numberIndex).orEmpty())
        }
    }
    return found.take(20)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageViewerDialog(images: List<TravelImage>, initialId: String, store: TravelStore, onDismiss: () -> Unit) {
    val initialIndex = images.indexOfFirst { it.id == initialId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
    val scope = rememberCoroutineScope()
    val current = images.getOrNull(pagerState.currentPage) ?: return
    var resetVersion by remember { mutableIntStateOf(0) }
    var imageTransformActive by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF202124)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(current.displayName, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (images.size > 1) {
                        TextButton(onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) } }, enabled = pagerState.currentPage > 0) { Text("‹ 이전", color = Color.White) }
                        Text("${pagerState.currentPage + 1}/${images.size}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(images.lastIndex)) } }, enabled = pagerState.currentPage < images.lastIndex) { Text("다음 ›", color = Color.White) }
                    }
                    TextButton(onClick = { resetVersion++ }) { Text("원래 크기", color = Color.White) }
                    TextButton(onClick = onDismiss) { Text("닫기", color = Color.White) }
                }
                HorizontalPager(
                    state = pagerState,
                    // A two-finger pinch belongs to the image, not to page navigation.
                    userScrollEnabled = !imageTransformActive,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val image = images[page]
                    val file = store.imageFile(image)
                    val bitmap = remember(file.path, file.lastModified()) { BitmapFactory.decodeFile(file.path) }
                    var pageScale by remember(image.id, resetVersion) { mutableStateOf(1f) }
                    var pageOffsetX by remember(image.id, resetVersion) { mutableStateOf(0f) }
                    var pageOffsetY by remember(image.id, resetVersion) { mutableStateOf(0f) }
                    // At normal size, do not consume one-finger drags: the pager
                    // receives those as previous/next image swipes.  Two fingers
                    // always start zooming; an already enlarged image also pans
                    // with one finger.
                    val imageGesture = Modifier.pointerInput(image.id, resetVersion) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var transformed = false
                                do {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }
                                    val shouldTransform = pressedCount >= 2 || pageScale > 1f
                                    if (shouldTransform) {
                                        transformed = true
                                        imageTransformActive = true
                                        pageScale = (pageScale * event.calculateZoom()).coerceIn(1f, 5f)
                                        val pan = event.calculatePan()
                                        if (pageScale == 1f) {
                                            pageOffsetX = 0f
                                            pageOffsetY = 0f
                                        } else {
                                            pageOffsetX += pan.x
                                            pageOffsetY += pan.y
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (transformed) imageTransformActive = false
                            }
                        }
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black).then(imageGesture), contentAlignment = Alignment.Center) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = image.displayName, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = pageScale; scaleY = pageScale; translationX = pageOffsetX; translationY = pageOffsetY })
                        else Text("이미지를 찾지 못했습니다.", color = Color.White)
                    }
                }
            }
        }
    }
}
