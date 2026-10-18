package com.cobra.busantravel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * The travel app intentionally uses the same conservative GPS thresholds as dev-1-new.
 * A card is never marked onboard solely because its clock time has arrived.
 */
class TravelTrackingService : Service() {
    private lateinit var store: TravelStore
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private val handler = Handler(Looper.getMainLooper())
    // KTX는 객차 안에서 GPS 첫 수신을 놓칠 수 있다. 사용자가 누르는 새로 고침은
    // 다음의 새 위치 수신으로 같은 자동 탑승 기준을 다시 적용하는 복구 수단이다.
    private var ktxRecoveryCardId: String? = null
    private var ktxRecoveryRequestedAt: Long = 0L

    private val recoveryTimeout = Runnable {
        val cardId = ktxRecoveryCardId ?: return@Runnable
        store.saveTrackingStatus(
            TravelTrackingStatus(
                cardId = cardId,
                date = LocalDate.now().toString(),
                phase = "대기",
                currentStopIndex = 0,
                message = "현재 위치를 확인하지 못했습니다. GPS가 잡히는 곳에서 다시 눌러주세요.",
            )
        )
        ktxRecoveryCardId = null
        ktxRecoveryRequestedAt = 0L
    }

    private val tick = object : Runnable {
        override fun run() {
            val active = activeCards()
            // A foreground location service is legally required to expose a
            // notification while tracking.  Do not leave a meaningless
            // “waiting” item in the status area outside an actual trip.
            if (active.isEmpty() && ktxRecoveryCardId == null) {
                stopTrackingWhenIdle()
                return
            }
            val needsLocation = active.any { it.allBusInfos.isNotEmpty() || it.ktxInfo != null } || ktxRecoveryCardId != null
            updateLocationSubscription(needsLocation)
            val hasBus = active.any { it.allBusInfos.isNotEmpty() }
            val hasKtx = active.any { it.ktxInfo != null }
            postNotification(
                when {
                    hasBus && hasKtx -> "버스·KTX 자동 이동을 확인 중"
                    hasKtx -> "KTX 자동 이동을 확인 중"
                    else -> "버스 자동 탑승을 확인 중"
                }
            )
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = TravelStore(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startTrackingForeground()
        if (intent?.action == ACTION_REFRESH_KTX_LOCATION) {
            refreshKtxLocation(intent.getStringExtra(EXTRA_CARD_ID))
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(recoveryTimeout)
        val locationManager = manager
        val locationListener = listener
        if (locationManager != null && locationListener != null) runCatching { locationManager.removeUpdates(locationListener) }
        listener = null
        manager = null
        super.onDestroy()
    }

    private fun refreshKtxLocation(cardId: String?) {
        val card = cardId?.let { id -> store.load().cards.firstOrNull { it.id == id && it.ktxInfo != null } }
        if (card == null) return
        val interval = card.ktxInfo?.let { route -> ktxInterval(card, route) }
        if (interval == null || LocalDateTime.now() !in interval.first..interval.second) {
            store.saveTrackingStatus(
                TravelTrackingStatus(card.id, LocalDate.now().toString(), "대기", 0, "현재 위치 새로 고침은 KTX 출발 시각부터 도착 시각 사이에 사용할 수 있습니다.")
            )
            return
        }
        ktxRecoveryCardId = card.id
        ktxRecoveryRequestedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(recoveryTimeout)
        handler.postDelayed(recoveryTimeout, 60_000L)
        store.saveTrackingStatus(TravelTrackingStatus(card.id, LocalDate.now().toString(), "대기", 0, "새 GPS 위치를 확인하는 중입니다."))
        // 기존 위치 구독이 오래된 위치를 계속 주지 않도록 새로 등록한다.
        manager?.let { current -> listener?.let { runCatching { current.removeUpdates(it) } } }
        listener = null
        manager = null
        updateLocationSubscription(true)
    }

    private fun startTrackingForeground() {
        val notification = notification("여행 자동 이동 확인 준비 중")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopTrackingWhenIdle() {
        handler.removeCallbacks(tick)
        manager?.let { current -> listener?.let { runCatching { current.removeUpdates(it) } } }
        listener = null
        manager = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun updateLocationSubscription(shouldTrack: Boolean) {
        if (!shouldTrack) {
            manager?.let { current -> listener?.let { runCatching { current.removeUpdates(it) } } }
            listener = null; manager = null
            return
        }
        if (listener != null) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = handleLocation(location)
        }
        manager = locationManager; listener = locationListener
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 5_000L, 0f, locationListener, Looper.getMainLooper())
                }
            }
        }
    }

    private fun activeCards(): List<TravelCard> {
        val now = LocalDateTime.now()
        return store.load().cards.filter { card ->
            // Arrival is terminal for today's card: stop GPS and remove the
            // foreground notification instead of continuing to poll it.
            if (store.trackingStatus(card.id)?.phase == "도착") return@filter false

            // Bus uses its saved travel date (but no clock requirement).
            // KTX already includes its own card date plus KTX time window.
            // Subway tracking stays disabled until a verified timetable source
            // is connected.
            val busActive = card.allBusInfos.isNotEmpty() && busTravelDateIsToday(card)
            val ktxActive = card.ktxInfo?.let { route -> ktxTrackingWindow(card, route, now) } == true
            busActive || ktxActive
        }
    }

    private fun handleLocation(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val speed = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val now = LocalDateTime.now()
        val cards = activeCards().toMutableList()
        ktxRecoveryCardId?.let { recoveryId ->
            store.load().cards.firstOrNull { it.id == recoveryId && it.ktxInfo != null }?.let { recoveryCard ->
                if (cards.none { it.id == recoveryCard.id }) cards += recoveryCard
            }
        }
        cards.forEach { card ->
            card.ktxInfo?.takeIf { route ->
                ktxTrackingWindow(card, route, now) || ktxRecoveryCardId == card.id
            }?.let { handleKtx(card, it, location, accuracy, speed, now) }
            // A card may show several alternative bus paths.  For automatic
            // tracking use the first registered path; every path still has
            // its own on-demand arrival information in the card.
            card.allBusInfos.firstOrNull()?.let { handleBus(card, it, location, accuracy, speed) }
        }
    }

    private fun handleKtx(card: TravelCard, route: TravelKtxInfo, location: Location, accuracy: Float?, speed: Float, now: LocalDateTime) {
        val interval = ktxInterval(card, route) ?: return
        val previous = store.trackingStatus(card.id)
        val onBoard = previous?.phase == "탑승 중"
        val recovering = ktxRecoveryCardId == card.id
        val locationElapsed = if (location.elapsedRealtimeNanos > 0L) location.elapsedRealtimeNanos / 1_000_000L else SystemClock.elapsedRealtime()
        if (recovering && locationElapsed < ktxRecoveryRequestedAt) return
        if (!onBoard) {
            // dev-1-new KTX criteria: scheduled departure~arrival, 20 km/h and 100 m accuracy.
            if (now !in interval.first..interval.second || speed < 20f || accuracy == null || accuracy > 100f) {
                val message = if (recovering) {
                    "새 위치를 받았지만 아직 탑승으로 판단할 수 없습니다. GPS 정확도와 이동 속도를 다시 확인합니다."
                } else "KTX 자동 탑승 확인 중"
                store.saveTrackingStatus(TravelTrackingStatus(card.id, LocalDate.now().toString(), "대기", 0, message))
                return
            }
        }
        val index = nearestStop(route.stops, location).coerceAtLeast(0)
        val arrived = onBoard && accuracy != null && accuracy <= 250f && speed < 5f &&
            distance(location.latitude, location.longitude, route.stops.last().latitude, route.stops.last().longitude) <= 2_000.0
        val phase = if (arrived) "도착" else "탑승 중"
        val message = if (arrived) "KTX 도착: ${route.stops.last().name}" else "KTX 탑승 중 · 현재 ${route.stops[index].name} 부근"
        store.saveTrackingStatus(TravelTrackingStatus(card.id, LocalDate.now().toString(), phase, index, message))
        if (recovering) {
            ktxRecoveryCardId = null
            ktxRecoveryRequestedAt = 0L
            handler.removeCallbacks(recoveryTimeout)
        }
    }

    private fun handleBus(card: TravelCard, bus: TravelBusInfo, location: Location, accuracy: Float?, speed: Float) {
        val stops = bus.routeStops.filter { it.latitude != null && it.longitude != null }
        if (stops.size < 2 || accuracy == null || accuracy > 80f) {
            store.saveTrackingStatus(TravelTrackingStatus(card.id, LocalDate.now().toString(), "대기", 0, "버스 자동 탑승 확인 중"))
            return
        }
        val projection = project(stops, location.latitude, location.longitude) ?: return
        // Bus boarding criteria: a known route (two or more stops), GPS
        // accuracy within 80m, route distance within 250m, and 20km/h.
        if (projection.distance > 250.0) return
        val previous = store.trackingStatus(card.id)
        if (previous?.phase != "탑승 중" && previous?.phase != "도착") {
            if (speed < 20f) return
        }
        val index = nearestStop(stops.map { TravelKtxStop(it.name, it.latitude!!, it.longitude!!) }, location).coerceAtLeast(0)
        val destinationIndex = stops.indexOfFirst { it.id == bus.destinationStopId }.takeIf { it >= 0 } ?: stops.lastIndex
        val arrived = index >= destinationIndex && speed < 5f
        val phase = if (arrived) "도착" else "탑승 중"
        val message = if (arrived) "버스 도착: ${bus.destinationStopName}" else "${bus.routeNumber}번 탑승 중 · 현재 ${stops[index].name} 부근"
        store.saveTrackingStatus(TravelTrackingStatus(card.id, LocalDate.now().toString(), phase, index, message))
    }

    /** Same fixed-train timetable rule as dev-1-new: alert once at two and one stops before destination. */
    private fun evaluateSubwayTimetable(card: TravelCard, routeIndex: Int, subway: TravelSubwayInfo) {
        val boarding = store.subwayBoarding(card.id, routeIndex) ?: return
        if (boarding.alighted || boarding.arrivalsEpochMillis.size != subway.stops.size) return
        val now = System.currentTimeMillis()
        val current = boarding.arrivalsEpochMillis.indexOfLast { it <= now }
            .coerceAtLeast(0).coerceAtMost(subway.stops.lastIndex)
        val remaining = (subway.stops.lastIndex - current).coerceAtLeast(0)
        var updated = boarding
        if (remaining in 1..2 && !updated.twoStopsAlerted) {
            postSubwayEvent("지하철 하차 2정거장 전", "${subway.stops.last()}까지 2정거장 남았습니다")
            updated = updated.copy(twoStopsAlerted = true)
        }
        if (remaining == 1 && !updated.oneStopAlerted) {
            postSubwayEvent("지하철 하차 1정거장 전", "다음 역은 ${subway.stops.last()}입니다")
            updated = updated.copy(oneStopAlerted = true)
        }
        if (updated != boarding) store.saveSubwayBoarding(updated)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "여행 자동 이동 확인", NotificationManager.IMPORTANCE_LOW)
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(SUBWAY_ALERT_CHANNEL_ID, "여행 지하철 하차 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                }
            )
        }
    }

    private fun postNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_map)
        .setContentTitle("여행 자동 이동 확인")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun postSubwayEvent(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, SUBWAY_ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(SUBWAY_ALERT_NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "travel_tracking"
        const val NOTIFICATION_ID = 6306
        const val SUBWAY_ALERT_CHANNEL_ID = "travel_subway_alert"
        const val SUBWAY_ALERT_NOTIFICATION_ID = 6307
        const val ACTION_REFRESH_KTX_LOCATION = "com.cobra.busantravel.action.REFRESH_KTX_LOCATION"
        const val EXTRA_CARD_ID = "card_id"
    }
}

private data class TravelProjection(val distance: Double, val progress: Double)

private fun busTravelDateIsToday(card: TravelCard): Boolean =
    runCatching { LocalDate.parse(card.sortDate) }.getOrNull() == LocalDate.now()

private fun trackingWindow(card: TravelCard, now: LocalDateTime): Boolean {
    val range = cardInterval(card) ?: return false
    return now >= range.first.minusMinutes(15) && now <= range.second.plusMinutes(90)
}

private fun ktxTrackingWindow(card: TravelCard, route: TravelKtxInfo, now: LocalDateTime): Boolean {
    val range = ktxInterval(card, route) ?: return false
    return now >= range.first.minusMinutes(15) && now <= range.second.plusMinutes(90)
}

/** KTX tracking intentionally reads the KTX panel, never the card's general time label. */
private fun ktxInterval(card: TravelCard, route: TravelKtxInfo): Pair<LocalDateTime, LocalDateTime>? {
    val day = runCatching { LocalDate.parse(card.sortDate) }.getOrNull() ?: return null
    val start = runCatching { LocalTime.parse(route.departureTime) }.getOrNull() ?: return null
    val end = runCatching { LocalTime.parse(route.arrivalTime) }.getOrNull() ?: return null
    val startAt = LocalDateTime.of(day, start)
    val endAt = LocalDateTime.of(day, end).let { if (it < startAt) it.plusDays(1) else it }
    return startAt to endAt
}

private fun cardInterval(card: TravelCard): Pair<LocalDateTime, LocalDateTime>? {
    val day = runCatching { LocalDate.parse(card.sortDate) }.getOrNull() ?: return null
    val times = Regex("(\\d{1,2}):(\\d{2})").findAll(card.time).mapNotNull {
        runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull()
    }.toList()
    val start = times.firstOrNull() ?: return null
    val end = times.getOrNull(1) ?: start.plusMinutes(90)
    return LocalDateTime.of(day, start) to LocalDateTime.of(day, end)
}

private fun nearestStop(stops: List<TravelKtxStop>, location: Location): Int = stops.indices.minByOrNull { index ->
    distance(location.latitude, location.longitude, stops[index].latitude, stops[index].longitude)
} ?: 0

private fun project(stops: List<TravelBusStop>, latitude: Double, longitude: Double): TravelProjection? {
    if (stops.size < 2) return null
    var bestDistance = Double.MAX_VALUE
    var bestProgress = 0.0
    var accumulated = 0.0
    for (index in 0 until stops.lastIndex) {
        val a = stops[index]; val b = stops[index + 1]
        val segment = distance(a.latitude!!, a.longitude!!, b.latitude!!, b.longitude!!)
        if (segment < 0.1) continue
        val t = projectionFraction(a.latitude, a.longitude, b.latitude, b.longitude, latitude, longitude)
        val pointLat = a.latitude + (b.latitude - a.latitude) * t
        val pointLon = a.longitude + (b.longitude - a.longitude) * t
        val distance = distance(latitude, longitude, pointLat, pointLon)
        if (distance < bestDistance) { bestDistance = distance; bestProgress = accumulated + segment * t }
        accumulated += segment
    }
    return TravelProjection(bestDistance, bestProgress)
}

private fun projectionFraction(aLat: Double, aLon: Double, bLat: Double, bLon: Double, lat: Double, lon: Double): Double {
    val scale = cos(Math.toRadians((aLat + bLat) / 2.0))
    val dx = (bLon - aLon) * scale; val dy = bLat - aLat
    val px = (lon - aLon) * scale; val py = lat - aLat
    val denominator = dx * dx + dy * dy
    return if (denominator == 0.0) 0.0 else ((px * dx + py * dy) / denominator).coerceIn(0.0, 1.0)
}

private fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val radius = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
    val sinLat = kotlin.math.sin(dLat / 2); val sinLon = kotlin.math.sin(dLon / 2)
    val h = sinLat * sinLat + kotlin.math.cos(Math.toRadians(aLat)) * kotlin.math.cos(Math.toRadians(bLat)) * sinLon * sinLon
    return 2 * radius * kotlin.math.asin(kotlin.math.sqrt(h))
}
