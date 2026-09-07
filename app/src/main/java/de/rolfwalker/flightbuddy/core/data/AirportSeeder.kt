package de.rolfwalker.flightbuddy.core.data

import android.content.Context
import de.rolfwalker.flightbuddy.core.data.db.AirportDao
import de.rolfwalker.flightbuddy.core.data.db.AirportEntity
import org.json.JSONArray

object AirportSeeder {
    suspend fun seed(context: Context, dao: AirportDao) {
        if (dao.listAll().isNotEmpty()) return
        val text = context.assets.open("airports.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        val rows = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AirportEntity(
                iata = o.getString("iata"),
                icao = o.optString("icao").ifBlank { null },
                name = o.optString("name").ifBlank { null },
                city = o.optString("city").ifBlank { null },
                country = o.optString("country").ifBlank { null },
                timezone = o.optString("tz").ifBlank { null },
                lat = o.optDouble("lat").takeIf { it != 0.0 },
                lon = o.optDouble("lon").takeIf { it != 0.0 },
            )
        }
        dao.upsertAll(rows)
    }
}
