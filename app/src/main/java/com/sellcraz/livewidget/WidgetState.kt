package com.sellcraz.livewidget

import org.json.JSONObject
import java.time.OffsetDateTime

/** Mirror of the jsonb returned by show_widget_state / seller_advance_lot. */
data class WidgetLot(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val status: String,
    val currentBid: Long,
    val startingBid: Long,
    val endsAtMs: Long?,
    val leaderId: String?,
    val winnerId: String?,
)

data class WidgetState(
    val showId: String,
    val title: String,
    val showStatus: String,
    val sellerId: String,
    val venue: String,
    val instagramHandle: String?,
    val lot: WidgetLot?,
    val nextBid: Long?,
    val queuedCount: Int,
    val nextLotName: String?,
    /** server clock minus phone clock, in ms */
    val clockOffsetMs: Long,
) {
    val isInstagram: Boolean get() = venue == "instagram"

    companion object {
        private fun str(o: JSONObject, k: String): String? =
            if (!o.has(k) || o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }

        private fun num(o: JSONObject, k: String): Long? =
            if (!o.has(k) || o.isNull(k)) null else o.optLong(k)

        fun time(s: String?): Long? = try {
            s?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        } catch (e: Exception) {
            null
        }

        fun parse(o: JSONObject, localTimeMs: Long): WidgetState {
            val show = o.getJSONObject("show")
            val lotJson = if (o.isNull("lot")) null else o.optJSONObject("lot")
            val serverNow = time(str(o, "server_now"))
            val next = if (o.isNull("next_lot")) null else o.optJSONObject("next_lot")
            return WidgetState(
                showId = show.getString("id"),
                title = str(show, "title") ?: "Show",
                showStatus = str(show, "status") ?: "",
                sellerId = str(show, "seller_id") ?: "",
                venue = str(show, "bid_venue") ?: "sellcraz",
                instagramHandle = str(show, "instagram_handle"),
                lot = lotJson?.let {
                    WidgetLot(
                        id = it.getString("id"),
                        name = str(it, "name") ?: "Lot",
                        imageUrl = str(it, "image_url")?.takeIf { u -> u.startsWith("http") },
                        status = str(it, "status") ?: "",
                        currentBid = num(it, "current_bid") ?: 0,
                        startingBid = num(it, "starting_bid") ?: 0,
                        endsAtMs = time(str(it, "ends_at")),
                        leaderId = str(it, "leader_id"),
                        winnerId = str(it, "winner_id"),
                    )
                },
                nextBid = num(o, "next_bid"),
                queuedCount = num(o, "queued_count")?.toInt() ?: 0,
                nextLotName = next?.let { str(it, "name") },
                clockOffsetMs = if (serverNow != null) serverNow - localTimeMs else 0L,
            )
        }
    }
}
