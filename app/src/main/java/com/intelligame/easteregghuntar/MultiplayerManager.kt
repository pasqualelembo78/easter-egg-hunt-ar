package com.intelligame.easteregghuntar

import android.util.Log
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlin.random.Random

/**
 * MultiplayerManager v2 — stanze con nome, sfoglia stanze pubbliche, chat in-game.
 *
 * Struttura DB:
 *   rooms/{code}/
 *     roomName, hostId, hostName, isPublic, status ("waiting"|"playing"|"finished")
 *     createdAt, config/{eggCount, trapCount, penaltySecs, riddles}
 *     players/{playerId}/{name, online, joinedAt}
 *     scores/{playerId}/{playerName, eggsFound, totalMs, finished, lastUpdate}
 *     chat/{pushId}/{senderId, senderName, text, ts, type("msg"|"system")}
 */
class MultiplayerManager private constructor() {

    companion object {
        @Volatile private var inst: MultiplayerManager? = null
        fun get() = inst ?: synchronized(this) { inst ?: MultiplayerManager().also { inst = it } }

        const val EXTRA_IS_MP        = "mp_active"
        const val EXTRA_IS_HOST      = "mp_is_host"
        const val EXTRA_ROOM_CODE    = "mp_room_code"
        const val EXTRA_ROOM_NAME    = "mp_room_name"
        const val EXTRA_PLAYER_ID    = "mp_player_id"
        const val EXTRA_PLAYER_NAME  = "mp_player_name"
        const val EXTRA_EGG_COUNT    = "mp_egg_count"
        const val EXTRA_TRAP_COUNT   = "mp_trap_count"
        const val EXTRA_PENALTY_SECS = "mp_penalty_secs"
        const val EXTRA_RIDDLES      = "mp_riddles"
        private const val TAG        = "MultiplayerManager"
        const val ROOMS              = "rooms"
        const val MAX_CHAT_MESSAGES  = 100
    }

    data class RoomPlayer(val id: String = "", val name: String = "", val online: Boolean = true)

    data class PlayerScore(
        val playerId: String   = "", val playerName: String = "",
        val eggsFound: Int     = 0,  val totalMs: Long      = 0L,
        val finished: Boolean  = false, val lastUpdate: Long = 0L
    )

    data class GameConfig(
        val eggCount: Int = 4, val trapCount: Int = 0,
        val penaltySecs: Int = 30, val riddles: List<String> = emptyList()
    )

    data class ChatMessage(
        val id: String = "", val senderId: String = "", val senderName: String = "",
        val text: String = "", val ts: Long = 0L, val type: String = "msg"
    )

    data class RoomInfo(
        val code: String = "", val roomName: String = "", val hostName: String = "",
        val playerCount: Int = 0, val status: String = "waiting", val createdAt: Long = 0L
    )

    var currentRoomCode   = ""; private set
    var currentRoomName   = ""; private set
    var currentPlayerId   = ""; private set
    var currentPlayerName = ""; private set
    var isHost            = false; private set

    var onPlayersChanged : ((List<RoomPlayer>)  -> Unit)? = null
    var onScoresChanged  : ((List<PlayerScore>) -> Unit)? = null
    var onGameStarted    : ((GameConfig)        -> Unit)? = null
    var onChatMessage    : ((ChatMessage)       -> Unit)? = null
    var onError          : ((String)            -> Unit)? = null

    private var playersRef : DatabaseReference? = null
    private var scoresRef  : DatabaseReference? = null
    private var statusRef  : DatabaseReference? = null
    private var chatRef    : Query? = null
    private var playersL   : ValueEventListener? = null
    private var scoresL    : ValueEventListener? = null
    private var statusL    : ValueEventListener? = null
    private var chatL      : ChildEventListener? = null

    private var firebaseOk = true
    private var firebaseInitError = ""

    private val db: DatabaseReference? by lazy {
        try {
            val database = Firebase.database
            database.setPersistenceEnabled(false)
            database.reference
        } catch (e: Exception) {
            firebaseOk = false
            firebaseInitError = when {
                e.message?.contains("Specify DatabaseURL") == true ->
                    "Realtime Database non creato.\nVai: Firebase Console → Build → Realtime Database → Crea database."
                e.message?.contains("FirebaseApp") == true ->
                    "google-services.json non valido.\nScaricalo da Firebase Console e sostituisci il file in app/."
                else -> e.message ?: "Errore Firebase sconosciuto"
            }
            Log.e(TAG, "Firebase init error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
    fun generatePlayerId() = "p_${System.currentTimeMillis()}_${Random.nextInt(9999)}"

    // ─── Crea stanza ─────────────────────────────────────────────
    fun createRoom(playerName: String, roomName: String, isPublic: Boolean,
                   config: GameConfig, onSuccess: (String) -> Unit, onFail: (String) -> Unit) {
        val d = db ?: run { onFail(firebaseInitError.ifEmpty { "Firebase non configurato." }); return }
        val code = generateRoomCode()
        val pid  = generatePlayerId()
        currentRoomCode = code; currentPlayerId = pid
        currentPlayerName = playerName; currentRoomName = roomName; isHost = true

        val riddleMap = config.riddles.mapIndexed { i, r -> i.toString() to r }.toMap()
        val data = mapOf(
            "roomName"  to roomName,
            "hostId"    to pid, "hostName" to playerName,
            "isPublic"  to isPublic,
            "status"    to "waiting",
            "createdAt" to ServerValue.TIMESTAMP,
            "config"    to mapOf("eggCount" to config.eggCount, "trapCount" to config.trapCount,
                "penaltySecs" to config.penaltySecs, "riddles" to riddleMap),
            "players"   to mapOf(pid to mapOf("id" to pid, "name" to playerName,
                "online" to true, "joinedAt" to ServerValue.TIMESTAMP))
        )
        d.child(ROOMS).child(code).setValue(data)
            .addOnSuccessListener {
                listenToRoom(code)
                sendSystemMessage("🎮 Stanza \"$roomName\" creata da $playerName")
                onSuccess(code)
            }
            .addOnFailureListener { e -> onFail(e.message ?: "Errore creazione stanza") }
    }

    // ─── Unisciti stanza ─────────────────────────────────────────
    fun joinRoom(code: String, playerName: String,
                 onSuccess: (String) -> Unit, onFail: (String) -> Unit) {
        val d = db ?: run { onFail(firebaseInitError.ifEmpty { "Firebase non configurato." }); return }
        val pid = generatePlayerId()
        currentPlayerId = pid; currentPlayerName = playerName; isHost = false

        d.child(ROOMS).child(code).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) { onFail("Stanza non trovata! Controlla il codice."); return@addOnSuccessListener }
                val status = snap.child("status").value as? String ?: "waiting"
                if (status == "finished") { onFail("Questa partita è già terminata."); return@addOnSuccessListener }
                if (status == "playing")  { onFail("La partita è già iniziata."); return@addOnSuccessListener }
                val rName = snap.child("roomName").value as? String ?: code
                currentRoomCode = code; currentRoomName = rName
                d.child(ROOMS).child(code).child("players").child(pid)
                    .setValue(mapOf("id" to pid, "name" to playerName, "online" to true,
                        "joinedAt" to ServerValue.TIMESTAMP))
                    .addOnSuccessListener {
                        listenToRoom(code)
                        sendSystemMessage("👋 $playerName è entrato nella stanza")
                        onSuccess(rName)
                    }
                    .addOnFailureListener { e -> onFail(e.message ?: "Errore connessione") }
            }
            .addOnFailureListener { e -> onFail(e.message ?: "Impossibile raggiungere il server") }
    }

    // ─── Lista stanze pubbliche ───────────────────────────────────
    fun getRoomList(onResult: (List<RoomInfo>) -> Unit, onFail: (String) -> Unit) {
        val d = db ?: run { onFail(firebaseInitError.ifEmpty { "Firebase non configurato." }); return }
        d.child(ROOMS).orderByChild("isPublic").equalTo(true).limitToLast(30).get()
            .addOnSuccessListener { snap ->
                val list = snap.children.mapNotNull { r ->
                    val status = r.child("status").value as? String ?: return@mapNotNull null
                    if (status == "finished") return@mapNotNull null
                    RoomInfo(
                        code        = r.key ?: return@mapNotNull null,
                        roomName    = r.child("roomName").value   as? String ?: "?",
                        hostName    = r.child("hostName").value   as? String ?: "?",
                        playerCount = r.child("players").childrenCount.toInt(),
                        status      = status,
                        createdAt   = r.child("createdAt").value as? Long ?: 0L
                    )
                }.sortedByDescending { it.createdAt }
                onResult(list)
            }
            .addOnFailureListener { e -> onFail(e.message ?: "Errore caricamento stanze") }
    }

    // ─── Avvia partita (host) ─────────────────────────────────────
    fun startGame(config: GameConfig, onComplete: () -> Unit) {
        val d = db ?: return; if (!isHost) return
        val riddleMap = config.riddles.mapIndexed { i, r -> i.toString() to r }.toMap()
        d.child(ROOMS).child(currentRoomCode).updateChildren(mapOf<String, Any>(
            "status" to "playing", "startedAt" to ServerValue.TIMESTAMP,
            "config/eggCount" to config.eggCount, "config/trapCount" to config.trapCount,
            "config/penaltySecs" to config.penaltySecs, "config/riddles" to riddleMap
        )).addOnSuccessListener {
            sendSystemMessage("🚀 La caccia è iniziata! Buona fortuna a tutti!")
            onComplete()
        }.addOnFailureListener { e -> onError?.invoke(e.message ?: "Errore avvio") }
    }

    // ─── Chat ─────────────────────────────────────────────────────
    fun sendChatMessage(text: String) {
        if (text.isBlank() || currentRoomCode.isEmpty()) return
        db?.child(ROOMS)?.child(currentRoomCode)?.child("chat")?.push()?.setValue(
            mapOf("senderId" to currentPlayerId, "senderName" to currentPlayerName,
                  "text" to text.trim(), "ts" to ServerValue.TIMESTAMP, "type" to "msg")
        )
    }

    fun sendSystemMessage(text: String) {
        if (currentRoomCode.isEmpty()) return
        db?.child(ROOMS)?.child(currentRoomCode)?.child("chat")?.push()?.setValue(
            mapOf("senderId" to "system", "senderName" to "Sistema",
                  "text" to text, "ts" to ServerValue.TIMESTAMP, "type" to "system")
        )
    }

    // ─── Score sync ───────────────────────────────────────────────
    fun updateMyScore(eggsFound: Int, totalMs: Long, finished: Boolean = false) {
        if (currentRoomCode.isEmpty() || currentPlayerId.isEmpty()) return
        db?.child(ROOMS)?.child(currentRoomCode)?.child("scores")?.child(currentPlayerId)
            ?.setValue(mapOf("playerId" to currentPlayerId, "playerName" to currentPlayerName,
                "eggsFound" to eggsFound, "totalMs" to totalMs, "finished" to finished,
                "lastUpdate" to ServerValue.TIMESTAMP))
    }

    fun reportEggFound(eggIdx: Int, timeMs: Long) {
        if (currentRoomCode.isEmpty()) return
        db?.child(ROOMS)?.child(currentRoomCode)?.child("events")?.push()?.setValue(
            mapOf("type" to "egg_found", "playerId" to currentPlayerId,
                  "playerName" to currentPlayerName, "eggIdx" to eggIdx,
                  "timeMs" to timeMs, "ts" to ServerValue.TIMESTAMP)
        )
        sendSystemMessage("🥚 $currentPlayerName ha trovato l'uovo #${eggIdx + 1}!")
    }

    // ─── Listeners ───────────────────────────────────────────────
    private fun listenToRoom(code: String) {
        val d = db ?: return

        playersRef = d.child(ROOMS).child(code).child("players")
        playersL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                onPlayersChanged?.invoke(s.children.mapNotNull { c ->
                    RoomPlayer(id = c.child("id").value as? String ?: return@mapNotNull null,
                        name   = c.child("name").value   as? String  ?: "?",
                        online = c.child("online").value as? Boolean ?: true)
                })
            }
            override fun onCancelled(e: DatabaseError) { onError?.invoke(e.message) }
        }
        playersRef!!.addValueEventListener(playersL!!)

        scoresRef = d.child(ROOMS).child(code).child("scores")
        scoresL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                onScoresChanged?.invoke(s.children.mapNotNull { c ->
                    PlayerScore(
                        playerId   = c.child("playerId").value   as? String  ?: return@mapNotNull null,
                        playerName = c.child("playerName").value as? String  ?: "?",
                        eggsFound  = (c.child("eggsFound").value as? Long)?.toInt() ?: 0,
                        totalMs    = c.child("totalMs").value    as? Long    ?: 0L,
                        finished   = c.child("finished").value   as? Boolean ?: false,
                        lastUpdate = c.child("lastUpdate").value as? Long    ?: 0L
                    )
                }.sortedWith(compareByDescending<PlayerScore> { it.eggsFound }.thenBy { it.totalMs }))
            }
            override fun onCancelled(e: DatabaseError) { onError?.invoke(e.message) }
        }
        scoresRef!!.addValueEventListener(scoresL!!)

        statusRef = d.child(ROOMS).child(code).child("status")
        statusL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.value as? String == "playing" && !isHost) {
                    d.child(ROOMS).child(code).child("config").get()
                        .addOnSuccessListener { cfg ->
                            onGameStarted?.invoke(GameConfig(
                                eggCount    = (cfg.child("eggCount").value    as? Long)?.toInt() ?: 4,
                                trapCount   = (cfg.child("trapCount").value   as? Long)?.toInt() ?: 0,
                                penaltySecs = (cfg.child("penaltySecs").value as? Long)?.toInt() ?: 30,
                                riddles     = cfg.child("riddles").children
                                    .sortedBy { it.key?.toIntOrNull() ?: 0 }
                                    .map { it.value as? String ?: "" }
                            ))
                        }
                }
            }
            override fun onCancelled(e: DatabaseError) { onError?.invoke(e.message) }
        }
        statusRef!!.addValueEventListener(statusL!!)

        chatRef = d.child(ROOMS).child(code).child("chat")
            .orderByChild("ts").limitToLast(MAX_CHAT_MESSAGES)
        chatL = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                onChatMessage?.invoke(ChatMessage(
                    id         = s.key ?: "",
                    senderId   = s.child("senderId").value   as? String ?: "",
                    senderName = s.child("senderName").value as? String ?: "?",
                    text       = s.child("text").value       as? String ?: "",
                    ts         = s.child("ts").value         as? Long   ?: 0L,
                    type       = s.child("type").value       as? String ?: "msg"
                ))
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) { onError?.invoke(e.message) }
        }
        chatRef!!.addChildEventListener(chatL!!)
    }

    // ─── Disconnetti ─────────────────────────────────────────────
    fun disconnect() {
        val d = db
        if (d != null && currentRoomCode.isNotEmpty() && currentPlayerId.isNotEmpty()) {
            d.child(ROOMS).child(currentRoomCode).child("players")
                .child(currentPlayerId).child("online").setValue(false)
            sendSystemMessage("👋 $currentPlayerName ha lasciato la stanza")
        }
        playersL?.let { playersRef?.removeEventListener(it) }
        scoresL?.let  { scoresRef?.removeEventListener(it) }
        statusL?.let  { statusRef?.removeEventListener(it) }
        chatL?.let    { chatRef?.removeEventListener(it) }
        playersL = null; scoresL = null; statusL = null; chatL = null
        currentRoomCode = ""; currentPlayerId = ""; isHost = false
    }

    fun isFirebaseAvailable()  = firebaseOk
    fun getFirebaseInitError() = firebaseInitError
}
