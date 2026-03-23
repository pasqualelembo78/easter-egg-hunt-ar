package com.intelligame.easteregghuntar

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * PlayerProfileManager — gestisce i profili giocatori su Firestore.
 *
 * Persistente per sempre: il profilo non scade mai.
 * Tutti i giocatori possono vedere il livello e il potere degli altri.
 *
 * Firestore collections:
 *   players/{playerId}     → profilo completo
 *   leaderboard/{playerId} → snapshot veloce per la classifica
 *
 * Il playerId è salvato in SharedPreferences sul device, così
 * il giocatore riconosce il proprio profilo ad ogni avvio.
 */
object PlayerProfileManager {

    private const val TAG         = "PlayerProfileManager"
    private const val PREF_FILE   = "world_game_prefs"
    private const val KEY_ID      = "world_player_id"
    private const val KEY_NAME    = "world_player_name"
    private const val COL_PLAYERS = "players"
    private const val COL_LEADER  = "leaderboard"

    private val db = FirebaseFirestore.getInstance()

    // ─── Profilo locale (cache) ──────────────────────────────────
    private var _myProfile: PlayerProfile? = null
    val myProfile: PlayerProfile? get() = _myProfile

    // ─── Init / get-or-create ────────────────────────────────────

    /**
     * Carica o crea il profilo del giocatore locale.
     * Usa SharedPreferences per ricordare l'ID tra sessioni.
     */
    fun initMyProfile(
        context:   Context,
        name:      String,
        onReady:   (profile: PlayerProfile) -> Unit,
        onError:   (msg: String) -> Unit
    ) {
        val prefs    = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val savedId  = prefs.getString(KEY_ID, null)
        val savedName= prefs.getString(KEY_NAME, null)

        if (savedId != null) {
            // Profilo esistente: carica da Firestore
            loadProfile(savedId, onSuccess = { profile ->
                _myProfile = profile
                onReady(profile)
            }, onNotFound = {
                // Profilo non trovato su Firestore (primo accesso su nuovo device): ricrea
                createProfile(name.ifBlank { savedName ?: "Giocatore" }, savedId, prefs, onReady, onError)
            }, onError = onError)
        } else {
            // Nuovo giocatore
            val newId = PlayerProfile.generateId(name)
            createProfile(name.ifBlank { "Giocatore" }, newId, prefs, onReady, onError)
        }
    }

    private fun createProfile(
        name:    String,
        id:      String,
        prefs:   SharedPreferences,
        onReady: (PlayerProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        val profile = PlayerProfile(playerId = id, name = name)
        db.collection(COL_PLAYERS).document(id)
            .set(profile.toFirestore())
            .addOnSuccessListener {
                prefs.edit().putString(KEY_ID, id).putString(KEY_NAME, name).apply()
                _myProfile = profile
                updateLeaderboard(profile)
                Log.d(TAG, "Profilo creato: $id")
                onReady(profile)
            }
            .addOnFailureListener { e -> onError(e.message ?: "Errore creazione profilo") }
    }

    private fun loadProfile(
        playerId:   String,
        onSuccess:  (PlayerProfile) -> Unit,
        onNotFound: () -> Unit,
        onError:    (String) -> Unit
    ) {
        db.collection(COL_PLAYERS).document(playerId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) { onNotFound(); return@addOnSuccessListener }
                val profile = PlayerProfile.fromFirestore(doc.data ?: emptyMap())
                if (profile == null) { onNotFound(); return@addOnSuccessListener }
                _myProfile = profile
                onSuccess(profile)
            }
            .addOnFailureListener { e -> onError(e.message ?: "Errore caricamento profilo") }
    }

    // ─── Aggiornamento dopo cattura uovo ─────────────────────────

    /**
     * Chiamare quando il giocatore cattura un WorldEgg.
     * Aggiorna Firestore atomicamente e la cache locale.
     */
    fun recordEggCatch(rarity: EggRarity, onComplete: (newProfile: PlayerProfile) -> Unit) {
        val profile = _myProfile ?: return
        profile.addEggReward(rarity)
        saveProfile(profile) { onComplete(profile) }
    }

    /**
     * Chiamare dopo l'allenamento in palestra.
     */
    fun recordTraining(powerGained: Long, xpGained: Long, onComplete: (PlayerProfile) -> Unit) {
        val profile = _myProfile ?: return
        profile.addTrainingReward(powerGained, xpGained)
        saveProfile(profile) { onComplete(profile) }
    }

    private fun saveProfile(profile: PlayerProfile, onComplete: (() -> Unit)? = null) {
        db.collection(COL_PLAYERS).document(profile.playerId)
            .set(profile.toFirestore(), SetOptions.merge())
            .addOnSuccessListener {
                updateLeaderboard(profile)
                onComplete?.invoke()
            }
            .addOnFailureListener { e -> Log.e(TAG, "Errore salvataggio: ${e.message}") }
    }

    private fun updateLeaderboard(profile: PlayerProfile) {
        db.collection(COL_LEADER).document(profile.playerId).set(
            mapOf(
                "playerId"  to profile.playerId,
                "name"      to profile.name,
                "level"     to profile.level,
                "power"     to profile.power,
                "xp"        to profile.xp,
                "eggsFound" to profile.eggsFound,
                "title"     to profile.title,
                "lastSeen"  to profile.lastSeen
            ), SetOptions.merge()
        )
    }

    // ─── Leaderboard ─────────────────────────────────────────────

    data class LeaderboardEntry(
        val playerId: String = "",
        val name:     String = "",
        val level:    Int    = 1,
        val power:    Long   = 0L,
        val xp:       Long   = 0L,
        val title:    String = "",
        val eggsFound:Int    = 0
    )

    /**
     * Carica i top 50 giocatori per potere.
     * Chiunque può vedere la classifica di tutti.
     */
    fun getLeaderboard(onResult: (List<LeaderboardEntry>) -> Unit, onError: (String) -> Unit) {
        db.collection(COL_LEADER)
            .orderBy("power", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    LeaderboardEntry(
                        playerId  = d["playerId"] as? String ?: return@mapNotNull null,
                        name      = d["name"]     as? String ?: "?",
                        level     = (d["level"]   as? Long)?.toInt() ?: 1,
                        power     = d["power"]    as? Long ?: 0L,
                        xp        = d["xp"]       as? Long ?: 0L,
                        title     = d["title"]    as? String ?: "",
                        eggsFound = (d["eggsFound"] as? Long)?.toInt() ?: 0
                    )
                }
                onResult(list)
            }
            .addOnFailureListener { e -> onError(e.message ?: "Errore leaderboard") }
    }

    // ─── Profilo di un altro giocatore ───────────────────────────

    fun getPlayerProfile(playerId: String, onResult: (PlayerProfile?) -> Unit) {
        db.collection(COL_PLAYERS).document(playerId).get()
            .addOnSuccessListener { doc ->
                onResult(if (doc.exists()) PlayerProfile.fromFirestore(doc.data ?: emptyMap()) else null)
            }
            .addOnFailureListener { onResult(null) }
    }

    // ─── Eliminazione profilo ─────────────────────────────────────

    fun deleteMyProfile(context: Context, onComplete: () -> Unit) {
        val id = _myProfile?.playerId ?: return
        db.collection(COL_PLAYERS).document(id).delete()
        db.collection(COL_LEADER).document(id).delete()
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().clear().apply()
        _myProfile = null
        onComplete()
    }
}
