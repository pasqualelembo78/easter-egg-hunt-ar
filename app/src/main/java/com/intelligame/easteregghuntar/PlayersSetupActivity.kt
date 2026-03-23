package com.intelligame.easteregghuntar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

/**
 * Doppio uso:
 *  mode="manage"         → gestisci i giocatori (aggiungi/rimuovi)
 *  mode="select_for_game" → scegli quali giocatori partecipano e avvia la caccia
 */
class PlayersSetupActivity : AppCompatActivity() {

    private lateinit var dm: GameDataManager
    private lateinit var playersContainer: LinearLayout
    private lateinit var mode: String
    private val selectedPlayers = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dm = GameDataManager.get(this)
        mode = intent.getStringExtra("mode") ?: "manage"

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 72, 40, 40)
        }
        scroll.addView(root)

        // Titolo
        root.addView(TextView(this).apply {
            text = if (mode == "manage") "👥 Gestione Giocatori" else "👧 Chi gioca oggi?"
            textSize = 22f
            setTextColor(Color.parseColor("#FFD700"))
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = if (mode == "manage")
                "Aggiungi o rimuovi giocatori. Le statistiche sono separate per ognuno."
            else
                "Seleziona chi partecipa a questa caccia. Le statistiche vengono salvate separatamente."
            textSize = 13f
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setPadding(0, 0, 0, 24)
        })

        // Lista giocatori
        playersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }
        root.addView(playersContainer)

        // Pulsante aggiungi
        root.addView(buildAddButton())

        if (mode == "select_for_game") {
            // Pulsante avvia caccia
            val btnPlay = buildActionButton("🐣  AVVIA CACCIA!", "#FF4CAF50")
            btnPlay.setOnClickListener { launchGame() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (60 * resources.displayMetrics.density).toInt())
            lp.topMargin = 24
            root.addView(btnPlay, lp)
        }

        setContentView(scroll)
        refreshList()
    }

    private fun refreshList() {
        playersContainer.removeAllViews()
        val dp = resources.displayMetrics.density
        dm.getPlayers().forEach { player ->
            val card = CardView(this).apply {
                radius = 14 * dp
                setCardBackgroundColor(Color.parseColor("#CC1A237E"))
                cardElevation = 4 * dp
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * dp).toInt())
                lp.bottomMargin = (10 * dp).toInt()
                layoutParams = lp
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            }

            if (mode == "select_for_game") {
                val cb = CheckBox(this).apply {
                    isChecked = selectedPlayers.contains(player.name)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedPlayers.add(player.name)
                        else selectedPlayers.remove(player.name)
                    }
                }
                row.addView(cb)
            } else {
                row.addView(TextView(this).apply { text = "👧"; textSize = 22f; gravity = android.view.Gravity.CENTER; layoutParams = LinearLayout.LayoutParams((40*dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT) })
            }

            row.addView(TextView(this).apply {
                text = player.name
                textSize = 17f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding((12*dp).toInt(), 0, 0, 0)
            })

            // Pulsante elimina (solo in manage)
            if (mode == "manage") {
                val btnDel = TextView(this).apply {
                    text = "🗑"
                    textSize = 20f
                    setPadding((8*dp).toInt(), 0, (8*dp).toInt(), 0)
                    isClickable = true
                    setOnClickListener { confirmDelete(player.name) }
                }
                row.addView(btnDel)
            }

            card.addView(row)
            playersContainer.addView(card)
        }
    }

    private fun buildAddButton(): android.widget.Button {
        return android.widget.Button(this).apply {
            text = "➕  Aggiungi giocatore"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC0277BD"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (52 * resources.displayMetrics.density).toInt())
            setOnClickListener { showAddPlayerDialog() }
        }
    }

    private fun buildActionButton(label: String, color: String): android.widget.Button {
        return android.widget.Button(this).apply {
            text = label
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            textSize = 16f
            setBackgroundColor(Color.parseColor(color))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (60 * resources.displayMetrics.density).toInt())
        }
    }

    private fun showAddPlayerDialog() {
        val et = EditText(this).apply {
            hint = "Nome giocatore"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Nuovo giocatore")
            .setView(et)
            .setPositiveButton("Aggiungi") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    dm.addPlayer(name)
                    refreshList()
                    Toast.makeText(this, "👧 $name aggiunto!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Elimina $name?")
            .setMessage("Le statistiche di $name rimarranno nello storico.")
            .setPositiveButton("Elimina") { _, _ ->
                dm.removePlayer(name)
                selectedPlayers.remove(name)
                refreshList()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun launchGame() {
        if (selectedPlayers.isEmpty()) {
            Toast.makeText(this, "Seleziona almeno un giocatore!", Toast.LENGTH_SHORT).show()
            return
        }
        // Passa per GameModeActivity (turni) -> EggSetupModeActivity (uova)
        val intent = Intent(this, GameModeActivity::class.java).apply {
            putStringArrayListExtra("players", ArrayList(selectedPlayers))
        }
        startActivity(intent)
    }
}
