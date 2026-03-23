package com.intelligame.easteregghuntar

/**
 * WorldEgg — uovo del mondo aperto (non legato a una stanza specifica).
 *
 * A differenza di OutdoorEgg (stanze multiplayer), le WorldEgg:
 *  - Hanno una rarità
 *  - Spariscono dopo ttlMinutes (TTL)
 *  - Possono essere catturate DA UNO SOLO (first-catch)
 *  - Danno XP e potere al giocatore che le cattura
 *  - Vengono spawnate automaticamente vicino al GPS del giocatore
 *
 * Stored in Firestore: world_eggs/{eggId}
 */
data class WorldEgg(
    val id:           String    = "",
    val lat:          Double    = 0.0,
    val lng:          Double    = 0.0,
    val rarity:       EggRarity = EggRarity.COMMON,
    val fantasyName:  String    = "",           // nome fantasy assegnato allo spawn
    val spawnedAt:    Long      = System.currentTimeMillis(),
    val expiresAt:    Long      = System.currentTimeMillis() + 60 * 60_000L,
    val caught:       Boolean   = false,
    val caughtBy:     String    = "",
    val caughtByName: String    = "",
    val caughtAt:     Long      = 0L
) {
    val isExpired get() = System.currentTimeMillis() > expiresAt

    /** Nome visualizzato: usa fantasyName se disponibile, altrimenti displayName rarità */
    val displayLabel: String get() = fantasyName.ifBlank { rarity.displayName }

    fun distanceTo(lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat - lat2)
        val dLng = Math.toRadians(lng - lng2)
        val a = Math.sin(dLat/2)*Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat2))*Math.cos(Math.toRadians(lat)) *
                Math.sin(dLng/2)*Math.sin(dLng/2)
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
    }

    fun toFirestore(): Map<String, Any> = mapOf(
        "id"           to id,
        "lat"          to lat,
        "lng"          to lng,
        "rarityId"     to rarity.id,
        "fantasyName"  to fantasyName,
        "spawnedAt"    to spawnedAt,
        "expiresAt"    to expiresAt,
        "caught"       to caught,
        "caughtBy"     to caughtBy,
        "caughtByName" to caughtByName,
        "caughtAt"     to caughtAt
    )

    companion object {
        fun fromFirestore(map: Map<String, Any?>): WorldEgg? {
            val id = map["id"] as? String ?: return null
            val rarity = EggRarity.fromId(map["rarityId"] as? String ?: "common")
            return WorldEgg(
                id           = id,
                lat          = (map["lat"] as? Double) ?: return null,
                lng          = (map["lng"] as? Double) ?: return null,
                rarity       = rarity,
                fantasyName  = map["fantasyName"] as? String ?: rarity.randomName(),
                spawnedAt    = (map["spawnedAt"] as? Long) ?: 0L,
                expiresAt    = (map["expiresAt"] as? Long) ?: 0L,
                caught       = map["caught"] as? Boolean ?: false,
                caughtBy     = map["caughtBy"] as? String ?: "",
                caughtByName = map["caughtByName"] as? String ?: "",
                caughtAt     = (map["caughtAt"] as? Long) ?: 0L
            )
        }
    }
}
