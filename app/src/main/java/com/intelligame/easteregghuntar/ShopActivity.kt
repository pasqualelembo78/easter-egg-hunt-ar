package com.intelligame.easteregghuntar

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class ShopActivity : AppCompatActivity() {

    private lateinit var dm: GameDataManager

    data class ShopItem(
        val id: String, val emoji: String, val name: String,
        val description: String, val price: String,
        val type: String, val isFree: Boolean = false
    )

    private val items = listOf(
        // ── Forme uova ───────────────────────────────────────────
        ShopItem("free_basic",    "🥚\uD83D\uDCE6\uD83D\uDEE2",  "Forme base",
            "Sfera, Cubo, Cilindro - gia\' incluse gratuitamente!",          "GRATIS",   "free", true),
        ShopItem("premium_eggs",  "\u2B50\uD83D\uDC8E\uD83C\uDF38\uD83D\uDC23",  "Forme Premium",
            "Sblocca: Diamante, Stella, Cubo colorato extra\nUova 3D uniche per la tua caccia!", "1,99", "egg_shapes"),

        // ── Cassaforti ───────────────────────────────────────────
        ShopItem("safe_chest",    "\uD83D\uDCE6", "Forziere del Tesoro",
            "Una bella cassa da pirata al posto della cassaforte metallica.\nColori caldi, stile avventura!", "0,99", "safe"),
        ShopItem("safe_vault",    "\uD83D\uDD10", "Vault Bancario",
            "Una porta blindata circolare stile banca.\nMassima sicurezza, massimo stile!", "0,99", "safe"),
        ShopItem("safe_present",  "\uD83C\uDF81", "Pacco Regalo",
            "Un regalo gigante con fiocco! Perfetto per Natale o compleanni.", "0,99", "safe"),

        // ── Bundle e altro ────────────────────────────────────────
        ShopItem("bundle_all",    "\uD83C\uDF81", "Pacchetto Completo",
            "Tutto il contenuto premium:\n- Forme speciali\n- Tutte le cassaforti\nRisparmia il 40%!", "3,49", "bundle"),
        ShopItem("premium_colors","\uD83C\uDF08\u2728", "Colori Speciali",
            "Sblocca: Arcobaleno, Glitter Oro, Glitter Argento", "0,99", "egg_colors"),
        ShopItem("restore",       "\uD83D\uDD04", "Ripristina acquisti",
            "Hai gia\' acquistato su un altro dispositivo? Ripristina.", "GRATIS", "restore", true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dm = GameDataManager.get(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(64), dp(24), dp(40))
        }
        scroll.addView(root)

        root.addView(tv("\uD83C\uDFEA Negozio", 22f, Color.parseColor("#FFD700"), bold = true)
            .also { it.setPadding(0,0,0,dp(4)) })
        root.addView(tv("Personalizza la tua caccia con oggetti speciali",
            13f, Color.parseColor("#AAFFFFFF")).also { it.setPadding(0,0,0,dp(24)) })

        items.forEach { root.addView(buildCard(it)) }

        root.addView(tv("Nota: il negozio e\' in versione beta. Gli acquisti reali saranno " +
            "attivi sulla versione Play Store.",
            11f, Color.parseColor("#66FFFFFF")).also { it.setPadding(0,dp(16),0,0) })

        setContentView(scroll)
    }

    private fun buildCard(item: ShopItem): CardView {
        val isPurchased = when (item.id) {
            "free_basic"     -> true
            "premium_eggs"   -> dm.isPremiumEggs()
            "premium_colors" -> dm.isPremiumColors()
            "safe_chest"     -> dm.getUnlockedSafes().contains("chest")
            "safe_vault"     -> dm.getUnlockedSafes().contains("vault")
            "safe_present"   -> dm.getUnlockedSafes().contains("present")
            "bundle_all"     -> dm.isPremiumEggs() && dm.isPremiumColors() &&
                                dm.getUnlockedSafes().containsAll(listOf("chest","vault","present"))
            else -> false
        }
        val bg = when {
            isPurchased       -> "#CC006064"
            item.id == "bundle_all" -> "#CCFF6F00"
            item.isFree       -> "#CC333333"
            else              -> "#CC1A237E"
        }
        val card = CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = dp(5).toFloat()
            setCardBackgroundColor(Color.parseColor(bg))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        // Header
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv(item.emoji, 26f, Color.WHITE, Gravity.CENTER).also {
            it.layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10),0,0,0)
        }
        nameCol.addView(tv(item.name, 15f, Color.WHITE, bold = true))
        nameCol.addView(tv(
            if (isPurchased) "\u2705 Acquistato" else item.price,
            13f,
            if (isPurchased) Color.parseColor("#66FF99") else Color.parseColor("#FFD700")
        ))
        row.addView(nameCol); col.addView(row)
        col.addView(tv(item.description, 12f, Color.parseColor("#CCFFFFFF"))
            .also { it.setPadding(0,dp(6),0,dp(10)) })

        if (!isPurchased && !item.isFree) {
            col.addView(Button(this).apply {
                text = "\uD83D\uDED2  ACQUISTA ${item.price} \u20AC"
                setTextColor(Color.BLACK)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                textSize = 13f; setBackgroundColor(Color.parseColor("#FFD700"))
                setOnClickListener { showPurchaseDialog(item) }
            })
        } else if (item.id == "restore") {
            col.addView(Button(this).apply {
                text = "\uD83D\uDD04  Ripristina"
                setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#CC0277BD"))
                textSize = 12f; setOnClickListener {
                    Toast.makeText(this@ShopActivity, "Ripristino... (attivo su Play Store)", Toast.LENGTH_LONG).show()
                }
            })
        }
        card.addView(col); return card
    }

    private fun showPurchaseDialog(item: ShopItem) {
        AlertDialog.Builder(this)
            .setTitle("Acquista ${item.name}")
            .setMessage("${item.description}\n\nPrezzo: ${item.price} euro\n\nAcquisto simulato per test - la versione finale usera\' Google Play Billing.\nVuoi sbloccare per test?")
            .setPositiveButton("Sblocca (test)") { _, _ ->
                when (item.id) {
                    "premium_eggs"   -> { dm.unlockPremiumEggs(); dm.unlockShape("diamond"); dm.unlockShape("star") }
                    "premium_colors" -> dm.unlockPremiumColors()
                    "safe_chest"     -> dm.unlockSafe("chest")
                    "safe_vault"     -> dm.unlockSafe("vault")
                    "safe_present"   -> dm.unlockSafe("present")
                    "bundle_all"     -> {
                        dm.unlockPremiumEggs(); dm.unlockPremiumColors()
                        dm.unlockShape("diamond"); dm.unlockShape("star")
                        dm.unlockSafe("chest"); dm.unlockSafe("vault"); dm.unlockSafe("present")
                    }
                }
                Toast.makeText(this, "\u2705 ${item.name} sbloccato!", Toast.LENGTH_LONG).show()
                recreate()
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun tv(text: String, size: Float, color: Int,
                   gravity: Int = Gravity.START, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); this.gravity = gravity
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
