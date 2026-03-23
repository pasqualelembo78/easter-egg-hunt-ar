package com.intelligame.easteregghuntar

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.UUID
import kotlin.math.*

/**
 * GymLocation — palestra nel mondo reale.
 *
 * Le palestre sono punti fissi sulla mappa, persistenti per sempre.
 * Possono essere aggiunte dall'host quando crea una stanza, oppure
 * da un "admin" tramite una stanza speciale con codice ADMIN.
 *
 * Firestore: gyms/{gymId}
 */
data class GymLocation(
    val gymId:        String = "",
    val name:         String = "",
    val lat:          Double = 0.0,
    val lng:          Double = 0.0,
    val type:         String = "standard",   // "standard" | "elite" | "legendary"
    val power:        Long   = 0L,           // potere accumulato da tutti gli allenamenti
    val topTrainer:   String = "",           // nome del trainer più forte
    val topTrainerId: String = "",
    val trainCount:   Int    = 0,            // allenamenti totali eseguiti
    val createdBy:    String = "",
    val createdAt:    Long   = System.currentTimeMillis()
) {
    val emoji: String get() = when (type) {
        "legendary" -> "⭐"
        "elite"     -> "🏟️"
        else        -> "💪"
    }

    val maxPowerReward: Long get() = when (type) {
        "legendary" -> 500L
        "elite"     -> 200L
        else        -> 80L
    }

    val maxXpReward: Long get() = when (type) {
        "legendary" -> 1000L
        "elite"     -> 400L
        else        -> 150L
    }

    fun distanceTo(lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat - lat2)
        val dLng = Math.toRadians(lng - lng2)
        val a    = sin(dLat/2)*sin(dLat/2) +
                   cos(Math.toRadians(lat2))*cos(Math.toRadians(lat)) *
                   sin(dLng/2)*sin(dLng/2)
        return 6371000.0 * 2 * atan2(sqrt(a), sqrt(1-a))
    }

    fun toFirestore(): Map<String, Any> = mapOf(
        "gymId"        to gymId,
        "name"         to name,
        "lat"          to lat,
        "lng"          to lng,
        "type"         to type,
        "power"        to power,
        "topTrainer"   to topTrainer,
        "topTrainerId" to topTrainerId,
        "trainCount"   to trainCount,
        "createdBy"    to createdBy,
        "createdAt"    to createdAt
    )

    companion object {
        fun fromFirestore(map: Map<String, Any?>): GymLocation? {
            val id = map["gymId"] as? String ?: return null
            return GymLocation(
                gymId        = id,
                name         = map["name"]         as? String ?: "Palestra",
                lat          = map["lat"]           as? Double ?: return null,
                lng          = map["lng"]           as? Double ?: return null,
                type         = map["type"]          as? String ?: "standard",
                power        = map["power"]         as? Long   ?: 0L,
                topTrainer   = map["topTrainer"]    as? String ?: "",
                topTrainerId = map["topTrainerId"]  as? String ?: "",
                trainCount   = (map["trainCount"]   as? Long)?.toInt() ?: 0,
                createdBy    = map["createdBy"]     as? String ?: "",
                createdAt    = map["createdAt"]     as? Long   ?: 0L
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * GymManager — gestisce le palestre su Firestore.
 *
 * Le palestre sono PERSISTENTI PER SEMPRE e visibili a tutti.
 * Chiunque può allenarsi in una palestra vicina (entro 100m).
 * L'allenamento ha un cooldown per palestra per giocatore (1 volta al giorno).
 */
object GymManager {

    private const val TAG         = "GymManager"
    private const val COL_GYMS    = "gyms"
    private const val COL_TRAINS  = "gym_trainings"  // log allenamenti
    private const val TRAIN_DIST  = 100.0            // m per allenarsi
    private const val LOAD_DIST   = 1000.0           // m di visibilità

    private val db = FirebaseFirestore.getInstance()

    // ─── Caricamento palestre vicine ─────────────────────────────

    fun loadGymsNear(
        lat:      Double,
        lng:      Double,
        onResult: (List<GymLocation>) -> Unit,
        onError:  (String) -> Unit
    ) {
        val latDelta = LOAD_DIST / 111000.0
        val lngDelta = LOAD_DIST / (111000.0 * cos(Math.toRadians(lat)))

        db.collection(COL_GYMS)
            .whereGreaterThan("lat", lat - latDelta)
            .whereLessThan("lat", lat + latDelta)
            .limit(20)
            .get()
            .addOnSuccessListener { snap ->
                val gyms = snap.documents.mapNotNull { doc ->
                    GymLocation.fromFirestore(doc.data ?: return@mapNotNull null)
                }.filter { gym ->
                    gym.lng >= lng - lngDelta && gym.lng <= lng + lngDelta &&
                    gym.distanceTo(lat, lng) <= LOAD_DIST
                }
                onResult(gyms)
            }
            .addOnFailureListener { e -> onError(e.message ?: "Errore caricamento palestre") }
    }

    // ─── Aggiunta palestra ────────────────────────────────────────

    fun addGym(
        name:      String,
        lat:       Double,
        lng:       Double,
        type:      String = "standard",
        createdBy: String,
        onSuccess: (GymLocation) -> Unit,
        onError:   (String) -> Unit
    ) {
        val gym = GymLocation(
            gymId     = UUID.randomUUID().toString().replace("-","").take(12),
            name      = name.ifBlank { "Palestra" },
            lat       = lat,
            lng       = lng,
            type      = type,
            createdBy = createdBy,
            createdAt = System.currentTimeMillis()
        )
        db.collection(COL_GYMS).document(gym.gymId)
            .set(gym.toFirestore())
            .addOnSuccessListener { onSuccess(gym) }
            .addOnFailureListener { e -> onError(e.message ?: "Errore creazione palestra") }
    }

    // ─── Allenamento ─────────────────────────────────────────────

    data class TrainingResult(
        val powerGained: Long,
        val xpGained:    Long,
        val message:     String
    )

    /**
     * Esegue l'allenamento in palestra.
     * Cooldown: 1 volta ogni 23 ore per palestra per giocatore.
     * Il potere guadagnato scala con il livello del giocatore e la difficoltà del mini-gioco.
     */
    fun trainAtGym(
        gym:         GymLocation,
        player:      PlayerProfile,
        scorePercent: Int,   // 0-100, punteggio del mini-gioco (più alto = più ricompensa)
        onSuccess:   (TrainingResult) -> Unit,
        onAlreadyTrained: () -> Unit,
        onError:     (String) -> Unit
    ) {
        val trainKey = "${player.playerId}_${gym.gymId}"
        val cooldownMs = 23 * 3600_000L

        // Controlla cooldown
        db.collection(COL_TRAINS).document(trainKey).get()
            .addOnSuccessListener { doc ->
                val lastTrain = doc.getLong("trainedAt") ?: 0L
                if (System.currentTimeMillis() - lastTrain < cooldownMs) {
                    onAlreadyTrained(); return@addOnSuccessListener
                }

                // Calcola ricompensa
                val mult  = scorePercent / 100f
                val power = (gym.maxPowerReward * mult).toLong().coerceAtLeast(5L)
                val xp    = (gym.maxXpReward    * mult).toLong().coerceAtLeast(10L)

                val now = System.currentTimeMillis()
                val batch = db.batch()

                // Salva log allenamento
                batch.set(db.collection(COL_TRAINS).document(trainKey), mapOf(
                    "playerId"    to player.playerId,
                    "playerName"  to player.name,
                    "gymId"       to gym.gymId,
                    "gymName"     to gym.name,
                    "powerGained" to power,
                    "xpGained"    to xp,
                    "trainedAt"   to now
                ))

                // Aggiorna palestra: +power, aggiorna top trainer se necessario
                val newGymPower   = gym.power + power
                val isNewTopTrainer = player.power + power > (gym.power)
                val gymUpdate = mutableMapOf<String, Any>(
                    "power"      to newGymPower,
                    "trainCount" to (gym.trainCount + 1)
                )
                if (isNewTopTrainer) {
                    gymUpdate["topTrainer"]   = player.name
                    gymUpdate["topTrainerId"] = player.playerId
                }
                batch.update(db.collection(COL_GYMS).document(gym.gymId), gymUpdate)

                batch.commit()
                    .addOnSuccessListener {
                        val msg = when {
                            scorePercent >= 90 -> "Allenamento perfetto! 🏆"
                            scorePercent >= 60 -> "Buon allenamento! 💪"
                            else               -> "Allenamento completato"
                        }
                        onSuccess(TrainingResult(power, xp, msg))
                    }
                    .addOnFailureListener { e -> onError(e.message ?: "Errore allenamento") }
            }
            .addOnFailureListener { e -> onError(e.message ?: "Errore verifica cooldown") }
    }
}
