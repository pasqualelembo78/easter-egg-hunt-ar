package com.intelligame.easteregghuntar

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.content.res.Resources
import kotlin.math.sin

/**
 * CharacterSpriteManager — gestisce il personaggio animato del giocatore sulla mappa.
 *
 * Il personaggio è disegnato interamente su Canvas (nessuna immagine esterna).
 * Animazione camminata: le gambe oscillano in modo sfasato basandosi su un tick globale.
 * Il cappello/colore cambiano in base al livello del giocatore.
 *
 * Personaggio base "Cacciatore di Uova":
 *  - Testa rotonda con occhi e sorriso
 *  - Corpo ovale
 *  - Due gambe che camminano (oscillazione sinusoidale)
 *  - Cestino nella mano destra
 *  - Cappello che scala col livello (colore diverso)
 *
 * Estendibile: in futuro aggiungere skin dal marketplace.
 */
object CharacterSpriteManager {

    /**
     * Genera il bitmap del personaggio animato.
     *
     * @param resources  Resources per BitmapDrawable
     * @param sizeDp     Dimensione totale del marker in dp
     * @param walkTick   Tick dell'animazione (0..63, incrementa ogni frame)
     * @param level      Livello del giocatore (cambia colore cappello)
     * @param facing     Direzione in gradi (0=Nord, 90=Est) — per specchiare il personaggio
     */
    fun makeCharacterDrawable(
        resources: Resources,
        sizeDp:    Int,
        walkTick:  Int,
        level:     Int,
        facing:    Float = 0f
    ): BitmapDrawable {
        val dp   = resources.displayMetrics.density
        val size = (sizeDp * dp).toInt()
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)

        val cx = size / 2f
        val scaleDp = size / 64f   // scala tutti gli elementi relativamente a 64px base

        // ── Colori basati sul livello ──────────────────────────────
        val hatColor = when {
            level >= 40 -> Color.parseColor("#FFD700")   // oro — leggenda
            level >= 30 -> Color.parseColor("#E040FB")   // viola — campione
            level >= 20 -> Color.parseColor("#FF5722")   // arancio — esperto
            level >= 10 -> Color.parseColor("#2196F3")   // blu — avanzato
            else        -> Color.parseColor("#4CAF50")   // verde — principiante
        }
        val skinColor  = Color.parseColor("#FFCC80")
        val shirtColor = Color.parseColor("#1565C0")
        val pantsColor = Color.parseColor("#37474F")
        val shoeColor  = Color.parseColor("#212121")

        // ── Animazione camminata ──────────────────────────────────
        val walkAngle = (walkTick * Math.PI / 16).toFloat()
        val legSwingL =  sin(walkAngle.toDouble()).toFloat() * 6f * scaleDp
        val legSwingR = -sin(walkAngle.toDouble()).toFloat() * 6f * scaleDp
        val bodyBob   = (sin(walkAngle.toDouble() * 2) * 1.5f * scaleDp).toFloat()

        // ── Specchia se va a destra (>= 45 && < 225) ──────────────
        val mirror = facing in 45f..225f
        if (mirror) {
            val matrix = Matrix()
            matrix.setScale(-1f, 1f, cx, size / 2f)
            c.concat(matrix)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── Ombra sotto ────────────────────────────────────────────
        paint.color = 0x33000000
        c.drawOval(cx - 14*scaleDp, size - 8*scaleDp,
                   cx + 14*scaleDp, size - 3*scaleDp, paint)

        // ── Gambe ──────────────────────────────────────────────────
        val legTop   = 38*scaleDp + bodyBob
        val legBot   = 58*scaleDp
        val legW     = 7*scaleDp

        // Gamba sinistra
        paint.color = pantsColor
        c.drawRoundRect(cx - 10*scaleDp + legSwingL, legTop,
                        cx - 3*scaleDp  + legSwingL, legBot,
                        3*scaleDp, 3*scaleDp, paint)
        // Scarpa sinistra
        paint.color = shoeColor
        c.drawRoundRect(cx - 11*scaleDp + legSwingL, legBot - 4*scaleDp,
                        cx - 2*scaleDp  + legSwingL, legBot + 1*scaleDp,
                        2*scaleDp, 2*scaleDp, paint)

        // Gamba destra
        paint.color = pantsColor
        c.drawRoundRect(cx + 3*scaleDp  + legSwingR, legTop,
                        cx + 10*scaleDp + legSwingR, legBot,
                        3*scaleDp, 3*scaleDp, paint)
        // Scarpa destra
        paint.color = shoeColor
        c.drawRoundRect(cx + 2*scaleDp  + legSwingR, legBot - 4*scaleDp,
                        cx + 11*scaleDp + legSwingR, legBot + 1*scaleDp,
                        2*scaleDp, 2*scaleDp, paint)

        // ── Corpo ──────────────────────────────────────────────────
        paint.color = shirtColor
        c.drawRoundRect(cx - 13*scaleDp, 28*scaleDp + bodyBob,
                        cx + 13*scaleDp, 44*scaleDp + bodyBob,
                        6*scaleDp, 6*scaleDp, paint)

        // ── Braccio destro (con cestino) ───────────────────────────
        paint.color = skinColor
        val armSwing = -legSwingL * 0.4f
        c.drawRoundRect(cx + 12*scaleDp + armSwing, 29*scaleDp + bodyBob,
                        cx + 19*scaleDp + armSwing, 40*scaleDp + bodyBob,
                        3*scaleDp, 3*scaleDp, paint)
        // Cestino
        paint.color = Color.parseColor("#8D6E63")
        c.drawRoundRect(cx + 15*scaleDp + armSwing, 36*scaleDp + bodyBob,
                        cx + 22*scaleDp + armSwing, 44*scaleDp + bodyBob,
                        2*scaleDp, 2*scaleDp, paint)
        paint.color = Color.parseColor("#6D4C41")
        paint.strokeWidth = 1.5f * scaleDp; paint.style = Paint.Style.STROKE
        c.drawLine(cx + 15*scaleDp + armSwing, 38*scaleDp + bodyBob,
                   cx + 22*scaleDp + armSwing, 38*scaleDp + bodyBob, paint)
        paint.style = Paint.Style.FILL

        // ── Braccio sinistro ───────────────────────────────────────
        paint.color = skinColor
        c.drawRoundRect(cx - 19*scaleDp - armSwing, 29*scaleDp + bodyBob,
                        cx - 12*scaleDp - armSwing, 40*scaleDp + bodyBob,
                        3*scaleDp, 3*scaleDp, paint)

        // ── Collo ──────────────────────────────────────────────────
        paint.color = skinColor
        c.drawRoundRect(cx - 4*scaleDp, 22*scaleDp + bodyBob,
                        cx + 4*scaleDp, 30*scaleDp + bodyBob,
                        3*scaleDp, 3*scaleDp, paint)

        // ── Testa ──────────────────────────────────────────────────
        paint.color = skinColor
        c.drawCircle(cx, 16*scaleDp + bodyBob, 14*scaleDp, paint)

        // ── Occhi ──────────────────────────────────────────────────
        paint.color = Color.parseColor("#212121")
        c.drawCircle(cx - 5*scaleDp, 14*scaleDp + bodyBob, 2.5f*scaleDp, paint)
        c.drawCircle(cx + 5*scaleDp, 14*scaleDp + bodyBob, 2.5f*scaleDp, paint)
        // Pupille bianche
        paint.color = Color.WHITE
        c.drawCircle(cx - 4.5f*scaleDp, 13.5f*scaleDp + bodyBob, 0.8f*scaleDp, paint)
        c.drawCircle(cx + 5.5f*scaleDp, 13.5f*scaleDp + bodyBob, 0.8f*scaleDp, paint)

        // ── Sorriso ────────────────────────────────────────────────
        paint.color = Color.parseColor("#E65100")
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f*scaleDp
        val smileRect = RectF(cx - 5*scaleDp, 16*scaleDp + bodyBob,
                              cx + 5*scaleDp, 22*scaleDp + bodyBob)
        c.drawArc(smileRect, 10f, 160f, false, paint)
        paint.style = Paint.Style.FILL

        // ── Cappello ──────────────────────────────────────────────
        paint.color = hatColor
        // Tesa
        c.drawRoundRect(cx - 16*scaleDp, 4*scaleDp + bodyBob,
                        cx + 16*scaleDp, 8*scaleDp + bodyBob,
                        2*scaleDp, 2*scaleDp, paint)
        // Corpo cappello
        c.drawRoundRect(cx - 10*scaleDp, -4*scaleDp + bodyBob,
                        cx + 10*scaleDp, 6*scaleDp + bodyBob,
                        3*scaleDp, 3*scaleDp, paint)
        // Nastro cappello (colore più scuro)
        paint.color = darken(hatColor, 0.6f)
        c.drawRect(cx - 10*scaleDp, 3*scaleDp + bodyBob,
                   cx + 10*scaleDp, 6*scaleDp + bodyBob, paint)
        // Stella sul cappello se livello alto
        if (level >= 20) {
            paint.color = Color.WHITE
            paint.textSize = 8*scaleDp; paint.textAlign = Paint.Align.CENTER
            val fm = paint.fontMetrics
            c.drawText("★", cx, 1*scaleDp + bodyBob - (fm.top+fm.bottom)/2, paint)
        }

        return BitmapDrawable(resources, bmp)
    }

    /** Scurisce un colore di un fattore (0-1) */
    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color)   * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color)  * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    // ── Selezione personaggio ─────────────────────────────────────

    data class CharacterSkin(
        val id:          String,
        val name:        String,
        val description: String,
        val price:       Int,   // 0 = gratis
        val isDefault:   Boolean = false
    )

    val availableSkins = listOf(
        CharacterSkin("base", "Cacciatore", "Il coraggioso cercatore di uova", 0, true),
        CharacterSkin("ninja", "Ninja", "Silenzioso come l'ombra", 500),
        CharacterSkin("mago",  "Mago",  "Maestro delle uova magiche", 750),
        CharacterSkin("pirata","Pirata","I tesori del mare sono suoi", 1000)
        // Altri verranno aggiunti nel marketplace
    )
}
