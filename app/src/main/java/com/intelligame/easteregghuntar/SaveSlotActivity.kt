package com.intelligame.easteregghuntar

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

/**
 * Gestione slot di salvataggio multipli.
 * Mostra tutti gli slot salvati. Tap = carica. Pulsante elimina = cancella.
 */
class SaveSlotActivity : AppCompatActivity() {

    private lateinit var dm: GameDataManager
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dm = GameDataManager.get(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(64), dp(24), dp(48))
        }
        scroll.addView(root)

        root.addView(tv("\uD83D\uDCBE", 44f, Color.WHITE, Gravity.CENTER))
        root.addView(tv("Partite salvate", 22f, Color.parseColor("#FFD700"),
            Gravity.CENTER, bold = true).also { it.setPadding(0,4,0,4) })
        root.addView(tv("Tocca uno slot per caricare la partita",
            13f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER).also { it.setPadding(0,0,0,dp(24)) })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(scroll)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        listContainer.removeAllViews()
        val slots = dm.getSaveSlots()
        if (slots.isEmpty()) {
            listContainer.addView(tv("Nessuna partita salvata.\nAvvia una caccia e salva dal menu in-game!",
                14f, Color.parseColor("#88FFFFFF"), Gravity.CENTER).also {
                it.setPadding(0, dp(40), 0, 0)
            })
            return
        }
        slots.sortedByDescending { it.savedAt }.forEach { slot ->
            listContainer.addView(buildSlotCard(slot))
        }
    }

    private fun buildSlotCard(slot: GameDataManager.SavedSession): CardView {
        val card = CardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC1A237E"))
            cardElevation = dp(5).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        // Titolo slot
        val name = slot.slotName.ifBlank { "Partita del ${slot.savedAt}" }
        col.addView(tv(name, 15f, Color.WHITE, bold = true))

        // Info
        val players = slot.players.joinToString(", ")
        val eggInfo = "${slot.eggCount} uova"
        col.addView(tv("\uD83D\uDC65 $players  |  \uD83E\uDD5A $eggInfo",
            12f, Color.parseColor("#AAFFFFFF")).also { it.setPadding(0, dp(4), 0, dp(8)) })

        // Riga pulsanti
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnLoad = Button(this).apply {
            text = "\uD83D\uDCE5 CARICA"
            setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#FF4CAF50"))
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                .also { it.marginEnd = dp(8) }
            setOnClickListener { loadSlot(slot) }
        }
        val btnDel = Button(this).apply {
            text = "\uD83D\uDDD1"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CCB71C1C"))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
            setOnClickListener { confirmDelete(slot) }
        }
        btnRow.addView(btnLoad)
        btnRow.addView(btnDel)
        col.addView(btnRow)

        card.addView(col)
        return card
    }

    private fun loadSlot(slot: GameDataManager.SavedSession) {
        val eggInfo = if (slot.eggOffsets.isNotEmpty())
            "${slot.eggOffsets.size} uova con posizioni salvate"
        else
            "${slot.eggCount} uova (posizioni non salvate)"

        AlertDialog.Builder(this)
            .setTitle("\uD83D\uDCE5 Carica partita")
            .setMessage(
                "Partita: ${slot.slotName.ifBlank { slot.savedAt }}\n" +
                "Giocatori: ${slot.players.joinToString(", ")}\n" +
                "Uova: $eggInfo\n\n" +
                "Come funziona il ripristino:\n" +
                "1. Inquadra la stanza con la fotocamera\n" +
                "2. Piazza la cassaforte NELLO STESSO PUNTO di prima\n" +
                "3. Le uova appariranno automaticamente!"
            )
            .setPositiveButton("CARICA") { _, _ ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    putStringArrayListExtra("players", ArrayList(slot.players))
                    putStringArrayListExtra("riddles", ArrayList(slot.riddles))
                    putExtra("restore_mode", true)
                    putExtra("restore_slot_id", slot.id)
                }
                startActivity(intent)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmDelete(slot: GameDataManager.SavedSession) {
        AlertDialog.Builder(this)
            .setTitle("Elimina partita?")
            .setMessage("Stai per eliminare:\n${slot.slotName.ifBlank { slot.savedAt }}\n\nQuesta azione non puo\' essere annullata.")
            .setPositiveButton("Elimina") { _, _ ->
                dm.deleteSaveSlot(slot.id)
                refresh()
                Toast.makeText(this, "Partita eliminata", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ─── helpers ─────────────────────────────────────────────────
    private fun tv(text: String, size: Float, color: Int,
                   gravity: Int = Gravity.START, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); this.gravity = gravity
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
