package com.intelligame.easteregghuntar

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.util.UUID
import kotlin.math.*

/**
 * EggSpawnManager — genera uova nel mondo reale vicino alla posizione GPS del giocatore.
 *
 * Logica spawn:
 *  - Ogni N minuti (o su richiesta) genera uova nell'area intorno al giocatore
 *  - Distribuzione ponderata per rarità (la maggior parte comuni)
 *  - Le uova spariscono dopo il loro TTL
 *  - Una stessa area non viene rispawnata troppo frequentemente (cooldown)
 *  - Le uova sono visibili a TUTTI i giocatori in quell'area
 *
 * Firestore: world_eggs/{eggId}
 *   Regola: le uova scadute vengono eliminate automaticamente tramite
 *   Cloud Firestore TTL o dalla pulizia lato client.
 */
object EggSpawnManager {

    private const val TAG             = "EggSpawnManager"
    private const val COL_WORLD_EGGS  = "world_eggs"
    private const val SPAWN_RADIUS_M  = 300.0   // raggio spawn in metri
    private const val LOAD_RADIUS_M   = 500.0   // raggio visibilità sulla mappa
    private const val MIN_EGG_DIST_M  = 15.0    // distanza minima tra uova
    private const val SPAWN_COUNT     = 8        // uova per batch di spawn
    private const val COOLDOWN_MS     = 5 * 60_000L  // 5 minuti tra spawn nella stessa area

    private val db = FirebaseFirestore.getInstance()
    private var lastSpawnLat  = 0.0
    private var lastSpawnLng  = 0.0
    private var lastSpawnTime = 0L

    // ─── Spawn ───────────────────────────────────────────────────

    /**
     * Spawna un batch di uova vicino alla posizione GPS del giocatore.
     * Chiama solo se necessario (posizione cambiata significativamente o cooldown scaduto).
     */
    fun spawnNearPlayer(
        playerLat:   Double,
        playerLng:   Double,
        playerLevel: Int,
        onComplete:  (eggsSpawned: Int) -> Unit,
        onError:     (String) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val distFromLastSpawn = distanceM(playerLat, playerLng, lastSpawnLat, lastSpawnLng)

        // Cooldown: se siamo ancora vicini all'ultimo spawn e non è passato il cooldown, skip
        if (distFromLastSpawn < 100 && now - lastSpawnTime < COOLDOWN_MS) {
            onComplete(0); return
        }

        lastSpawnLat  = playerLat
        lastSpawnLng  = playerLng
        lastSpawnTime = now

        val eggs = generateEggs(playerLat, playerLng, playerLevel)
        if (eggs.isEmpty()) { onComplete(0); return }

        val batch = db.batch()
        eggs.forEach { egg ->
            val ref = db.collection(COL_WORLD_EGGS).document(egg.id)
            batch.set(ref, egg.toFirestore())
        }
        batch.commit()
            .addOnSuccessListener {
                Log.d(TAG, "Spawnate ${eggs.size} uova vicino a ($playerLat, $playerLng)")
                onComplete(eggs.size)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Errore spawn: ${e.message}")
                onError(e.message ?: "Errore spawn uova")
            }
    }

    private fun generateEggs(
        centerLat:   Double,
        centerLng:   Double,
        playerLevel: Int
    ): List<WorldEgg> {
        val eggs    = mutableListOf<WorldEgg>()
        val placed  = mutableListOf<Pair<Double, Double>>()
        var attempts = 0

        // Bonus rarità per livello alto del giocatore
        // Livello > 10: +1 peso raro, livello > 20: +1 peso epico, ecc.
        val rarityBonus = when {
            playerLevel >= 30 -> EggRarity.EPIC
            playerLevel >= 20 -> EggRarity.RARE
            playerLevel >= 10 -> EggRarity.UNCOMMON
            else              -> null
        }

        while (eggs.size < SPAWN_COUNT && attempts < SPAWN_COUNT * 20) {
            attempts++
            val angle   = Math.random() * 2 * Math.PI
            val dist    = MIN_EGG_DIST_M + Math.random() * (SPAWN_RADIUS_M - MIN_EGG_DIST_M)
            val lat     = centerLat + (dist * cos(angle)) / 111000.0
            val lng     = centerLng + (dist * sin(angle)) / (111000.0 * cos(Math.toRadians(centerLat)))

            if (placed.any { (la, lo) -> distanceM(lat, lng, la, lo) < MIN_EGG_DIST_M }) continue

            // Rarità: a volte usa la rarità bonus del livello del giocatore
            val rarity = if (rarityBonus != null && Math.random() < 0.15)
                rarityBonus
            else
                EggRarity.weightedRandom()

            val now     = System.currentTimeMillis()
            val egg     = WorldEgg(
                id        = UUID.randomUUID().toString().replace("-", "").take(16),
                lat       = lat,
                lng       = lng,
                rarity    = rarity,
                fantasyName = rarity.randomName(),
                spawnedAt = now,
                expiresAt = now + rarity.ttlMinutes * 60_000L
            )
            eggs.add(egg)
            placed.add(lat to lng)
        }
        return eggs
    }

    // ─── Caricamento uova visibili ───────────────────────────────

    /**
     * Carica le uova WorldEgg visibili nell'area intorno al giocatore.
     * Filtra quelle già catturate e quelle scadute.
     */
    fun loadEggsNearPlayer(
        playerLat: Double,
        playerLng: Double,
        onResult:  (List<WorldEgg>) -> Unit,
        onError:   (String) -> Unit
    ) {
        val now = System.currentTimeMillis()

        // Bounding box approssimato per filtrare lato server
        val latDelta = LOAD_RADIUS_M / 111000.0
        val lngDelta = LOAD_RADIUS_M / (111000.0 * cos(Math.toRadians(playerLat)))

        db.collection(COL_WORLD_EGGS)
            .whereGreaterThan("lat", playerLat - latDelta)
            .whereLessThan("lat", playerLat + latDelta)
            .whereEqualTo("caught", false)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                val eggs = snap.documents.mapNotNull { doc ->
                    WorldEgg.fromFirestore(doc.data ?: return@mapNotNull null)
                }.filter { egg ->
                    !egg.isExpired &&
                    distanceM(egg.lat, egg.lng, playerLat, playerLng) <= LOAD_RADIUS_M &&
                    egg.lng >= playerLng - lngDelta &&
                    egg.lng <= playerLng + lngDelta
                }
                onResult(eggs)
            }
            .addOnFailureListener { e -> onError(e.message ?: "Errore caricamento uova") }
    }

    // ─── Cattura ─────────────────────────────────────────────────

    /**
     * Marca un'uovo come catturato su Firestore (operazione atomica).
     * Se due giocatori tentano di catturare la stessa uovo, vince il primo.
     */
    fun catchEgg(
        egg:      WorldEgg,
        playerId: String,
        playerName: String,
        onSuccess: (WorldEgg) -> Unit,
        onFailed:  (String) -> Unit
    ) {
        val ref = db.collection(COL_WORLD_EGGS).document(egg.id)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val alreadyCaught = snap.getBoolean("caught") ?: false
            if (alreadyCaught) throw Exception("Uovo già catturato!")
            val now = System.currentTimeMillis()
            tx.update(ref, mapOf(
                "caught"       to true,
                "caughtBy"     to playerId,
                "caughtByName" to playerName,
                "caughtAt"     to now
            ))
            egg.copy(caught = true, caughtBy = playerId, caughtByName = playerName, caughtAt = now)
        }.addOnSuccessListener { updatedEgg ->
            onSuccess(updatedEgg)
        }.addOnFailureListener { e ->
            onFailed(e.message ?: "Qualcun altro l'ha già presa!")
        }
    }

    // ─── Pulizia uova scadute ─────────────────────────────────────

    /**
     * Elimina le uova scadute. Chiamare periodicamente (es. all'avvio).
     * In produzione, meglio usare una Firebase Cloud Function con TTL automatico.
     */
    fun purgeExpiredEggs() {
        val now = System.currentTimeMillis()
        db.collection(COL_WORLD_EGGS)
            .whereLessThan("expiresAt", now)
            .limit(100)
            .get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) return@addOnSuccessListener
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.commit().addOnSuccessListener {
                    Log.d(TAG, "Eliminate ${snap.size()} uova scadute")
                }
            }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat1 - lat2)
        val dLng = Math.toRadians(lng1 - lng2)
        val a    = sin(dLat/2)*sin(dLat/2) +
                   cos(Math.toRadians(lat2))*cos(Math.toRadians(lat1)) *
                   sin(dLng/2)*sin(dLng/2)
        return 6371000.0 * 2 * atan2(sqrt(a), sqrt(1-a))
    }
}
