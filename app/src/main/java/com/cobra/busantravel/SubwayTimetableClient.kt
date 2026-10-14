package com.cobra.busantravel

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

data class TravelSubwayTrip(
    val departureEpochMillis: Long,
    val arrivalsEpochMillis: List<Long>,
)

/**
 * The same full-CSV timetable rule used by dev-1-new.  Tapping boarding pins
 * one train (the nearest scheduled train at that moment), then only that
 * train's station times are used for the displayed current location.
 */
class SubwayTimetableClient(private val context: Context) {
    fun selectNearestTrip(info: TravelSubwayInfo, referenceEpochMillis: Long): TravelSubwayTrip {
        val reference = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(referenceEpochMillis), ZoneId.systemDefault()
        )
        val (header, rows) = readTable(info.timetableAsset)
        val serviceRows = rows.filter { it["요일별"].orEmpty().startsWith(dayLabel(reference.toLocalDate())) }
        if (serviceRows.isEmpty()) throw IllegalStateException("오늘(${dayLabel(reference.toLocalDate())})의 지하철 시간표가 없습니다.")

        val origin = normalize(info.stops.first())
        val originRows = serviceRows.filter { normalize(it["역명"].orEmpty()) == origin }
        if (originRows.isEmpty()) throw IllegalStateException("시간표에서 ${info.stops.first()}을 찾지 못했습니다.")
        val trainColumns = header.drop(3).filter { it.isNotBlank() }
        val selectedColumn = trainColumns.minByOrNull { column ->
            originRows.mapNotNull { row -> serviceDateTime(row[column], reference) }
                .minOfOrNull { abs(Duration.between(reference, it).toMillis()) } ?: Long.MAX_VALUE
        } ?: throw IllegalStateException("${info.stops.first()} 출발 열차를 찾지 못했습니다.")

        var previous: LocalDateTime? = null
        val arrivals = info.stops.map { stop ->
            val stationRows = serviceRows.filter { normalize(it["역명"].orEmpty()) == normalize(stop) }
            var event = stationRows.mapNotNull { row -> serviceDateTime(row[selectedColumn], reference) }
                .minOrNull() ?: throw IllegalStateException("선택 열차의 ${stop} 시각이 없습니다.")
            while (previous != null && event.isBefore(previous!!.minusHours(2))) event = event.plusDays(1)
            previous = event
            event.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return TravelSubwayTrip(arrivals.first(), arrivals)
    }

    private fun readTable(asset: String): Pair<List<String>, List<Map<String, String>>> {
        val lines = context.assets.open(asset).use { stream ->
            BufferedReader(InputStreamReader(stream, Charset.forName("MS949"))).readLines().filter { it.isNotBlank() }
        }
        if (lines.isEmpty()) throw IllegalStateException("지하철 시간표 파일이 비어 있습니다.")
        val header = parseCsv(lines.first()).mapIndexed { index, item -> if (index == 0) item.removePrefix("\uFEFF").trim() else item.trim() }
        return header to lines.drop(1).mapNotNull { line ->
            val cells = parseCsv(line)
            if (cells.size < 3) null else header.indices.associate { index -> header[index] to cells.getOrElse(index) { "" }.trim() }
        }
    }

    private fun serviceDateTime(raw: String?, reference: LocalDateTime): LocalDateTime? {
        val match = Regex("^\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*$").matchEntire(raw.orEmpty()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val second = match.groupValues[3].toIntOrNull() ?: 0
        var value = runCatching { reference.toLocalDate().atTime(hour, minute, second) }.getOrNull() ?: return null
        if (reference.hour >= 18 && hour < 4) value = value.plusDays(1)
        else if (reference.hour < 4 && hour >= 18) value = value.minusDays(1)
        return value
    }

    private fun dayLabel(date: LocalDate) = when (date.dayOfWeek.value) {
        6 -> "토요일"; 7 -> "휴일"; else -> "평일"
    }

    private fun normalize(value: String) = value.replace("역", "").trim()

    private fun parseCsv(line: String): List<String> {
        val cells = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var index = 0
        while (index < line.length) {
            when (val char = line[index]) {
                '"' -> if (quoted && index + 1 < line.length && line[index + 1] == '"') { cell.append(char); index++ } else quoted = !quoted
                ',' -> if (quoted) cell.append(char) else { cells += cell.toString(); cell.clear() }
                else -> cell.append(char)
            }
            index++
        }
        cells += cell.toString()
        return cells
    }
}
