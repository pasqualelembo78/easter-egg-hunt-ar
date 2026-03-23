package com.intelligame.easteregghuntar

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * PlayerProfileActivity — visualizza il profilo del giocatore corrente
 * e la classifica mondiale.
 *
 * Chiunque può vedere il livello e il potere di tutti i giocatori.
 * Il profilo è persistente per sempre su Firestore.
 */
class PlayerProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(48))
        }
        scroll.addView(root)

        root.addView(tv("👤 Il Tuo Profilo", 22f, Color.parseColor("#FFD700"),
            Gravity.CENTER, bold = true).also { it.setPadding(0, 0, 0, dp(20)) })

        // ── Profilo corrente ──────────────────────────────────────
        val profile = PlayerProfileManager.myProfile

        if (profile == null) {
            root.addView(tv("Profilo non ancora caricato.\nApri prima la modalità Mondo outdoor.",
                14f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER))
        } else {
            buildProfileCard(root, profile, isMine = true)
        }

        root.addView(divider())

        // ── Leaderboard ───────────────────────────────────────────
        root.addView(tv("🏆 Classifica Mondiale", 18f, Color.parseColor("#FFD700"),
            Gravity.CENTER, bold = true).also { it.setPadding(0, dp(8), 0, dp(12)) })

        val leaderContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(leaderContainer)

        val tvLoading = tv("Caricamento classifica...", 13f, Color.parseColor("#AAFFFFFF"),
            Gravity.CENTER)
        leaderContainer.addView(tvLoading)

        PlayerProfileManager.getLeaderboard(
            onResult = { entries ->
                runOnUiThread {
                    leaderContainer.removeAllViews()
                    if (entries.isEmpty()) {
                        leaderContainer.addView(tv("Nessun giocatore ancora.",
                            13f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER))
                        return@runOnUiThread
                    }
                    entries.forEachIndexed { i, entry ->
                        val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i+1}." }
                        val isMine = entry.playerId == profile?.playerId
                        val card = buildLeaderCard(medal, entry, isMine)
                        leaderContainer.addView(card)
                        (card.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)
                    }
                }
            },
            onError = { msg ->
                runOnUiThread {
                    leaderContainer.removeAllViews()
                    leaderContainer.addView(tv("Errore: $msg", 12f, Color.parseColor("#FFAA44")))
                }
            }
        )

        root.addView(divider())

        // ── Elimina profilo ───────────────────────────────────────
        if (profile != null) {
            root.addView(Button(this).apply {
                text = "🗑️ Elimina il mio profilo"
                setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#88B71C1C"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).also { it.topMargin = dp(8) }
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@PlayerProfileActivity)
                        .setTitle("Eliminare il profilo?")
                        .setMessage("Perderai tutti i tuoi XP, potere e uova catturate. Azione irreversibile.")
                        .setPositiveButton("Elimina") { _, _ ->
                            PlayerProfileManager.deleteMyProfile(this@PlayerProfileActivity) {
                                Toast.makeText(this@PlayerProfileActivity,
                                    "Profilo eliminato.", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                        .setNegativeButton("Annulla", null).show()
                }
            })
        }

        setContentView(scroll)
    }

    private fun buildProfileCard(root: LinearLayout, profile: PlayerProfile, isMine: Boolean) {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = dp(6).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC1A237E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        // Nome + titolo
        col.addView(tv(profile.name, 20f, Color.WHITE, Gravity.CENTER, bold = true))
        col.addView(tv(profile.title, 14f, Color.parseColor("#FFD700"), Gravity.CENTER)
            .also { it.setPadding(0, dp(2), 0, dp(12)) })

        // Statistiche principali
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        fun statBlock(emoji: String, value: String, label: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(tv(emoji, 28f, Color.WHITE, Gravity.CENTER))
                addView(tv(value, 18f, Color.WHITE, Gravity.CENTER, bold = true))
                addView(tv(label, 10f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER))
            }
        }
        statsRow.addView(statBlock("⚡", "${profile.power}", "Potere"))
        statsRow.addView(statBlock("🎓", "Lv.${profile.level}", "Livello"))
        statsRow.addView(statBlock("🥚", "${profile.eggsFound}", "Uova"))
        statsRow.addView(statBlock("💪", "${profile.gymTrainings}", "Allenamenti"))
        col.addView(statsRow)

        // Barra XP
        col.addView(tv("XP: ${profile.xpProgressInLevel} / ${profile.xpNeededForNextLevel}  →  Lv.${profile.level+1}",
            11f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER).also { it.setPadding(0, dp(12), 0, dp(4)) })
        col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = profile.xpNeededForNextLevel.toInt().coerceAtLeast(1)
            progress = profile.xpProgressInLevel.toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD700"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
                .also { it.bottomMargin = dp(12) }
        })

        // Rarity breakdown
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            listOf(
                "🥚 ×${profile.commonFound}" to "#4CAF50",
                "🥚 ×${profile.uncommonFound}" to "#2196F3",
                "💎 ×${profile.rareFound}" to "#9C27B0",
                "🔥 ×${profile.epicFound}" to "#FF5722",
                "⭐ ×${profile.legendaryFound}" to "#FFD700"
            ).forEach { (text, color) ->
                addView(tv(text, 11f, Color.parseColor(color), Gravity.CENTER).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        })

        card.addView(col); root.addView(card)
    }

    private fun buildLeaderCard(medal: String, entry: PlayerProfileManager.LeaderboardEntry,
                                isMine: Boolean): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(10).toFloat(); cardElevation = dp(3).toFloat()
            setCardBackgroundColor(Color.parseColor(
                if (isMine) "#CC2E7D32" else "#CC1A237E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        row.addView(tv(medal, 18f, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT) })
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameCol.addView(tv(entry.name + if (isMine) " (tu)" else "",
            14f, Color.WHITE, bold = isMine))
        nameCol.addView(tv(entry.title, 11f, Color.parseColor("#AAFFFFFF")))
        row.addView(nameCol)
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(tv("⚡ ${entry.power}", 14f, Color.parseColor("#64B5F6"), Gravity.END, bold = true))
            addView(tv("Lv.${entry.level} • ${entry.eggsFound} 🥚", 11f, Color.parseColor("#AAFFFFFF"), Gravity.END))
        })
        card.addView(row); return card
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#22FFFFFF"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            .also { it.topMargin = dp(12); it.bottomMargin = dp(16) }
    }

    private fun tv(text: String, size: Float, color: Int,
                   gravity: Int = Gravity.START, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); this.gravity = gravity
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
