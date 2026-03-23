package com.intelligame.easteregghuntar

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * LocalAnchorStore — persistenza anchor completamente locale e gratuita.
 *
 * Sostituisce ARCore Cloud Anchors (che richiede Google Cloud API).
 *
 * Come funziona:
 *  - Ogni anchor è salvato come OFFSET [dx, dy, dz] e ORIENTAZIONE [qx, qy, qz, qw]
 *    rispetto al punto di riferimento (la cassaforte).
 *  - Al riavvio dell'app, l'utente piazza la cassaforte NELLO STESSO PUNTO fisico.
 *  - Il sistema ricrea tutti gli anchor con le stesse posizioni relative.
 *
 * Vantaggi vs Cloud Anchors:
 *  ✅ Zero costo, zero API key, zero account Google
 *  ✅ Nessun limite di anchor (solo storage locale del telefono)
 *  ✅ TTL configurabile: giorni, settimane, mesi, anni
 *  ✅ Funziona offline
 *  ✅ Nessun round-trip server (istantaneo al salvataggio)
 *
 * Limitazione vs Cloud Anchors:
 *  ⚠️ Non condivide gli anchor tra dispositivi diversi (OK per uso singolo)
 *  ⚠️ Richiede che la cassaforte venga riposizionata nello stesso punto fisico
 *     (in genere a <2cm di errore, impercettibile in gioco)
 *
 * Struttura file JSON salvato in filesDir/local_anchors/{sessionId}.json:
 * {
 *   "sessionId": "...",
 *   "sessionName": "Stanza soggiorno",
 *   "createdAt": 1234567890,
 *   "expiresAt": 1234567890,
 *   "ttlDays": 30,
 *   "refDescription": "Cassaforte sul tavolo del soggiorno",
 *   "refLat": 0.0,  // opzionale, GPS se disponibile
 *   "refLng": 0.0,
 *   "anchors": [
 *     {
 *       "id": 0,
 *       "dx": 0.5, "dy": 0.0, "dz": -1.2,
 *       "qx": 0.0, "qy": 0.0, "qz": 0.0, "qw": 1.0,
 *       "colorIdx": 2,
 *       "shape": "sphere",
 *       "isTrap": false,
 *       "hintText": "",
 *       "label": "Uovo #1"
 *     }, ...
 *   ]
 * }
 */
class LocalAnchorStore(private val context: Context) {

    companion object {
        private const val TAG         = "LocalAnchorStore"
        private const val DIR_NAME    = "local_anchors"
        private const val MAX_ANCHORS = 500     // limite pratico per UI
        const val DEFAULT_TTL_DAYS    = 30

        @Volatile private var instance: LocalAnchorStore? = null
        fun get(context: Context): LocalAnchorStore =
            instance ?: synchronized(this) {
                instance ?: LocalAnchorStore(context.applicationContext).also { instance = it }
            }
    }

    // ─── Modelli ────────────────────────────────────────────────

    /**
     * Singolo anchor salvato localmente.
     *
     * @param id        indice progressivo (0-based)
     * @param dx dy dz  offset in metri rispetto al punto di riferimento (pose.translation)
     * @param qx qy qz qw quaternione orientazione (pose.rotationQuaternion)
     * @param colorIdx  indice colore (0-5)
     * @param shape     "sphere" | "cube" | "cylinder" | "diamond"
     * @param isTrap    true = trappola
     * @param hintText  indovinello opzionale
     * @param label     etichetta visualizzata
     */
    data class LocalAnchor(
        val id:        Int     = 0,
        val dx:        Float   = 0f,
        val dy:        Float   = 0f,
        val dz:        Float   = 0f,
        val qx:        Float   = 0f,
        val qy:        Float   = 0f,
        val qz:        Float   = 0f,
        val qw:        Float   = 1f,
        val colorIdx:  Int     = 0,
        val shape:     String  = "sphere",
        val isTrap:    Boolean = false,
        val hintText:  String  = "",
        val label:     String  = ""
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id)
            put("dx", dx.toDouble()); put("dy", dy.toDouble()); put("dz", dz.toDouble())
            put("qx", qx.toDouble()); put("qy", qy.toDouble()); put("qz", qz.toDouble()); put("qw", qw.toDouble())
            put("colorIdx", colorIdx); put("shape", shape)
            put("isTrap", isTrap); put("hintText", hintText); put("label", label)
        }
        companion object {
            fun fromJson(j: JSONObject) = LocalAnchor(
                id       = j.optInt("id", 0),
                dx       = j.optDouble("dx", 0.0).toFloat(),
                dy       = j.optDouble("dy", 0.0).toFloat(),
                dz       = j.optDouble("dz", 0.0).toFloat(),
                qx       = j.optDouble("qx", 0.0).toFloat(),
                qy       = j.optDouble("qy", 0.0).toFloat(),
                qz       = j.optDouble("qz", 0.0).toFloat(),
                qw       = j.optDouble("qw", 1.0).toFloat(),
                colorIdx = j.optInt("colorIdx", 0),
                shape    = j.optString("shape", "sphere"),
                isTrap   = j.optBoolean("isTrap", false),
                hintText = j.optString("hintText", ""),
                label    = j.optString("label", "")
            )
        }
    }

    /**
     * Sessione di anchor: un insieme di uova nascoste in una stanza.
     *
     * @param sessionId      UUID unico
     * @param sessionName    nome leggibile (es. "Caccia pasquale soggiorno")
     * @param createdAt      timestamp Unix ms creazione
     * @param expiresAt      timestamp Unix ms scadenza (0 = mai)
     * @param ttlDays        giorni di vita (0 = infinito)
     * @param refDescription suggerimento per riposizionare il riferimento
     * @param refLat refLng  GPS opzionale del punto di riferimento
     * @param anchors        lista anchor
     */
    data class AnchorSession(
        val sessionId:      String             = UUID.randomUUID().toString(),
        val sessionName:    String             = "",
        val createdAt:      Long               = System.currentTimeMillis(),
        val expiresAt:      Long               = 0L,
        val ttlDays:        Int                = DEFAULT_TTL_DAYS,
        val refDescription: String             = "",
        val refLat:         Double             = 0.0,
        val refLng:         Double             = 0.0,
        val anchors:        List<LocalAnchor>  = emptyList()
    ) {
        val anchorCount get() = anchors.size
        val isExpired get() = expiresAt > 0 && System.currentTimeMillis() > expiresAt
        val daysRemaining get(): Int {
            if (expiresAt <= 0) return Int.MAX_VALUE
            val ms = expiresAt - System.currentTimeMillis()
            return (ms / 86_400_000L).toInt().coerceAtLeast(0)
        }
        val formattedDate: String
            get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(createdAt))

        fun toJson() = JSONObject().apply {
            put("sessionId",      sessionId)
            put("sessionName",    sessionName)
            put("createdAt",      createdAt)
            put("expiresAt",      expiresAt)
            put("ttlDays",        ttlDays)
            put("refDescription", refDescription)
            put("refLat",         refLat)
            put("refLng",         refLng)
            put("anchors", JSONArray().apply { anchors.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(j: JSONObject): AnchorSession {
                val arr  = j.optJSONArray("anchors") ?: JSONArray()
                val list = (0 until arr.length()).map { LocalAnchor.fromJson(arr.getJSONObject(it)) }
                return AnchorSession(
                    sessionId      = j.optString("sessionId", UUID.randomUUID().toString()),
                    sessionName    = j.optString("sessionName", ""),
                    createdAt      = j.optLong("createdAt", System.currentTimeMillis()),
                    expiresAt      = j.optLong("expiresAt", 0L),
                    ttlDays        = j.optInt("ttlDays", DEFAULT_TTL_DAYS),
                    refDescription = j.optString("refDescription", ""),
                    refLat         = j.optDouble("refLat", 0.0),
                    refLng         = j.optDouble("refLng", 0.0),
                    anchors        = list
                )
            }
        }
    }

    // ─── Storage directory ──────────────────────────────────────

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).also { if (!it.exists()) it.mkdirs() }

    private fun fileFor(sessionId: String) = File(dir, "$sessionId.json")

    // ─── CRUD ───────────────────────────────────────────────────

    /**
     * Salva o aggiorna una sessione anchor.
     * @return true se il salvataggio è riuscito
     */
    fun save(session: AnchorSession): Boolean {
        return try {
            fileFor(session.sessionId).writeText(session.toJson().toString(2))
            Log.d(TAG, "Salvati ${session.anchorCount} anchor → ${session.sessionId}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Errore salvataggio: ${e.message}")
            false
        }
    }

    /**
     * Carica una sessione per ID.
     * @return AnchorSession o null se non esiste o corrotta
     */
    fun load(sessionId: String): AnchorSession? {
        val file = fileFor(sessionId)
        if (!file.exists()) return null
        return try {
            AnchorSession.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            Log.e(TAG, "Errore caricamento $sessionId: ${e.message}")
            null
        }
    }

    /**
     * Elimina una sessione.
     */
    fun delete(sessionId: String): Boolean = fileFor(sessionId).delete()

    /**
     * Lista tutte le sessioni valide (non scadute), ordinate dalla più recente.
     */
    fun listSessions(): List<AnchorSession> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try { AnchorSession.fromJson(JSONObject(file.readText())) } catch (_: Exception) { null }
        }.filter { !it.isExpired }.sortedByDescending { it.createdAt }
    }

    /**
     * Elimina tutte le sessioni scadute. Chiamare all'avvio app.
     * @return numero di sessioni eliminate
     */
    fun purgeExpired(): Int {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return 0
        var count = 0
        files.forEach { file ->
            try {
                val s = AnchorSession.fromJson(JSONObject(file.readText()))
                if (s.isExpired) { file.delete(); count++ }
            } catch (_: Exception) { file.delete(); count++ }
        }
        if (count > 0) Log.d(TAG, "Eliminate $count sessioni scadute")
        return count
    }

    /**
     * Dimensione totale usata su disco (in bytes).
     */
    fun totalSizeBytes(): Long =
        dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun totalSizeKb(): Long = totalSizeBytes() / 1024L

    /**
     * Crea una nuova AnchorSession con TTL calcolato.
     */
    fun createSession(
        name:           String,
        ttlDays:        Int = DEFAULT_TTL_DAYS,
        refDescription: String = "",
        refLat:         Double = 0.0,
        refLng:         Double = 0.0,
        anchors:        List<LocalAnchor> = emptyList()
    ): AnchorSession {
        val now      = System.currentTimeMillis()
        val expires  = if (ttlDays <= 0) 0L else now + ttlDays * 86_400_000L
        return AnchorSession(
            sessionName    = name,
            createdAt      = now,
            expiresAt      = expires,
            ttlDays        = ttlDays,
            refDescription = refDescription,
            refLat         = refLat,
            refLng         = refLng,
            anchors        = anchors
        )
    }

    /**
     * Costruisce un LocalAnchor dai dati di posa ARCore.
     *
     * @param id        indice uovo
     * @param refTrans  translation[3] della cassaforte (punto di riferimento)
     * @param refRot    rotationQuaternion[4] della cassaforte
     * @param eggTrans  translation[3] dell'uovo
     * @param eggRot    rotationQuaternion[4] dell'uovo
     */
    fun buildAnchor(
        id:       Int,
        refTrans: FloatArray,
        refRot:   FloatArray,
        eggTrans: FloatArray,
        eggRot:   FloatArray,
        colorIdx: Int     = 0,
        shape:    String  = "sphere",
        isTrap:   Boolean = false,
        hintText: String  = "",
        label:    String  = ""
    ): LocalAnchor {
        // Calcola offset in coordinate mondo (semplice differenza — sufficiente per le distanze in gioco)
        val dx = eggTrans[0] - refTrans[0]
        val dy = eggTrans[1] - refTrans[1]
        val dz = eggTrans[2] - refTrans[2]
        return LocalAnchor(
            id = id, dx = dx, dy = dy, dz = dz,
            qx = eggRot[0], qy = eggRot[1], qz = eggRot[2], qw = eggRot[3],
            colorIdx = colorIdx, shape = shape, isTrap = isTrap,
            hintText = hintText, label = label
        )
    }

    /**
     * Ricostruisce la posizione mondo di un anchor dato il punto di riferimento corrente.
     *
     * @param anchor    anchor locale caricato
     * @param refTrans  translation[3] della cassaforte nella sessione corrente
     * @return FloatArray[3] con la posizione mondo dell'uovo
     */
    fun worldPosition(anchor: LocalAnchor, refTrans: FloatArray): FloatArray =
        floatArrayOf(
            refTrans[0] + anchor.dx,
            refTrans[1] + anchor.dy,
            refTrans[2] + anchor.dz
        )
}
