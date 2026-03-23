package com.intelligame.easteregghuntar

/**
 * Un uovo nascosto nel mondo reale, identificato da coordinate GPS.
 *
 * @param id        indice progressivo (0-based)
 * @param lat       latitudine WGS84
 * @param lng       longitudine WGS84
 * @param label     emoji/label (es. "🥚 #1")
 * @param colorIdx  indice colore (stesso sistema indoor)
 * @param isTrap    true = trappola (penalità se raccolta)
 * @param caught    true = già trovata dal bambino
 * @param caughtBy  nome giocatore che l'ha trovata
 * @param hintText  indovinello/suggerimento opzionale
 * @param cloudId   Firebase key per multiplayer
 */
data class OutdoorEgg(
    val id:        Int     = 0,
    val lat:       Double  = 0.0,
    val lng:       Double  = 0.0,
    val label:     String  = "🥚",
    val colorIdx:  Int     = 0,
    val isTrap:    Boolean = false,
    var caught:    Boolean = false,
    var caughtBy:  String  = "",
    val hintText:  String  = "",
    val cloudId:   String  = ""
) {
    /** Distanza in metri da coordinate correnti (approssimata, sufficientemente precisa per <500m) */
    fun distanceTo(userLat: Double, userLng: Double): Double {
        val dLat = Math.toRadians(lat - userLat)
        val dLng = Math.toRadians(lng - userLng)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(userLat)) * Math.cos(Math.toRadians(lat)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Bearing in gradi (0 = Nord, 90 = Est) da posizione utente verso l'uovo */
    fun bearingFrom(userLat: Double, userLng: Double): Float {
        val dLng = Math.toRadians(lng - userLng)
        val y = Math.sin(dLng) * Math.cos(Math.toRadians(lat))
        val x = Math.cos(Math.toRadians(userLat)) * Math.sin(Math.toRadians(lat)) -
                Math.sin(Math.toRadians(userLat)) * Math.cos(Math.toRadians(lat)) * Math.cos(dLng)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
    }

    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("id",       id)
        put("lat",      lat)
        put("lng",      lng)
        put("label",    label)
        put("colorIdx", colorIdx)
        put("isTrap",   isTrap)
        put("caught",   caught)
        put("caughtBy", caughtBy)
        put("hintText", hintText)
        put("cloudId",  cloudId)
    }

    companion object {
        fun fromJson(j: org.json.JSONObject) = OutdoorEgg(
            id       = j.optInt("id", 0),
            lat      = j.optDouble("lat", 0.0),
            lng      = j.optDouble("lng", 0.0),
            label    = j.optString("label", "🥚"),
            colorIdx = j.optInt("colorIdx", 0),
            isTrap   = j.optBoolean("isTrap", false),
            caught   = j.optBoolean("caught", false),
            caughtBy = j.optString("caughtBy", ""),
            hintText = j.optString("hintText", ""),
            cloudId  = j.optString("cloudId", "")
        )
    }
}

/**
 * Sessione di caccia outdoor completa.
 */
data class OutdoorSession(
    val id:          String           = "",
    val createdAt:   String           = "",
    val players:     List<String>     = emptyList(),
    val eggs:        List<OutdoorEgg> = emptyList(),
    val penaltySecs: Int              = 30,
    val isMultiplayer: Boolean        = false,
    val roomCode:    String           = ""
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("id",           id)
        put("createdAt",    createdAt)
        put("penaltySecs",  penaltySecs)
        put("isMultiplayer",isMultiplayer)
        put("roomCode",     roomCode)
        val pa = org.json.JSONArray(); players.forEach { pa.put(it) }; put("players", pa)
        val ea = org.json.JSONArray(); eggs.forEach { ea.put(it.toJson()) }; put("eggs", ea)
    }

    companion object {
        fun fromJson(j: org.json.JSONObject): OutdoorSession {
            val players = mutableListOf<String>()
            val pa = j.optJSONArray("players"); if (pa != null) for (i in 0 until pa.length()) players.add(pa.getString(i))
            val eggs = mutableListOf<OutdoorEgg>()
            val ea = j.optJSONArray("eggs"); if (ea != null) for (i in 0 until ea.length()) eggs.add(OutdoorEgg.fromJson(ea.getJSONObject(i)))
            return OutdoorSession(
                id          = j.optString("id", ""),
                createdAt   = j.optString("createdAt", ""),
                penaltySecs = j.optInt("penaltySecs", 30),
                isMultiplayer = j.optBoolean("isMultiplayer", false),
                roomCode    = j.optString("roomCode", ""),
                players     = players,
                eggs        = eggs
            )
        }
    }
}
