package com.intelligame.easteregghuntar

import android.graphics.Color

/**
 * EggRarity — sistema di rarità uova outdoor, stile Pokémon GO.
 *
 * Ogni rarità ha:
 *  - Probabilità di spawn (peso relativo)
 *  - Potere base trasferito al giocatore alla cattura
 *  - XP guadagnati alla cattura
 *  - Difficoltà cattura (raggio del target nel mini-gioco 0-1)
 *  - TTL in minuti
 *  - Nome fantasy unico assegnato a ogni uovo al momento dello spawn
 *  - Raggio di azione: 70m (uguale a Pokémon GO) per TUTTE le rarità
 */
enum class EggRarity(
    val id:            String,
    val displayName:   String,
    val emoji:         String,
    val colorHex:      String,
    val glowColorHex:  String,
    val basePower:     Int,
    val xpReward:      Int,
    val spawnWeight:   Int,
    val catchRadius:   Float,
    val catchSpeed:    Float,
    val ttlMinutes:    Int,
    val namePool:      List<String>
) {
    COMMON(
        id           = "common",
        displayName  = "Ordinario",
        emoji        = "🥚",
        colorHex     = "#4CAF50",
        glowColorHex = "#81C784",
        basePower    = 10,
        xpReward     = 25,
        spawnWeight  = 55,
        catchRadius  = 0.75f,
        catchSpeed   = 0.20f,
        ttlMinutes   = 60,
        namePool     = listOf(
            "Uovo di Quarzio", "Uovo di Muschio", "Uovo di Ciottolo",
            "Uovo della Bruma", "Uovo di Felce", "Uovo di Lichene",
            "Uovo di Torba", "Uovo di Fieno", "Uovo del Ruscello",
            "Uovo di Argilla", "Uovo di Granito", "Uovo di Ghiaia"
        )
    ),
    UNCOMMON(
        id           = "uncommon",
        displayName  = "Insolito",
        emoji        = "🔵",
        colorHex     = "#2196F3",
        glowColorHex = "#64B5F6",
        basePower    = 35,
        xpReward     = 80,
        spawnWeight  = 25,
        catchRadius  = 0.60f,
        catchSpeed   = 0.35f,
        ttlMinutes   = 45,
        namePool     = listOf(
            "Uovo di Cristallo", "Uovo di Gelsomino", "Uovo di Nebbia Azzurra",
            "Uovo della Rugiada", "Uovo dell'Alba Grigia", "Uovo di Zaffiro Tenue",
            "Uovo di Vento Salato", "Uovo di Mare Calmo", "Uovo del Crepuscolo"
        )
    ),
    RARE(
        id           = "rare",
        displayName  = "Raro",
        emoji        = "💜",
        colorHex     = "#9C27B0",
        glowColorHex = "#CE93D8",
        basePower    = 100,
        xpReward     = 250,
        spawnWeight  = 12,
        catchRadius  = 0.45f,
        catchSpeed   = 0.55f,
        ttlMinutes   = 30,
        namePool     = listOf(
            "Uovo di Selene", "Uovo della Tempesta Viola", "Uovo di Smeraldo Antico",
            "Uovo del Tramonto Cremisi", "Uovo dell'Ombra Argentata",
            "Uovo del Vento Magico", "Uovo del Bosco Incantato",
            "Uovo di Ametista", "Uovo del Fulmine Silente"
        )
    ),
    EPIC(
        id           = "epic",
        displayName  = "Epico",
        emoji        = "🔥",
        colorHex     = "#FF5722",
        glowColorHex = "#FF8A65",
        basePower    = 300,
        xpReward     = 750,
        spawnWeight  = 6,
        catchRadius  = 0.30f,
        catchSpeed   = 0.70f,
        ttlMinutes   = 20,
        namePool     = listOf(
            "Uovo del Drago Antico", "Uovo del Vulcano", "Uovo dell'Abisso Ardente",
            "Uovo della Fenice", "Uovo del Tuono Primordiale",
            "Uovo del Leviatano", "Uovo della Fiamma Eterna"
        )
    ),
    LEGENDARY(
        id           = "legendary",
        displayName  = "Leggendario",
        emoji        = "⭐",
        colorHex     = "#FFD700",
        glowColorHex = "#FFF176",
        basePower    = 1000,
        xpReward     = 2500,
        spawnWeight  = 2,
        catchRadius  = 0.15f,
        catchSpeed   = 0.90f,
        ttlMinutes   = 15,
        namePool     = listOf(
            "Uovo del Cosmo", "Uovo dell'Aurora Boreale",
            "Uovo del Tempo Primordiale", "Uovo del Grande Spirito",
            "Uovo della Creazione", "Uovo dell'Infinito"
        )
    );

    val color: Int get() = Color.parseColor(colorHex)
    val glowColor: Int get() = Color.parseColor(glowColorHex)

    /** Raggio di azione — 70m come Pokémon GO, uguale per tutte le rarità */
    val actionRadiusM: Double get() = 70.0

    /** Seleziona un nome fantasy casuale da assegnare all'uovo allo spawn */
    fun randomName(): String = namePool.random()

    companion object {
        fun weightedRandom(): EggRarity {
            val total = values().sumOf { it.spawnWeight }
            var roll  = (Math.random() * total).toInt()
            for (rarity in values()) {
                roll -= rarity.spawnWeight
                if (roll < 0) return rarity
            }
            return COMMON
        }

        fun fromId(id: String): EggRarity =
            values().firstOrNull { it.id == id } ?: COMMON
    }
}
