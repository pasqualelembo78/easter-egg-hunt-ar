package com.intelligame.easteregghuntar

import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class StatsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = GameDataManager.get(this)
        val dp = resources.displayMetrics.density

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 72, 32, 40)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "📊 Statistiche"
            textSize = 22f; setTextColor(Color.parseColor("#FFD700"))
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        })

        val allRuns = dm.getAllRuns()
        if (allRuns.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "\n\n🐰  Nessuna partita registrata ancora.\nGioca la prima caccia!"
                textSize = 16f; setTextColor(Color.parseColor("#AAFFFFFF"))
                gravity = android.view.Gravity.CENTER; setPadding(0, 40, 0, 0)
            })
            setContentView(scroll); return
        }

        // Riepilogo globale
        val totalGames = allRuns.size
        val bestTotal  = allRuns.minOf { it.totalMs }
        val bestPlayer = allRuns.minByOrNull { it.totalMs }?.playerName ?: ""
        root.addView(buildSummaryCard(totalGames, bestTotal, bestPlayer, dp))

        // Per ogni giocatore
        val players = allRuns.map { it.playerName }.distinct().sorted()
        players.forEach { player ->
            val runs = allRuns.filter { it.playerName == player }
            root.addView(buildPlayerSection(player, runs, dp))
        }

        // Confronto diretto (se ci sono ≥2 giocatori)
        if (players.size >= 2) {
            root.addView(buildComparisonCard(players, dm, dp))
        }

        setContentView(scroll)
    }

    private fun buildSummaryCard(totalGames: Int, bestMs: Long, bestPlayer: String, dp: Float): CardView {
        val card = CardView(this).apply {
            radius = 16 * dp; setCardBackgroundColor(Color.parseColor("#CC1A237E"))
            cardElevation = 6 * dp
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (16 * dp).toInt(); layoutParams = lp
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (16*dp).toInt()) }
        col.addView(tv("🏆 Riepilogo globale", 16f, "#FFD700", true))
        col.addView(tv("Partite totali: $totalGames", 14f))
        col.addView(tv("Record assoluto: ${fmtMs(bestMs)} ($bestPlayer)", 14f))
        card.addView(col); return card
    }

    private fun buildPlayerSection(player: String, runs: List<GameDataManager.GameRun>, dp: Float): LinearLayout {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 0) }
        section.addView(tv("👧 $player", 18f, "#FFD700", true))

        // Cards partite
        runs.sortedByDescending { it.date }.forEach { run ->
            val card = CardView(this).apply {
                radius = 12 * dp; setCardBackgroundColor(Color.parseColor("#CC0A1930"))
                cardElevation = 4 * dp
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = (8 * dp).toInt(); lp.topMargin = (4 * dp).toInt(); layoutParams = lp
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (12*dp).toInt()) }
            col.addView(tv("📅 ${run.date}  •  ⏱ ${fmtMs(run.totalMs)}", 13f, "#FFFFFFFF", true))
            col.addView(tv("Uova: ${run.eggCount}  •  🏆 ${fmtMs(run.bestMs())}  •  😅 ${fmtMs(run.worstMs())}  •  ⌀ ${fmtMs(run.avgMs())}", 12f, "#AAFFFFFF"))
            run.eggStats.forEach { e ->
                col.addView(tv("  Uovo #${e.eggNumber}: ${fmtMs(e.timeMs)}", 12f, "#88FFFFFF"))
            }
            card.addView(col); section.addView(card)
        }

        // Migliore record personale
        val best = runs.minByOrNull { it.totalMs }
        if (best != null) {
            section.addView(tv("  🥇 Record personale: ${fmtMs(best.totalMs)}", 13f, "#FFD700"))
        }
        section.addView(android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 16; it.bottomMargin = 8 }
        })
        return section
    }

    private fun buildComparisonCard(players: List<String>, dm: GameDataManager, dp: Float): CardView {
        val card = CardView(this).apply {
            radius = 16 * dp; setCardBackgroundColor(Color.parseColor("#CC7B1FA2"))
            cardElevation = 6 * dp
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (8 * dp).toInt(); layoutParams = lp
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (16*dp).toInt()) }
        col.addView(tv("⚡ Confronto diretto", 16f, "#FFD700", true))
        players.forEach { p ->
            val best = dm.getRunsForPlayer(p).minByOrNull { it.totalMs }
            if (best != null) col.addView(tv("$p: 🏆 ${fmtMs(best.totalMs)}", 14f))
        }
        // Chi vince?
        val bests = players.mapNotNull { p ->
            dm.getRunsForPlayer(p).minByOrNull { it.totalMs }?.let { Pair(p, it.totalMs) }
        }
        if (bests.size >= 2) {
            val winner = bests.minByOrNull { it.second }!!
            col.addView(tv("🏆 Leader: ${winner.first}!", 15f, "#FFD700", true))
        }
        card.addView(col); return card
    }

    private fun tv(text: String, size: Float, color: String = "#FFFFFF", bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        setPadding(0, 2, 0, 2)
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m > 0) "%dm%02ds".format(m, s % 60) else "${s}s"
    }
}
