package com.intelligame.easteregghuntar

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gestisce: giocatori, statistiche, slot di salvataggio multipli, premium, impostazioni.
 *
 * SLOT MULTIPLI:
 *  save_slots.json = array JSON di SavedSession.
 *  Ogni session ha un id univoco (timestamp). Max 30 slot.
 *
 * POSIZIONI:
 *  Ogni uovo e' salvato come offset [dx,dy,dz] rispetto alla cassaforte.
 *  Al ripristino: riposiziona la cassaforte nello stesso punto fisico,
 *  le uova riappaiono automaticamente.
 */
class GameDataManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("easter_hunt_prefs", Context.MODE_PRIVATE)
    private val filesDir: File = context.filesDir

    // ─── Modelli ────────────────────────────────────────────────

    data class Player(val name: String)
    data class EggStat(val eggNumber: Int, val timeMs: Long)

    data class GameRun(
        val id: String, val playerName: String, val date: String,
        val eggCount: Int, val eggStats: List<EggStat>, val totalMs: Long
    ) {
        fun bestMs()  = eggStats.minOfOrNull { it.timeMs } ?: 0L
        fun worstMs() = eggStats.maxOfOrNull { it.timeMs } ?: 0L
        fun avgMs()   = if (eggStats.isEmpty()) 0L else eggStats.sumOf { it.timeMs } / eggStats.size
    }

    data class SavedSession(
        val id: String,
        val savedAt: String,
        val slotName: String = "",          // nome personalizzabile dallo slot
        val players: List<String>,
        val eggCount: Int,
        val riddles: List<String>,
        val parentNote: String,
        val eggOffsets: List<FloatArray>,   // [dx,dy,dz] per ogni uovo
        val eggColors: List<Int>,           // indice colore (0-5)
        val eggShapes: List<String> = emptyList(),   // "sphere","cube",...
        val safeType: String = "classic",   // tipo cassaforte
        val trapMask: List<Boolean> = emptyList(),   // true = trappola
        val turnMode: String = "sequential"
    )

    // ─── Giocatori ──────────────────────────────────────────────

    fun getPlayers(): List<Player> {
        val json = prefs.getString("players", null) ?: return defaultPlayers()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { Player(arr.getString(it)) }
        } catch (e: Exception) { defaultPlayers() }
    }

    private fun defaultPlayers() = listOf(Player("Melissa"), Player("Vanessa"))

    fun savePlayers(players: List<Player>) {
        val arr = JSONArray().apply { players.forEach { put(it.name) } }
        prefs.edit().putString("players", arr.toString()).apply()
    }

    fun addPlayer(name: String) {
        val list = getPlayers().toMutableList()
        if (list.none { it.name == name }) { list.add(Player(name)); savePlayers(list) }
    }

    fun removePlayer(name: String) = savePlayers(getPlayers().filter { it.name != name })

    // ─── Statistiche ────────────────────────────────────────────

    private fun statsFile() = File(filesDir, "game_stats.json")

    fun getAllRuns(): List<GameRun> {
        val file = statsFile(); if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { parseRun(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun getRunsForPlayer(playerName: String) = getAllRuns().filter { it.playerName == playerName }

    fun addRun(run: GameRun) {
        val list = getAllRuns().toMutableList().also { it.add(run) }
        val arr  = JSONArray().apply { list.forEach { put(runToJson(it)) } }
        statsFile().writeText(arr.toString())
    }

    fun clearStats() = statsFile().delete()

    private fun runToJson(r: GameRun) = JSONObject().apply {
        put("id", r.id); put("playerName", r.playerName); put("date", r.date)
        put("eggCount", r.eggCount); put("totalMs", r.totalMs)
        put("eggStats", JSONArray().apply {
            r.eggStats.forEach { e -> put(JSONObject().apply { put("n", e.eggNumber); put("ms", e.timeMs) }) }
        })
    }

    private fun parseRun(o: JSONObject): GameRun {
        val arr = o.optJSONArray("eggStats") ?: JSONArray()
        val stats = (0 until arr.length()).map {
            val e = arr.getJSONObject(it); EggStat(e.getInt("n"), e.getLong("ms"))
        }
        return GameRun(
            id = o.optString("id", ""), playerName = o.getString("playerName"),
            date = o.optString("date", ""), eggCount = o.getInt("eggCount"),
            eggStats = stats, totalMs = o.getLong("totalMs")
        )
    }

    // ─── Slot di salvataggio multipli ───────────────────────────

    private fun slotsFile() = File(filesDir, "save_slots.json")

    fun getSaveSlots(): List<SavedSession> {
        val file = slotsFile()
        if (!file.exists()) {
            // Backward compat: migrate old single save
            val old = File(filesDir, "saved_session.json")
            if (old.exists()) {
                try {
                    val s = parseSingleSession(JSONObject(old.readText()))
                    if (s != null) { upsertSaveSlot(s); old.delete() }
                } catch (_: Exception) {}
            }
        }
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { parseSingleSession(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun upsertSaveSlot(session: SavedSession) {
        val list = getSaveSlots().toMutableList()
        val idx = list.indexOfFirst { it.id == session.id }
        if (idx >= 0) list[idx] = session else list.add(session)
        // Keep max 30 slots: remove oldest if exceeded
        val trimmed = if (list.size > 30) list.sortedByDescending { it.savedAt }.take(30) else list
        val arr = JSONArray().apply { trimmed.forEach { put(sessionToJson(it)) } }
        slotsFile().writeText(arr.toString())
    }

    fun deleteSaveSlot(id: String) {
        val list = getSaveSlots().filter { it.id != id }
        val arr  = JSONArray().apply { list.forEach { put(sessionToJson(it)) } }
        slotsFile().writeText(arr.toString())
    }

    fun loadSaveSlot(id: String) = getSaveSlots().firstOrNull { it.id == id }

    // Compat
    fun hasSavedSession() = getSaveSlots().isNotEmpty()
    fun loadSession()     = getSaveSlots().maxByOrNull { it.savedAt }
    fun saveSession(s: SavedSession) = upsertSaveSlot(s)
    fun clearSavedSession() = slotsFile().delete()

    private fun sessionToJson(s: SavedSession) = JSONObject().apply {
        put("id", s.id); put("savedAt", s.savedAt); put("slotName", s.slotName)
        put("players",  JSONArray().apply { s.players.forEach { put(it) } })
        put("eggCount", s.eggCount)
        put("riddles",  JSONArray().apply { s.riddles.forEach { put(it) } })
        put("parentNote", s.parentNote)
        put("safeType", s.safeType)
        put("turnMode", s.turnMode)
        put("eggOffsets", JSONArray().apply {
            s.eggOffsets.forEach { off ->
                put(JSONArray().apply { off.forEach { v -> put(v.toDouble()) } })
            }
        })
        put("eggColors", JSONArray().apply { s.eggColors.forEach { put(it) } })
        put("eggShapes", JSONArray().apply { s.eggShapes.forEach { put(it) } })
        put("trapMask",  JSONArray().apply { s.trapMask.forEach { put(it) } })
    }

    private fun parseSingleSession(o: JSONObject): SavedSession? {
        return try {
            val playersArr = o.getJSONArray("players")
            val riddlesArr = o.getJSONArray("riddles")
            val offsetsArr = o.optJSONArray("eggOffsets") ?: JSONArray()
            val colorsArr  = o.optJSONArray("eggColors")  ?: JSONArray()
            val shapesArr  = o.optJSONArray("eggShapes")  ?: JSONArray()
            val trapArr    = o.optJSONArray("trapMask")   ?: JSONArray()

            val offsets = (0 until offsetsArr.length()).map { i ->
                val a = offsetsArr.getJSONArray(i)
                floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
            }
            val colors = (0 until colorsArr.length()).map { colorsArr.getInt(it) }
            val shapes = (0 until shapesArr.length()).map { shapesArr.getString(it) }
            val traps  = (0 until trapArr.length()).map { trapArr.getBoolean(it) }

            SavedSession(
                id = o.optString("id", System.currentTimeMillis().toString()),
                savedAt = o.optString("savedAt", ""),
                slotName = o.optString("slotName", ""),
                players = (0 until playersArr.length()).map { playersArr.getString(it) },
                eggCount = o.getInt("eggCount"),
                riddles  = (0 until riddlesArr.length()).map { riddlesArr.getString(it) },
                parentNote = o.optString("parentNote", ""),
                eggOffsets = offsets, eggColors = colors,
                eggShapes  = shapes,  trapMask  = traps,
                safeType   = o.optString("safeType", "classic"),
                turnMode   = o.optString("turnMode", "sequential")
            )
        } catch (e: Exception) { null }
    }

    // ─── Impostazioni ────────────────────────────────────────────

    fun getCatchDistMeters():  Float   = prefs.getFloat("catch_dist",  0.9f)
    fun setCatchDistMeters(v: Float)   { prefs.edit().putFloat("catch_dist",  v).apply() }
    fun getRevealDistMeters(): Float   = prefs.getFloat("reveal_dist", 2.5f)
    fun setRevealDistMeters(v: Float)  { prefs.edit().putFloat("reveal_dist", v).apply() }
    fun getSoundEnabled():     Boolean = prefs.getBoolean("sound", true)
    fun setSoundEnabled(v: Boolean)    { prefs.edit().putBoolean("sound", v).apply(); SoundManager.enabled = v }
    fun getVibrationEnabled(): Boolean = prefs.getBoolean("vibration", true)
    fun setVibrationEnabled(v: Boolean){ prefs.edit().putBoolean("vibration", v).apply() }
    fun getAdPersonalized():   Boolean = prefs.getBoolean("ad_personalized", true)
    fun setAdPersonalized(v: Boolean)  { prefs.edit().putBoolean("ad_personalized", v).apply() }

    // ─── Premium ─────────────────────────────────────────────────

    fun isPremiumEggs():    Boolean = prefs.getBoolean("premium_eggs",   false)
    fun unlockPremiumEggs()         { prefs.edit().putBoolean("premium_eggs",   true).apply() }
    fun isPremiumColors():  Boolean = prefs.getBoolean("premium_colors", false)
    fun unlockPremiumColors()       { prefs.edit().putBoolean("premium_colors", true).apply() }

    fun getUnlockedShapes(): Set<String> =
        prefs.getStringSet("unlocked_shapes", setOf("sphere","cube","cylinder"))
            ?: setOf("sphere","cube","cylinder")

    fun unlockShape(shape: String) {
        val set = getUnlockedShapes().toMutableSet().also { it.add(shape) }
        prefs.edit().putStringSet("unlocked_shapes", set).apply()
    }

    fun getUnlockedSafes(): Set<String> =
        prefs.getStringSet("unlocked_safes", setOf("classic")) ?: setOf("classic")

    fun unlockSafe(safeType: String) {
        val set = getUnlockedSafes().toMutableSet().also { it.add(safeType) }
        prefs.edit().putStringSet("unlocked_safes", set).apply()
    }

    fun isMultiplayerUnlocked(): Boolean = prefs.getBoolean("multiplayer_unlocked", false)
    fun unlockMultiplayer()               { prefs.edit().putBoolean("multiplayer_unlocked", true).apply() }

    // ─── Modalità AR ─────────────────────────────────────────────
    // "standard"   → solo rilevamento piano (comportamento attuale)
    // "depth"      → Depth API: qualsiasi superficie
    // "room_scan"  → scansione completa + Cloud Anchors persistenti
    fun getArMode(): String  = prefs.getString("ar_mode", "depth") ?: "depth"
    fun setArMode(v: String) { prefs.edit().putString("ar_mode", v).apply() }

    // ─── LocalAnchorStore — TTL configurabile ─────────────────────
    // 0 = infinito, altrimenti numero di giorni prima della scadenza
    // Gratuito e locale — nessuna API key richiesta
    fun getLocalAnchorTtlDays(): Int  = prefs.getInt("local_anchor_ttl", 30)
    fun setLocalAnchorTtlDays(v: Int) { prefs.edit().putInt("local_anchor_ttl", v).apply() }

    // ─── Utility ─────────────────────────────────────────────────

    fun newRunId()     = System.currentTimeMillis().toString()
    fun todayString(): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date())

    // ─── Singleton ───────────────────────────────────────────────

    companion object {
        @Volatile private var instance: GameDataManager? = null
        fun get(context: Context): GameDataManager =
            instance ?: synchronized(this) {
                instance ?: GameDataManager(context.applicationContext).also { instance = it }
            }
    }
}
