package com.intelligame.easteregghuntar

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

/**
 * Schermata di configurazione caccia.
 *
 * Modalita':
 *  - manual   → tocchi tu il pavimento per ogni uovo
 *  - auto     → ARCore scansiona e piazza le uova automaticamente
 *  - combined → auto + puoi aggiungerne manualmente
 *
 * Uova trappola:
 *  - N uova false miste a quelle vere (stesso aspetto)
 *  - Solo il genitore vede il marcatore rosso durante il setup
 *  - Se il bambino le prende: penalita' in secondi + animazione
 */
class EggSetupModeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SETUP_MODE     = "egg_setup_mode"
        const val EXTRA_AUTO_EGG_COUNT = "auto_egg_count"
        const val EXTRA_TRAP_EGG_COUNT = "trap_egg_count"
        const val EXTRA_PENALTY_SECS   = "penalty_seconds"
        const val EXTRA_AR_MODE        = "ar_mode"
    }

    private var selectedMode  = "manual"
    private var eggCount      = 4
    private var turnMode      = "sequential"

    private lateinit var cards:       Map<String, CardView>
    private lateinit var tvEggCount:  TextView
    private lateinit var autoSection: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val players   = intent.getStringArrayListExtra("players") ?: arrayListOf()
        val riddles   = intent.getStringArrayListExtra("riddles")
        turnMode      = intent.getStringExtra(GameModeActivity.EXTRA_TURN_MODE) ?: "sequential"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080E24"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(64), dp(24), dp(48))
        }
        scroll.addView(root)

        // ── Header ──────────────────────────────────────────────────────────
        root.addView(tv("\uD83D\uDC23", 52f, Color.WHITE, Gravity.CENTER))   // 🐣
        root.addView(tv("Configura la Caccia", 22f, Color.parseColor("#FFD700"),
            Gravity.CENTER, bold = true).also { it.setPadding(0, 0, 0, dp(4)) })
        root.addView(tv("Scegli come nascondere le uova e aggiungi le trappole",
            13f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER).also { it.setPadding(0, 0, 0, dp(28)) })

        // ── Modalita' ────────────────────────────────────────────────────────
        root.addView(sectionHeader("\uD83D\uDCCD Modalita' di piazzamento"))

        val modeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(modeContainer)

        val modeEntries = listOf(
            Triple("manual",   "\u270B  Manuale",      "Tocchi tu il pavimento per ogni uovo"),
            Triple("auto",     "\uD83E\uDD16  Auto",   "ARCore scansiona e nasconde le uova da solo"),
            Triple("combined", "\u270B+\uD83E\uDD16  Misto", "Auto + puoi aggiungerne altri a mano")
        )
        val cardMap = mutableMapOf<String, CardView>()
        modeEntries.forEach { (mode, label, desc) ->
            val card = buildModeCard(label, desc, mode == selectedMode)
            card.setOnClickListener { setMode(mode, cardMap) }
            cardMap[mode] = card
            modeContainer.addView(card)
            (card.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
        }
        cards = cardMap

        // ── Sezione Auto (numero uova) ───────────────────────────────────────
        autoSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (selectedMode == "manual") LinearLayout.GONE else LinearLayout.VISIBLE
        }
        root.addView(autoSection)

        autoSection.addView(sectionHeader("\uD83D\uDD22 Numero di uova"))
        val countRow = counterRow(
            getCount = { eggCount }, setCount = { eggCount = it },
            min = 1, max = 10, color = "#FF4CAF50"
        ) { tvEggCount = it }
        autoSection.addView(countRow)
        autoSection.addView(tv("(in modalita' Misto puoi aggiungerne altre a mano)",
            11f, Color.parseColor("#88FFFFFF")).also { it.setPadding(dp(4), 0, 0, dp(16)) })

        // ── Divisore ────────────────────────────────────────────────────────
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { lp -> lp.topMargin = dp(20); lp.bottomMargin = dp(16) }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        })

        // ── Bottone Avvia ────────────────────────────────────────────────────
        val btnLaunch = Button(this).apply {
            text = "\uD83D\uDC23  AVVIA CACCIA!"  // 🐣
            setTextColor(Color.parseColor("#0D1B3F"))
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textSize = 17f
            setBackgroundColor(Color.parseColor("#FF4CAF50"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
            ).also { it.topMargin = dp(4) }
            setOnClickListener { launchGame(players, riddles) }
        }
        root.addView(btnLaunch)

        setContentView(scroll)
    }

    // ─── Logica ─────────────────────────────────────────────────────────────

    private fun setMode(mode: String, map: Map<String, CardView>) {
        selectedMode = mode
        map.forEach { (m, card) ->
            card.setCardBackgroundColor(
                if (m == mode) Color.parseColor("#CC2E7D32")
                else           Color.parseColor("#CC1A237E")
            )
        }
        autoSection.visibility =
            if (mode == "manual") LinearLayout.GONE else LinearLayout.VISIBLE
    }

    private fun launchGame(players: ArrayList<String>, riddles: ArrayList<String>?) {
        val arMode = GameDataManager.get(this).getArMode()
        val intent = Intent(this, MainActivity::class.java).apply {
            putStringArrayListExtra("players", players)
            if (riddles != null) putStringArrayListExtra("riddles", riddles)
            putExtra(EXTRA_SETUP_MODE,     selectedMode)
            putExtra(EXTRA_AUTO_EGG_COUNT, eggCount)
            putExtra(EXTRA_AR_MODE,        arMode)
            putExtra(GameModeActivity.EXTRA_TURN_MODE, turnMode)
        }
        startActivity(intent)
    }

    // ─── UI helpers ─────────────────────────────────────────────────────────

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun buildModeCard(label: String, desc: String, selected: Boolean): CardView {
        val card = CardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.parseColor(if (selected) "#CC2E7D32" else "#CC1A237E"))
            cardElevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        col.addView(tv(label, 15f, Color.WHITE, bold = true))
        col.addView(tv(desc,  12f, Color.parseColor("#AAFFFFFF")))
        card.addView(col)
        return card
    }

    private fun counterRow(
        getCount: () -> Int, setCount: (Int) -> Unit,
        min: Int, max: Int, color: String,
        formatLabel: (Int) -> String = { it.toString() },
        tvRef: (TextView) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val tvVal = tv(formatLabel(getCount()), 26f, Color.WHITE, Gravity.CENTER, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvRef(tvVal)
        val btnM = roundBtn("-", color) {
            val v = getCount(); if (v > min) { setCount(v - 1); tvVal.text = formatLabel(getCount()) }
        }
        val btnP = roundBtn("+", color) {
            val v = getCount(); if (v < max) { setCount(v + 1); tvVal.text = formatLabel(getCount()) }
        }
        row.addView(btnM); row.addView(tvVal); row.addView(btnP)
        return row
    }

    private fun roundBtn(label: String, bgColor: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(bgColor))
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            setOnClickListener { action() }
        }

    private fun tv(
        text: String, size: Float, color: Int,
        gravity: Int = Gravity.START, bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        this.gravity = gravity
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
