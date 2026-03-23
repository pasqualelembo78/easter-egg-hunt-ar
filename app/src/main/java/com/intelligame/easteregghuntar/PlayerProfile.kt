package com.intelligame.easteregghuntar

/**
 * PlayerProfile — profilo persistente del giocatore nel mondo outdoor.
 *
 * Stored in Firestore: players/{playerId}
 * Il profilo dura per sempre (finché il giocatore non lo elimina).
 *
 * Sistema di progressione:
 *  XP → determina il Livello
 *  Livello → sblocca privilegi (palestre più forti, uova più rare)
 *  Potere → si accumula catturando uova (basePower di ogni uovo)
 *            usato per le sfide in palestra
 */
data class PlayerProfile(
    val playerId:        String = "",
    val name:            String = "",
    var xp:              Long   = 0L,
    var power:           Long   = 0L,
    var eggsFound:       Int    = 0,
    var commonFound:     Int    = 0,
    var uncommonFound:   Int    = 0,
    var rareFound:       Int    = 0,
    var epicFound:       Int    = 0,
    var legendaryFound:  Int    = 0,
    var gymsVisited:     Int    = 0,
    var gymTrainings:    Int    = 0,
    val createdAt:       Long   = System.currentTimeMillis(),
    var lastSeen:        Long   = System.currentTimeMillis()
) {
    // ─── Livello calcolato da XP ───────────────────────────────
    // Livello 1: 0 XP, Livello N: somma di (i*150) per i=1..N-1
    // Livello 2: 150, Livello 3: 450, Livello 5: 1500, Livello 10: 6750, ...
    val level: Int get() {
        var lv = 1; var required = 0L
        while (xp >= required + lv * 150L) { required += lv * 150L; lv++ }
        return lv
    }

    val xpForCurrentLevel: Long get() {
        var required = 0L
        for (i in 1 until level) required += i * 150L
        return required
    }

    val xpForNextLevel: Long get() {
        var required = 0L
        for (i in 1..level) required += i * 150L
        return required
    }

    val xpProgressInLevel: Long get() = xp - xpForCurrentLevel
    val xpNeededForNextLevel: Long get() = xpForNextLevel - xpForCurrentLevel
    val levelProgressPercent: Int get() =
        ((xpProgressInLevel.toFloat() / xpNeededForNextLevel) * 100).toInt().coerceIn(0, 100)

    /** Titolo basato sul livello */
    val title: String get() = when {
        level >= 50 -> "🐉 Gran Maestro"
        level >= 40 -> "⭐ Leggenda"
        level >= 30 -> "🔥 Campione"
        level >= 20 -> "💎 Esperto"
        level >= 15 -> "🥇 Veterano"
        level >= 10 -> "🏅 Avanzato"
        level >= 5  -> "🟢 Intermedio"
        else        -> "🐣 Principiante"
    }

    /** Aggiunge XP e potere dopo la cattura di un uovo */
    fun addEggReward(rarity: EggRarity) {
        xp    += rarity.xpReward
        power += rarity.basePower
        eggsFound++
        when (rarity) {
            EggRarity.COMMON    -> commonFound++
            EggRarity.UNCOMMON  -> uncommonFound++
            EggRarity.RARE      -> rareFound++
            EggRarity.EPIC      -> epicFound++
            EggRarity.LEGENDARY -> legendaryFound++
        }
        lastSeen = System.currentTimeMillis()
    }

    /** Aggiunge potere dopo un allenamento in palestra */
    fun addTrainingReward(powerGained: Long, xpGained: Long) {
        power += powerGained
        xp    += xpGained
        gymTrainings++
        lastSeen = System.currentTimeMillis()
    }

    fun toFirestore(): Map<String, Any> = mapOf(
        "playerId"       to playerId,
        "name"           to name,
        "xp"             to xp,
        "power"          to power,
        "eggsFound"      to eggsFound,
        "commonFound"    to commonFound,
        "uncommonFound"  to uncommonFound,
        "rareFound"      to rareFound,
        "epicFound"      to epicFound,
        "legendaryFound" to legendaryFound,
        "gymsVisited"    to gymsVisited,
        "gymTrainings"   to gymTrainings,
        "createdAt"      to createdAt,
        "lastSeen"       to lastSeen,
        "level"          to level  // denormalizzato per query leaderboard
    )

    companion object {
        fun fromFirestore(map: Map<String, Any?>): PlayerProfile? {
            val id = map["playerId"] as? String ?: return null
            return PlayerProfile(
                playerId       = id,
                name           = map["name"] as? String ?: "?",
                xp             = (map["xp"] as? Long) ?: 0L,
                power          = (map["power"] as? Long) ?: 0L,
                eggsFound      = (map["eggsFound"] as? Long)?.toInt() ?: 0,
                commonFound    = (map["commonFound"] as? Long)?.toInt() ?: 0,
                uncommonFound  = (map["uncommonFound"] as? Long)?.toInt() ?: 0,
                rareFound      = (map["rareFound"] as? Long)?.toInt() ?: 0,
                epicFound      = (map["epicFound"] as? Long)?.toInt() ?: 0,
                legendaryFound = (map["legendaryFound"] as? Long)?.toInt() ?: 0,
                gymsVisited    = (map["gymsVisited"] as? Long)?.toInt() ?: 0,
                gymTrainings   = (map["gymTrainings"] as? Long)?.toInt() ?: 0,
                createdAt      = (map["createdAt"] as? Long) ?: System.currentTimeMillis(),
                lastSeen       = (map["lastSeen"] as? Long) ?: System.currentTimeMillis()
            )
        }

        /** ID stabile per un giocatore, basato su nome + device ID */
        fun generateId(name: String): String {
            val clean = name.lowercase().replace(Regex("[^a-z0-9]"), "")
            return "p_${clean}_${System.currentTimeMillis() % 100000}"
        }
    }
}
