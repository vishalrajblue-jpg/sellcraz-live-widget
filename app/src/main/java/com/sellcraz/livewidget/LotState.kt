package com.sellcraz.livewidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.roundToLong

/**
 * A lot as the widget sees it. The exact column names in the live schema are
 * not known to this app, so each field is read from a list of likely names.
 * "Test load" in the app shows which ones were found.
 */
data class LotState(
    val id: String,
    val title: String,
    val currentBid: Long?,
    val startPrice: Long?,
    val increment: Long,
    val incrementFromDb: Boolean,
    val endsAtMs: Long?,
    val status: String?,
    val leaderId: String?,
    val imageUrl: String?,
) {
    /** The amount the button will bid. */
    val nextBid: Long?
        get() = currentBid?.let { it + increment } ?: startPrice

    val isClosedStatus: Boolean
        get() = status?.lowercase() in CLOSED

    fun describe(): String = buildString {
        appendLine("Title: $title")
        appendLine("Current bid: ${currentBid ?: "none found"}")
        appendLine("Start price: ${startPrice ?: "none found"}")
        appendLine("Increment: $increment" + if (incrementFromDb) "" else " (default, not in DB)")
        appendLine("Next bid would be: ${nextBid ?: "unknown, bidding disabled"}")
        appendLine("Ends at: " + (endsAtMs?.let { Instant.ofEpochMilli(it).toString() } ?: "none found"))
        appendLine("Status: ${status ?: "none found"}")
        appendLine("Leader: ${leaderId ?: "none found"}")
        append("Image: ${imageUrl ?: "none found"}")
    }

    companion object {
        private val CLOSED = setOf(
            "sold", "closed", "ended", "settled", "cancelled", "canceled",
            "completed", "unsold", "expired", "passed"
        )
        private val CURRENT = listOf(
            "current_bid", "current_price", "highest_bid", "current_amount",
            "leading_bid", "top_bid", "current_bid_amount"
        )
        private val START = listOf(
            "starting_bid", "start_price", "starting_price", "start_bid", "opening_bid"
        )
        private val INC = listOf("bid_increment", "min_increment", "increment", "min_bid_increment")
        private val ENDS = listOf(
            "ends_at", "end_time", "end_at", "closes_at", "close_at", "auction_ends_at", "ends"
        )
        private val STARTED = listOf("started_at", "live_at", "opened_at", "start_time")
        private val DURATION = listOf("duration_seconds", "duration_sec", "duration")
        private val TITLE = listOf("title", "name", "item_name")
        private val LEADER = listOf(
            "leader_id", "current_bidder_id", "highest_bidder_id", "leading_bidder_id",
            "top_bidder_id", "current_winner_id", "winner_id"
        )
        private val IMAGE = listOf(
            "image_url", "image", "photo_url", "thumbnail_url", "cover_url",
            "images", "photos", "image_urls"
        )

        fun from(o: JSONObject, defaultIncrement: Long): LotState {
            val inc = firstNumber(o, INC)?.takeIf { it > 0 }
            var ends = firstString(o, ENDS)?.let { parseTime(it) }
            if (ends == null) {
                val started = firstString(o, STARTED)?.let { parseTime(it) }
                val dur = firstNumber(o, DURATION)
                if (started != null && dur != null && dur > 0) ends = started + dur * 1000
            }
            return LotState(
                id = o.optString("id"),
                title = firstString(o, TITLE) ?: "Lot",
                currentBid = firstNumber(o, CURRENT)?.takeIf { it > 0 },
                startPrice = firstNumber(o, START)?.takeIf { it > 0 },
                increment = inc ?: defaultIncrement,
                incrementFromDb = inc != null,
                endsAtMs = ends,
                status = firstString(o, listOf("status", "state")),
                leaderId = firstString(o, LEADER),
                imageUrl = firstImage(o),
            )
        }

        private fun firstNumber(o: JSONObject, keys: List<String>): Long? {
            for (k in keys) {
                if (!o.has(k) || o.isNull(k)) continue
                when (val v = o.get(k)) {
                    is Number -> return v.toDouble().roundToLong()
                    is String -> v.toDoubleOrNull()?.let { return it.roundToLong() }
                }
            }
            return null
        }

        private fun firstString(o: JSONObject, keys: List<String>): String? {
            for (k in keys) {
                if (!o.has(k) || o.isNull(k)) continue
                val v = o.get(k)
                if (v is String && v.isNotBlank()) return v
                if (v is Number) return v.toString()
            }
            return null
        }

        private fun firstImage(o: JSONObject): String? {
            for (k in IMAGE) {
                if (!o.has(k) || o.isNull(k)) continue
                val url = when (val v = o.get(k)) {
                    is String -> v
                    is JSONArray -> if (v.length() > 0) {
                        when (val first = v.get(0)) {
                            is String -> first
                            is JSONObject -> first.optString("url")
                            else -> null
                        }
                    } else null
                    is JSONObject -> v.optString("url")
                    else -> null
                }
                if (url != null && url.startsWith("http")) return url
            }
            return null
        }

        fun parseTime(raw: String): Long? {
            var s = raw.trim().replace(' ', 'T')
            // Postgres can print "+00" as an offset; java.time wants "+00:00".
            if (Regex("[+-]\\d{2}$").containsMatchIn(s)) s += ":00"
            return try {
                OffsetDateTime.parse(s).toInstant().toEpochMilli()
            } catch (e: Exception) {
                try {
                    Instant.parse(s).toEpochMilli()
                } catch (e2: Exception) {
                    try {
                        LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
                    } catch (e3: Exception) {
                        null
                    }
                }
            }
        }
    }
}
