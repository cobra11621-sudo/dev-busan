package com.cobra.busantravel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TagoCity(val code: String, val name: String)
data class TagoRoute(val id: String, val number: String, val startName: String = "", val endName: String = "")
data class TagoStop(
    val id: String,
    val name: String,
    val order: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
)
data class TagoArrival(val seconds: Long, val stopsToBoarding: Int?)

/** Device-only storage. The public-data key is never written to the travel ZIP or source. */
class TagoApiKeyVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("travel_protected_credentials", Context.MODE_PRIVATE)

    fun save(value: String) {
        val key = value.trim()
        if (key.isBlank()) { clear(); return }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        preferences.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("value", Base64.encodeToString(cipher.doFinal(key.toByteArray()), Base64.NO_WRAP))
            .apply()
    }

    fun get(): String? {
        val iv = preferences.getString("iv", null) ?: return null
        val encrypted = preferences.getString("value", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isConfigured(): Boolean = !get().isNullOrBlank()
    fun clear() = preferences.edit().clear().apply()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("busan.travel.tago.key.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("busan.travel.tago.key.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object { const val TRANSFORMATION = "AES/GCM/NoPadding" }
}

class TagoBusClient(private val serviceKey: String) {
    fun cities(): List<TagoCity> = items(CITY_URL, emptyMap()).mapNotNull {
        val code = it["CITYCODE"].orEmpty()
        val name = it["CITYNAME"].orEmpty()
        if (code.isBlank() || name.isBlank()) null else TagoCity(code, name)
    }.distinctBy { it.code }.sortedBy { it.name }

    fun routes(cityCode: String, routeNo: String): List<TagoRoute> =
        // TAGO's route-number list endpoint returns the city list; filter the
        // requested number locally. getCityBusRouteList is not a TAGO endpoint.
        items(ROUTE_URL, mapOf("cityCode" to cityCode, "numOfRows" to "1000")).mapNotNull {
            val id = it["ROUTEID"].orEmpty()
            val number = it["ROUTENO"].orEmpty()
            if (id.isBlank() || number.isBlank()) null else TagoRoute(id, number, it["STARTNODENM"].orEmpty(), it["ENDNODENM"].orEmpty())
        }.filter { normalizeRoute(it.number) == normalizeRoute(routeNo) }.distinctBy { it.id }

    /** Bus-station API only: find a station name without requiring BusRouteInfo approval. */
    fun stations(cityCode: String, stationName: String): List<TagoStop> =
        items(STATION_URL, mapOf("cityCode" to cityCode, "nodeNm" to stationName, "numOfRows" to "50")).mapNotNull {
            val id = it["NODEID"].orEmpty()
            val name = it["NODENM"].orEmpty()
            if (id.isBlank() || name.isBlank()) null else TagoStop(
                id = id,
                name = name,
                order = it["NODEORD"]?.toDoubleOrNull()?.toInt() ?: Int.MAX_VALUE,
                latitude = coordinate(it, "GPSLATI", "GPSLAT", "LAT"),
                longitude = coordinate(it, "GPSLONG", "GPSLON", "LON", "LNG"),
            )
        }.distinctBy { it.id }

    fun routeStops(cityCode: String, routeId: String): List<TagoStop> =
        items(STOPS_URL, mapOf("cityCode" to cityCode, "routeId" to routeId, "numOfRows" to "500")).mapNotNull {
            val id = it["NODEID"].orEmpty()
            val name = it["NODENM"].orEmpty()
            if (id.isBlank() || name.isBlank()) null else TagoStop(
                id = id,
                name = name,
                order = it["NODEORD"]?.toDoubleOrNull()?.toInt() ?: Int.MAX_VALUE,
                latitude = coordinate(it, "GPSLATI", "GPSLAT", "LAT"),
                longitude = coordinate(it, "GPSLONG", "GPSLON", "LON", "LNG"),
            )
        }.distinctBy { it.id }.sortedBy { it.order }

    fun arrivals(cityCode: String, nodeId: String, routeNo: String): List<TagoArrival> =
        items(ARRIVAL_URL, mapOf("cityCode" to cityCode, "nodeId" to nodeId)).filter {
            normalizeRoute(it["ROUTENO"].orEmpty()) == normalizeRoute(routeNo)
        }.mapNotNull {
            val seconds = it["ARRTIME"]?.toLongOrNull() ?: return@mapNotNull null
            TagoArrival(seconds, it["ARRPREVSTATIONCNT"]?.toIntOrNull())
        }.sortedBy { it.seconds }

    private fun items(endpoint: String, extra: Map<String, String>): List<Map<String, String>> {
        val params = linkedMapOf("serviceKey" to decodedKey(), "pageNo" to "1", "numOfRows" to "100", "_type" to "xml")
        params.putAll(extra)
        val connection = (URL(endpoint + "?" + params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }).openConnection() as HttpURLConnection)
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val xml = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (status !in 200..299) throw IllegalStateException("TAGO HTTP 오류 ($status)")
            parse(xml)
        } finally { connection.disconnect() }
    }

    private fun parse(xml: String): List<Map<String, String>> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(xml.reader()) }
        val rows = mutableListOf<Map<String, String>>()
        var row: MutableMap<String, String>? = null
        var tag = ""
        var resultCode: String? = null
        var resultMessage: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> { tag = parser.name.uppercase(); if (tag == "ITEM") row = linkedMapOf() }
                XmlPullParser.TEXT -> parser.text.trim().takeIf { it.isNotBlank() }?.let { text ->
                    row?.let { it[tag] = it.getOrDefault(tag, "") + text }
                    if (tag == "RESULTCODE") resultCode = text
                    if (tag == "RESULTMSG") resultMessage = text
                }
                XmlPullParser.END_TAG -> { if (parser.name.equals("item", true)) row?.let(rows::add); if (parser.name.equals("item", true)) row = null; tag = "" }
            }
            event = parser.next()
        }
        if (resultCode != null && resultCode !in setOf("0", "00")) throw IllegalStateException(resultMessage ?: "TAGO 응답 오류")
        return rows
    }

    private fun decodedKey(): String = runCatching {
        if (serviceKey.contains('%')) URLDecoder.decode(serviceKey.replace("+", "%2B"), StandardCharsets.UTF_8.name()) else serviceKey
    }.getOrDefault(serviceKey)
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    // Some cities return express routes as "급행3006" while people normally
    // enter just "3006". Treat the express prefix as the same route number.
    private fun normalizeRoute(value: String) = value.trim()
        .removeSuffix("번")
        .replace(" ", "")
        .replace("급행", "")
        .uppercase()
    private fun coordinate(row: Map<String, String>, vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { row[it]?.toDoubleOrNull() }

    private companion object {
        // These are the two services actually requested by this app: BusSttnInfo and ArvlInfo.
        const val CITY_URL = "https://apis.data.go.kr/1613000/ArvlInfoInqireService/getCtyCodeList"
        const val STATION_URL = "https://apis.data.go.kr/1613000/BusSttnInfoInqireService/getSttnNoList"
        const val ROUTE_URL = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteNoList"
        const val STOPS_URL = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList"
        const val ARRIVAL_URL = "https://apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"
    }
}
