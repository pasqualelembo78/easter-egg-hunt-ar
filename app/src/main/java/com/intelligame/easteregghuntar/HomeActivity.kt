package com.intelligame.easteregghuntar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

/**
 * Menu principale — fulcro dell'app.
 * Voci: Gioca, Impostazioni, Statistiche, Negozio, Aiuto, Privacy, T&C, Salva/Carica
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080E24"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 60)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(root)

        // Logo/header
        root.addView(TextView(this).apply {
            text = "🐰"
            textSize = 64f
            gravity = android.view.Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Easter Egg Hunt AR"
            textSize = 26f
            setTextColor(Color.parseColor("#FFD700"))
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 48)
        })

        // Elenco voci menu — ogni riga: emoji, label, descrizione, colore bg, destinazione
        val items = listOf(
            MenuItem("🐣", "GIOCA", "Caccia indoor con AR — gioco normale", "#FF4CAF50", ::goPlay),
            MenuItem("🌍", "OUTDOOR", "Caccia nel mondo reale — GPS, mappa, multiplayer", "#FF2E7D32", ::goOutdoor),
            MenuItem("👤", "Il Mio Profilo", "XP, livello, potere, classifica mondiale", "#CC6A1B9A", ::goProfile),
            MenuItem("👥", "Giocatori", "Scegli chi partecipa e i nomi", "#CC1565C0", ::goPlayers),
            MenuItem("⚙️", "Impostazioni", "Difficolta', suoni, vibrazioni", "#CC333333", ::goSettings),
            MenuItem("📊", "Statistiche", "Tempi e record di ogni partita", "#CC7B1FA2", ::goStats),
            MenuItem("💾", "Salva partita", "Salva la configurazione corrente", "#CC006064", ::goSave),
            MenuItem("📂", "Carica partita", "Riprendi da dove avevi lasciato", "#CC1A237E", ::goLoad),
            MenuItem("🏪", "Negozio", "Nuove forme, colori speciali", "#CCFF6F00", ::goShop),
            MenuItem("❓", "Aiuto", "Come si gioca", "#CC0277BD", ::goHelp),
            MenuItem("🔒", "Privacy Policy", "Informativa sulla privacy", "#CC37474F", ::goPrivacy),
            MenuItem("📋", "Termini e Condizioni", "Regole d'uso dell'app", "#CC37474F", ::goTerms)
        )

        items.forEach { item ->
            val card = buildMenuCard(item)
            root.addView(card)
            val lp = card.layoutParams as LinearLayout.LayoutParams
            lp.bottomMargin = 14
        }

        setContentView(scroll)
    }

    private data class MenuItem(
        val emoji: String,
        val label: String,
        val subtitle: String,
        val bgColor: String,
        val action: () -> Unit
    )

    private fun buildMenuCard(item: MenuItem): CardView {
        val dp = resources.displayMetrics.density
        val card = CardView(this).apply {
            radius = 18 * dp
            setCardBackgroundColor(Color.parseColor(item.bgColor))
            cardElevation = 6 * dp
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (72 * dp).toInt()
            )
            setOnClickListener { item.action() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
        }
        row.addView(TextView(this).apply {
            text = item.emoji
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding((12 * dp).toInt(), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = item.label
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = item.subtitle
            textSize = 12f
            setTextColor(Color.parseColor("#CCFFFFFF"))
        })
        row.addView(texts)
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(Color.parseColor("#88FFFFFF"))
        })
        card.addView(row)
        return card
    }

    private fun goPlay() {
        val dm = GameDataManager.get(this)
        val players = dm.getPlayers()
        if (players.isEmpty()) {
            goPlayers(); return
        }
        startActivity(Intent(this, PlayersSetupActivity::class.java)
            .putExtra("mode", "select_for_game"))
    }

    private fun goOutdoor()  = startActivity(Intent(this, OutdoorModeActivity::class.java))
    private fun goProfile()  = startActivity(Intent(this, PlayerProfileActivity::class.java))

    private fun goPlayers() {
        startActivity(Intent(this, PlayersSetupActivity::class.java)
            .putExtra("mode", "manage"))
    }

    private fun goSettings()  = startActivity(Intent(this, SettingsActivity::class.java))
    private fun goStats()     = startActivity(Intent(this, StatsActivity::class.java))
    private fun goShop()      = startActivity(Intent(this, ShopActivity::class.java))
    private fun goHelp()      = startActivity(Intent(this, HelpActivity::class.java))
    private fun goPrivacy()   = startActivity(Intent(this, LegalActivity::class.java).putExtra("mode", "privacy"))
    private fun goTerms()     = startActivity(Intent(this, LegalActivity::class.java).putExtra("mode", "terms"))

    private fun goSave() {
        val dm = GameDataManager.get(this)
        if (!dm.hasSavedSession()) {
            android.widget.Toast.makeText(this, "Nessuna partita in corso da salvare.\nPrima avvia una caccia!", android.widget.Toast.LENGTH_LONG).show()
        } else {
            val s = dm.loadSession()
            if (s != null) {
                android.widget.Toast.makeText(this, "✅ Partita gia' salvata!\nSalvata il: ${s.savedAt}\n${s.eggCount} uova · ${s.players.size} giocatori", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goLoad() {
        startActivity(Intent(this, SaveSlotActivity::class.java))
    }
}
