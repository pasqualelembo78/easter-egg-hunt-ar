package com.intelligame.easteregghuntar

import android.util.Log
import com.google.firebase.database.*
import kotlin.random.Random

/**
 * OutdoorRoomManager — gestisce il ciclo di vita completo delle stanze outdoor multiplayer.
 *
 * Struttura Firebase (outdoor_rooms/{code}):
 * {
 *   "hostName":    "Mario",
 *   "hostId":      "p_...",
 *   "roomName":    "Giardino di casa",
 *   "isPublic":    true,
 *   "status":      "waiting" | "playing" | "finished",
 *   "penaltySecs": 30,
 *   "ttlDays":     7,
 *   "createdAt":   1234567890000,
 *   "expiresAt":   1234567890000,   // createdAt + ttlDays * 86400000
 *   "eggCount":    5,
 *   "outdoor_eggs": {
 *     "0": { "id":0, "lat":41.9, "lng":12.5, "label":"Uovo #1",
 *            "colorIdx":0, "isTrap":false, "caught":false, "caughtBy":"" },
 *     ...
 *   },
 *   "players": {
 *     "p_123": { "name":"Luigi", "online":true, "joinedAt":... }
 *   },
 *   "scores": {
 *     "p_123": { "playerName":"Luigi", "eggsFound":2, "totalMs":45000, "finished":false }
 *   }
 * }
 *
 * COSTI Firebase:
 *  - Piano Spark (gratuito): 1GB storage, 10GB/mese download, 100 connessioni simultanee
 *  - 1 stanza con 10 uova ≈ 1-2KB → puoi avere ~500.000 stanze nel piano gratuito
 *  - Le stanze scadono automaticamente via expiresAt (pulizia lato client + regole sicurezza)
 */
object OutdoorRoomManager {

    private const val TAG          = "OutdoorRoomManager"
    private const val ROOMS_PATH   = "outdoor_rooms"
    const val DEFAULT_TTL_DAYS     = 7      // stanze durano 7 giorni di default
    const val MAX_TTL_DAYS         = 30     // massimo 30 giorni

    private val db get() = FirebaseDatabase.getInstance().reference

    // ─── Modello stanza (vista lista) ───────────────────────────

    data class RoomInfo(
        val code:       String  = "",
        val roomName:   String  = "",
        val hostName:   String  = "",
        val eggCount:   Int     = 0,
        val playerCount:Int     = 0,
        val status:     String  = "waiting",
        val createdAt:  Long    = 0L,
        val expiresAt:  Long    = 0L,
        val isPublic:   Boolean = true
    ) {
        val isExpired get() = expiresAt > 0 && System.currentTimeMillis() > expiresAt
        val daysRemaining get(): Int {
            if (expiresAt <= 0) return Int.MAX_VALUE
            return ((expiresAt - System.currentTimeMillis()) / 86_400_000L).toInt().coerceAtLeast(0)
        }
    }

    // ─── Crea stanza ─────────────────────────────────────────────

    /**
     * Carica le uova su Firebase e crea la stanza.
     *
     * @param hostId     ID univoco dell'host
     * @param hostName   Nome dell'host
     * @param roomName   Nome della stanza (es. "Giardino di casa")
     * @param isPublic   Se true appare nella lista stanze pubbliche
     * @param eggs       Lista uova outdoor con coordinate GPS
     * @param penaltySecs Penalità in secondi per trappola
     * @param ttlDays    Giorni di persistenza (1-30)
     * @param onSuccess  Callback con il codice stanza generato
     * @param onError    Callback con messaggio errore
     */
    fun createRoom(
        hostId:       String,
        hostName:     String,
        roomName:     String,
        isPublic:     Boolean,
        eggs:         List<OutdoorEgg>,
        penaltySecs:  Int  = 30,
        ttlDays:      Int  = DEFAULT_TTL_DAYS,
        onSuccess:    (code: String) -> Unit,
        onError:      (msg: String)  -> Unit
    ) {
        val code = generateCode()
        val now  = System.currentTimeMillis()
        val ttl  = ttlDays.coerceIn(1, MAX_TTL_DAYS)
        val expires = now + ttl * 86_400_000L

        // Costruisci mappa uova
        val eggsMap = mutableMapOf<String, Any>()
        eggs.forEach { egg ->
            eggsMap[egg.id.toString()] = mapOf(
                "id"       to egg.id,
                "lat"      to egg.lat,
                "lng"      to egg.lng,
                "label"    to egg.label,
                "colorIdx" to egg.colorIdx,
                "isTrap"   to egg.isTrap,
                "caught"   to false,
                "caughtBy" to ""
            )
        }

        val roomData = mapOf(
            "hostName"    to hostName,
            "hostId"      to hostId,
            "roomName"    to roomName,
            "isPublic"    to isPublic,
            "status"      to "playing",
            "penaltySecs" to penaltySecs,
            "ttlDays"     to ttl,
            "createdAt"   to now,
            "expiresAt"   to expires,
            "eggCount"    to eggs.size,
            "outdoor_eggs" to eggsMap,
            "players"     to mapOf(
                hostId to mapOf("name" to hostName, "online" to true, "joinedAt" to now)
            )
        )

        db.child(ROOMS_PATH).child(code).setValue(roomData)
            .addOnSuccessListener {
                Log.d(TAG, "Stanza $code creata con ${eggs.size} uova, TTL=$ttl giorni")
                onSuccess(code)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Errore creazione stanza: ${e.message}")
                onError(e.message ?: "Errore Firebase")
            }
    }

    // ─── Entra in stanza ─────────────────────────────────────────

    /**
     * Verifica che una stanza esista, sia valida e non scaduta, poi registra il giocatore.
     *
     * @param code       Codice stanza (6 caratteri)
     * @param playerId   ID univoco del giocatore
     * @param playerName Nome del giocatore
     * @param onSuccess  Callback con le uova caricate
     * @param onError    Callback con messaggio errore
     */
    fun joinRoom(
        code:       String,
        playerId:   String,
        playerName: String,
        onSuccess:  (eggs: List<OutdoorEgg>, penaltySecs: Int, roomName: String) -> Unit,
        onError:    (msg: String) -> Unit
    ) {
        db.child(ROOMS_PATH).child(code).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) {
                    onError("Stanza non trovata. Controlla il codice."); return@addOnSuccessListener
                }

                val expiresAt = snap.child("expiresAt").value as? Long ?: 0L
                if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
                    onError("Questa stanza è scaduta."); return@addOnSuccessListener
                }

                val status = snap.child("status").value as? String ?: "playing"
                if (status == "finished") {
                    onError("Questa partita è già terminata."); return@addOnSuccessListener
                }

                val roomName    = snap.child("roomName").value as? String ?: code
                val penaltySecs = (snap.child("penaltySecs").value as? Long)?.toInt() ?: 30

                // Carica uova
                val eggsSnap = snap.child("outdoor_eggs")
                val eggs = mutableListOf<OutdoorEgg>()
                eggsSnap.children.forEach { c ->
                    val id       = (c.child("id").value as? Long)?.toInt() ?: return@forEach
                    val lat      = c.child("lat").value as? Double ?: return@forEach
                    val lng      = c.child("lng").value as? Double ?: return@forEach
                    val label    = c.child("label").value as? String ?: "Uovo"
                    val colorIdx = (c.child("colorIdx").value as? Long)?.toInt() ?: 0
                    val isTrap   = c.child("isTrap").value as? Boolean ?: false
                    val caught   = c.child("caught").value as? Boolean ?: false
                    val caughtBy = c.child("caughtBy").value as? String ?: ""
                    eggs.add(OutdoorEgg(id, lat, lng, label, colorIdx, isTrap, caught, caughtBy))
                }

                if (eggs.isEmpty()) {
                    onError("La stanza non contiene uova."); return@addOnSuccessListener
                }

                // Registra giocatore
                db.child(ROOMS_PATH).child(code).child("players").child(playerId)
                    .setValue(mapOf("name" to playerName, "online" to true,
                        "joinedAt" to System.currentTimeMillis()))

                onSuccess(eggs.sortedBy { it.id }, penaltySecs, roomName)
            }
            .addOnFailureListener { e ->
                onError("Impossibile raggiungere Firebase: ${e.message}")
            }
    }

    // ─── Listener real-time ──────────────────────────────────────

    /**
     * Ascolta in tempo reale i cambiamenti alle uova della stanza.
     * Chiama onEggCaught ogni volta che un'uovo viene preso da qualunque giocatore.
     *
     * @return il listener (da salvare per rimuoverlo in onDestroy)
     */
    fun listenToEggs(
        code:        String,
        onEggUpdate: (egg: OutdoorEgg) -> Unit,
        onError:     (msg: String) -> Unit
    ): Pair<DatabaseReference, ChildEventListener> {
        val ref = db.child(ROOMS_PATH).child(code).child("outdoor_eggs")
        val listener = object : ChildEventListener {
            override fun onChildChanged(snap: DataSnapshot, prev: String?) {
                val id       = (snap.child("id").value as? Long)?.toInt() ?: return
                val lat      = snap.child("lat").value as? Double ?: return
                val lng      = snap.child("lng").value as? Double ?: return
                val label    = snap.child("label").value as? String ?: "Uovo"
                val colorIdx = (snap.child("colorIdx").value as? Long)?.toInt() ?: 0
                val isTrap   = snap.child("isTrap").value as? Boolean ?: false
                val caught   = snap.child("caught").value as? Boolean ?: false
                val caughtBy = snap.child("caughtBy").value as? String ?: ""
                onEggUpdate(OutdoorEgg(id, lat, lng, label, colorIdx, isTrap, caught, caughtBy))
            }
            override fun onChildAdded(snap: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) { onError(e.message) }
        }
        ref.addChildEventListener(listener)
        return Pair(ref, listener)
    }

    /**
     * Ascolta il leaderboard in tempo reale.
     */
    fun listenToScores(
        code:          String,
        onScoreUpdate: (scores: List<PlayerScore>) -> Unit
    ): Pair<DatabaseReference, ValueEventListener> {
        val ref = db.child(ROOMS_PATH).child(code).child("scores")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val scores = snap.children.mapNotNull { c ->
                    val name      = c.child("playerName").value as? String ?: return@mapNotNull null
                    val found     = (c.child("eggsFound").value as? Long)?.toInt() ?: 0
                    val ms        = c.child("totalMs").value as? Long ?: 0L
                    val finished  = c.child("finished").value as? Boolean ?: false
                    PlayerScore(c.key ?: "", name, found, ms, finished)
                }.sortedWith(compareByDescending<PlayerScore> { it.eggsFound }.thenBy { it.totalMs })
                onScoreUpdate(scores)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return Pair(ref, listener)
    }

    data class PlayerScore(
        val playerId:   String  = "",
        val playerName: String  = "",
        val eggsFound:  Int     = 0,
        val totalMs:    Long    = 0L,
        val finished:   Boolean = false
    )

    // ─── Aggiornamento stato ─────────────────────────────────────

    /** Segna un uovo come catturato su Firebase */
    fun markEggCaught(code: String, eggId: Int, caughtBy: String) {
        db.child(ROOMS_PATH).child(code).child("outdoor_eggs").child(eggId.toString())
            .updateChildren(mapOf("caught" to true, "caughtBy" to caughtBy))
    }

    /** Aggiorna il punteggio di un giocatore */
    fun updateScore(code: String, playerId: String, playerName: String,
                    eggsFound: Int, totalMs: Long, finished: Boolean = false) {
        db.child(ROOMS_PATH).child(code).child("scores").child(playerId)
            .setValue(mapOf("playerName" to playerName, "eggsFound" to eggsFound,
                "totalMs" to totalMs, "finished" to finished))
    }

    /** Segna il giocatore offline */
    fun setPlayerOffline(code: String, playerId: String) {
        db.child(ROOMS_PATH).child(code).child("players").child(playerId)
            .child("online").setValue(false)
    }

    /** Chiude la stanza (host) */
    fun closeRoom(code: String) {
        db.child(ROOMS_PATH).child(code).child("status").setValue("finished")
    }

    // ─── Lista stanze pubbliche ───────────────────────────────────

    /**
     * Carica le stanze pubbliche attive, escludendo quelle scadute.
     * Firebase non supporta query TTL nativa → filtriamo lato client.
     */
    fun listPublicRooms(
        onResult: (rooms: List<RoomInfo>) -> Unit,
        onError:  (msg: String) -> Unit
    ) {
        db.child(ROOMS_PATH)
            .orderByChild("isPublic").equalTo(true)
            .limitToLast(50)
            .get()
            .addOnSuccessListener { snap ->
                val now   = System.currentTimeMillis()
                val rooms = snap.children.mapNotNull { r ->
                    val expiresAt = r.child("expiresAt").value as? Long ?: 0L
                    if (expiresAt > 0 && now > expiresAt) return@mapNotNull null   // scaduta
                    val status = r.child("status").value as? String ?: "playing"
                    if (status == "finished") return@mapNotNull null
                    RoomInfo(
                        code        = r.key ?: return@mapNotNull null,
                        roomName    = r.child("roomName").value    as? String ?: "?",
                        hostName    = r.child("hostName").value    as? String ?: "?",
                        eggCount    = (r.child("eggCount").value   as? Long)?.toInt() ?: 0,
                        playerCount = r.child("players").childrenCount.toInt(),
                        status      = status,
                        createdAt   = r.child("createdAt").value   as? Long ?: 0L,
                        expiresAt   = expiresAt,
                        isPublic    = true
                    )
                }.sortedByDescending { it.createdAt }
                onResult(rooms)
            }
            .addOnFailureListener { e -> onError(e.message ?: "Errore Firebase") }
    }

    // ─── Pulizia ─────────────────────────────────────────────────

    /**
     * Elimina le stanze scadute create dall'host corrente.
     * Chiama all'avvio app per mantenere Firebase pulito.
     * Attenzione: senza Firebase Security Rules questo elimina solo le stanze note localmente.
     */
    fun purgeExpiredRooms(knownCodes: List<String>) {
        val now = System.currentTimeMillis()
        knownCodes.forEach { code ->
            db.child(ROOMS_PATH).child(code).child("expiresAt").get()
                .addOnSuccessListener { snap ->
                    val exp = snap.value as? Long ?: return@addOnSuccessListener
                    if (exp > 0 && now > exp) {
                        db.child(ROOMS_PATH).child(code).removeValue()
                        Log.d(TAG, "Stanza scaduta eliminata: $code")
                    }
                }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    fun generatePlayerId() = "p_${System.currentTimeMillis()}_${Random.nextInt(9999)}"
}
