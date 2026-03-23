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
 * Schermata 2 del flusso di avvio caccia.
 * Scelte:
 *  1) Modalita' di turno: Sequenziale (tutta la caccia per giocatore) o
 *     Alternata (un uovo a testa, poi si cambia)
 *  2) Chi inizia per primo (trascina o tocca un nome)
 */
class GameModeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TURN_MODE        = "turn_mode"      // "sequential" | "alternating"
        const val EXTRA_ORDERED_PLAYERS  = "ordered_players"
    }

    private var turnMode  = "sequential"
    private lateinit var players: MutableList<String>
    private lateinit var modeCards: Map<String, CardView>
    private lateinit var playersContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        players = intent.getStringArrayListExtra("players")?.toMutableList() ?: mutableListOf()
        val riddles = intent.getStringArrayListExtra("riddles")

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(64), dp(24), dp(48))
        }
        scroll.addView(root)

        // ── Header ────────────────────────────────────────────────────────
        root.addView(tv("\uD83C\uDFC6", 48f, Color.WHITE, Gravity.CENTER))
        root.addView(tv("Modalita' di gioco", 22f, Color.parseColor("#FFD700"),
            Gravity.CENTER, bold = true).also { it.setPadding(0,4,0,4) })
        root.addView(tv("Scegli come si alternano i giocatori",
            13f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER).also { it.setPadding(0,0,0,dp(28)) })

        // ── Modalita' turni ───────────────────────────────────────────────
        root.addView(sec("Tipo di caccia"))
        val cardMap = mutableMapOf<String, CardView>()

        val modes = listOf(
            Triple("sequential",  "\uD83E\uDDD1 Sequenziale",
                "Ogni giocatore completa TUTTA la caccia. Poi tocca al prossimo."),
            Triple("alternating", "\uD83D\uDD04 Alternata",
                "Un uovo a testa, poi si passa. Chi finisce prima vince!")
        )
        val modeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(modeBox)
        modes.forEach { (mode, label, desc) ->
            val c = modeCard(label, desc, mode == turnMode)
            c.setOnClickListener { setMode(mode, cardMap) }
            cardMap[mode] = c
            modeBox.addView(c)
            (c.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
        }
        modeCards = cardMap

        // ── Ordine giocatori ─────────────────────────────────────────────
        root.addView(sec("Chi inizia per primo?"))
        root.addView(tv("Tocca un nome per metterlo primo in lista",
            12f, Color.parseColor("#AAFFFFFF")).also { it.setPadding(0,0,0,dp(10)) })

        playersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(playersContainer)
        refreshPlayerOrder()

        // ── Separatore ────────────────────────────────────────────────────
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.topMargin = dp(20); it.bottomMargin = dp(16) }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        })

        // ── Bottone avanti ────────────────────────────────────────────────
        val btnNext = Button(this).apply {
            text = "AVANTI: CONFIGURA LE UOVA"
            setTextColor(Color.parseColor("#0D1B3F"))
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textSize = 15f
            setBackgroundColor(Color.parseColor("#FF4CAF50"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60))
            setOnClickListener { goNext(riddles) }
        }
        root.addView(btnNext)
        setContentView(scroll)
    }

    private fun setMode(mode: String, map: Map<String, CardView>) {
        turnMode = mode
        map.forEach { (m, c) ->
            c.setCardBackgroundColor(
                if (m == mode) Color.parseColor("#CC2E7D32")
                else           Color.parseColor("#CC1A237E")
            )
        }
    }

    private fun refreshPlayerOrder() {
        playersContainer.removeAllViews()
        players.forEachIndexed { idx, name ->
            val card = CardView(this).apply {
                radius = dp(12).toFloat()
                setCardBackgroundColor(
                    if (idx == 0) Color.parseColor("#CC2E7D32")
                    else          Color.parseColor("#CC1A237E")
                )
                cardElevation = dp(3).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
                ).also { it.bottomMargin = dp(8) }
                setOnClickListener { makePrimary(idx) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
            }
            val badge = if (idx == 0) "\uD83E\uDD47" else "${'①' + idx - 1}"
            row.addView(tv(badge, 20f, Color.WHITE).also {
                it.layoutParams = LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(tv(name, 16f, Color.WHITE, bold = true).also {
                it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                it.setPadding(dp(10), 0, 0, 0)
            })
            if (idx == 0) {
                row.addView(tv("PRIMA", 11f, Color.parseColor("#66FF99")))
            } else {
                row.addView(tv("tocca per mettere primo", 10f, Color.parseColor("#66FFFFFF")))
            }
            card.addView(row)
            playersContainer.addView(card)
        }
    }

    private fun makePrimary(idx: Int) {
        if (idx == 0) return
        val name = players.removeAt(idx)
        players.add(0, name)
        refreshPlayerOrder()
    }

    private fun goNext(riddles: ArrayList<String>?) {
        val intent = Intent(this, EggSetupModeActivity::class.java).apply {
            putStringArrayListExtra("players", ArrayList(players))
            if (riddles != null) putStringArrayListExtra("riddles", riddles)
            putExtra(EXTRA_TURN_MODE, turnMode)
        }
        startActivity(intent)
    }

    // ─── UI helpers ──────────────────────────────────────────────
    private fun sec(text: String) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun modeCard(label: String, desc: String, selected: Boolean): CardView {
        val card = CardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.parseColor(if (selected) "#CC2E7D32" else "#CC1A237E"))
            cardElevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        col.addView(tv(label, 15f, Color.WHITE, bold = true))
        col.addView(tv(desc,  12f, Color.parseColor("#AAFFFFFF")))
        card.addView(col)
        return card
    }

    private fun tv(
        text: String, size: Float, color: Int,
        gravity: Int = Gravity.START, bold: Boolean = false
    ) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); this.gravity = gravity
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
