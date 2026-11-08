package com.mujimujimuji.odo.data

import com.mujimujimuji.odo.engine.AchievementDef
import com.mujimujimuji.odo.engine.AchievementRule
import com.mujimujimuji.odo.engine.Geofence
import com.mujimujimuji.odo.engine.LatLng
import org.json.JSONArray
import org.json.JSONObject

/** assets/achievements.json 파서. 좌표 출처는 JSON의 source 필드에 있다. */
object AchievementCatalog {
    fun parse(json: String): List<AchievementDef> {
        val items = JSONObject(json).getJSONArray("achievements")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            AchievementDef(
                id = o.getString("id"),
                title = o.getString("title"),
                description = o.getString("description"),
                rule = parseRule(o.getJSONObject("rule")),
            )
        }
    }

    private fun parseRule(r: JSONObject): AchievementRule = when (val type = r.getString("type")) {
        "place" -> AchievementRule.Place(fences(r.getJSONArray("fences")))
        "road" -> AchievementRule.Road(fences(r.getJSONArray("gates")))
        "safeDistance" -> AchievementRule.SafeDistance(r.getInt("km"))
        "nightSafeDistance" -> AchievementRule.NightSafeDistance(r.getInt("km"))
        "rainSafeDistance" -> AchievementRule.RainSafeDistance(r.getInt("km"))
        "cleanTrip" -> AchievementRule.CleanTrip(r.getInt("km"))
        "tripCount" -> AchievementRule.TripCount(r.getInt("count"))
        "streakDays" -> AchievementRule.StreakDays(r.getInt("days"))
        "level" -> AchievementRule.Level(r.getInt("level"))
        else -> error("알 수 없는 업적 규칙: $type")
    }

    private fun fences(a: JSONArray): List<Geofence> = (0 until a.length()).map { i ->
        val f = a.getJSONObject(i)
        Geofence(LatLng(f.getDouble("lat"), f.getDouble("lng")), f.getDouble("radiusM"))
    }
}
