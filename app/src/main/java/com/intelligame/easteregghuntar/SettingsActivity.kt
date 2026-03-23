package com.intelligame.easteregghuntar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = GameDataManager.get(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 72, 40, 40)
        }
        scroll.addView(root)

        fun title(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 18f; setTextColor(Color.parseColor("#FFD700"))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        })
        fun label(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 14f; setTextColor(Color.WHITE); setPadding(0, 4, 0, 4)
        })
        fun sublabel(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 12f; setTextColor(Color.parseColor("#AAFFFFFF")); setPadding(0, 0, 0, 8)
        })
        fun divider() = root.addView(android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 16; it.bottomMargin = 8 }
        })

        root.addView(TextView(this).apply {
            text = "⚙️ Impostazioni"
            textSize = 22f; setTextColor(Color.parseColor("#FFD700"))
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        // ── GIOCO ───────────────────────────────────────────────
        title("🎮 Gameplay")

        label("Distanza cattura uovo")
        sublabel("Quanto devi essere vicino per lanciare il cestino")
        val seekCatch = SeekBar(this).apply {
            max = 18  // 0.3m – 2.1m in step di 0.1m
            progress = ((dm.getCatchDistMeters() * 10 - 3).coerceIn(0f, 18f)).toInt()
        }
        val tvCatch = TextView(this).apply { text = "%.1fm".format(dm.getCatchDistMeters()); setTextColor(Color.parseColor("#FFD700")); textSize = 13f }
        seekCatch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                val v = (p + 3) / 10f; tvCatch.text = "%.1fm".format(v)
                dm.setCatchDistMeters(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        root.addView(seekCatch); root.addView(tvCatch)

        divider()

        label("Distanza rivelazione uovo")
        sublabel("Da quanti metri l'uovo diventa visibile in AR")
        val seekReveal = SeekBar(this).apply {
            max = 40  // 1m – 5m in step di 0.1m
            progress = ((dm.getRevealDistMeters() * 10 - 10).coerceIn(0f, 40f)).toInt()
        }
        val tvReveal = TextView(this).apply { text = "%.1fm".format(dm.getRevealDistMeters()); setTextColor(Color.parseColor("#FFD700")); textSize = 13f }
        seekReveal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                val v = (p + 10) / 10f; tvReveal.text = "%.1fm".format(v)
                dm.setRevealDistMeters(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        root.addView(seekReveal); root.addView(tvReveal)

        // ── AUDIO/VIBRAZIONE ────────────────────────────────────
        title("🔊 Audio e feedback")

        val swSound = Switch(this).apply {
            text = "Suoni di gioco"; setTextColor(Color.WHITE); textSize = 14f
            isChecked = dm.getSoundEnabled()
            setOnCheckedChangeListener { _, c -> dm.setSoundEnabled(c) }
        }
        root.addView(swSound)

        val swVib = Switch(this).apply {
            text = "Vibrazione"; setTextColor(Color.WHITE); textSize = 14f
            isChecked = dm.getVibrationEnabled()
            setOnCheckedChangeListener { _, c -> dm.setVibrationEnabled(c) }
        }
        root.addView(swVib)

        // ── MODALITÀ AR ─────────────────────────────────────────────
        title("📡 Modalità AR (default)")
        sublabel("Puoi cambiarla anche all'avvio di ogni partita")

        val arModes = listOf(
            "standard"  to "Piani piatti (pavimento, tavoli)",
            "depth"     to "Depth AR — qualsiasi superficie (consigliato)",
            "room_scan" to "Room Scan — scansione completa, anchor locali gratuiti"
        )
        var currentArMode = dm.getArMode()

        val arGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 4, 0, 4)
        }
        arModes.forEach { (key, label) ->
            val rb = RadioButton(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 13f
                id = key.hashCode()
                isChecked = (key == currentArMode)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD700"))
            }
            arGroup.addView(rb)
        }
        arGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = arModes.firstOrNull { it.first.hashCode() == checkedId }?.first
            if (selected != null) { currentArMode = selected; dm.setArMode(selected) }
        }
        root.addView(arGroup)
        sublabel("Room Scan: gratuito, nessuna API key. Anchor salvati in locale.")

        divider()

        // ── ANCHOR LOCALI ────────────────────────────────────────
        title("💾 Anchor Locali (Room Scan)")
        sublabel("Gratuiti, illimitati, nessuna rete richiesta.")

        val store    = LocalAnchorStore.get(this)
        val sessions = store.listSessions()
        val totalKb  = store.totalSizeKb()

        label("Sessioni salvate: ${sessions.size}  •  Spazio usato: ${totalKb}KB")

        // Slider TTL
        label("Durata anchor (giorni prima della scadenza)")
        sublabel("0 = mai scadono  •  30 = un mese  •  365 = un anno")
        val ttlValues = listOf(0, 7, 14, 30, 60, 90, 180, 365)
        val currentTtl = dm.getLocalAnchorTtlDays()
        val seekTtl = android.widget.SeekBar(this).apply {
            max = ttlValues.size - 1
            progress = ttlValues.indexOfFirst { it >= currentTtl }.coerceAtLeast(0)
        }
        val tvTtl = TextView(this).apply {
            text = if (currentTtl == 0) "Mai scadono" else "${currentTtl} giorni"
            setTextColor(Color.parseColor("#FFD700")); textSize = 13f
        }
        seekTtl.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, u: Boolean) {
                val v = ttlValues.getOrElse(p) { 30 }
                tvTtl.text = if (v == 0) "Mai scadono" else "$v giorni"
                dm.setLocalAnchorTtlDays(v)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })
        root.addView(seekTtl); root.addView(tvTtl)

        // Lista sessioni
        if (sessions.isNotEmpty()) {
            label("Sessioni salvate:")
            sessions.take(10).forEach { sess ->
                val daysLeft = sess.daysRemaining
                val expStr = if (sess.ttlDays == 0) "non scade" else "scade in ${daysLeft}g"
                val sessionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 4, 0, 4)
                }
                sessionRow.addView(TextView(this).apply {
                    text = "📦 ${sess.sessionName} — ${sess.anchorCount} anchor — $expStr"
                    textSize = 12f; setTextColor(Color.parseColor("#CCFFFFFF"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val btnDel = Button(this).apply {
                    text = "🗑"; setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#88B71C1C"))
                    layoutParams = LinearLayout.LayoutParams(
                        (40 * resources.displayMetrics.density).toInt(),
                        (32 * resources.displayMetrics.density).toInt())
                    setOnClickListener {
                        store.delete(sess.sessionId)
                        Toast.makeText(this@SettingsActivity, "Sessione eliminata", Toast.LENGTH_SHORT).show()
                        recreate()
                    }
                }
                sessionRow.addView(btnDel)
                root.addView(sessionRow)
            }
        }

        val btnPurge = Button(this).apply {
            text = "🧹 Elimina scaduti  •  Spazio libero: ${totalKb}KB usati"
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#CC333333"))
            textSize = 12f
            setOnClickListener {
                val n = store.purgeExpired()
                Toast.makeText(this@SettingsActivity, "Rimossi $n anchor scaduti", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }
        root.addView(btnPurge)

        sublabel("Gli anchor locali sopravvivono alla chiusura dell'app per il numero di giorni impostato sopra.")

        divider()

        // ── PRIVACY ─────────────────────────────────────────────
        title("🔒 Privacy annunci")
        val swAd = Switch(this).apply {
            text = "Annunci personalizzati (AdMob)"; setTextColor(Color.WHITE); textSize = 13f
            isChecked = dm.getAdPersonalized()
            setOnCheckedChangeListener { _, c -> dm.setAdPersonalized(c) }
        }
        root.addView(swAd)
        sublabel("Disattiva per vedere annunci non personalizzati")

        // ── DATI ────────────────────────────────────────────────
        title("🗑 Dati")
        val btnClearStats = Button(this).apply {
            text = "Cancella tutte le statistiche"; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CCB71C1C")); textSize = 13f
            setOnClickListener {
                android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Sei sicuro?")
                    .setMessage("Elimina tutte le statistiche di tutte le giocatrici.")
                    .setPositiveButton("Elimina") { _, _ -> dm.clearStats(); Toast.makeText(this@SettingsActivity, "Statistiche cancellate", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("Annulla", null).show()
            }
        }
        root.addView(btnClearStats)

        divider()

        // ── ACCOUNT ─────────────────────────────────────────────
        title("👤 Account")
        val prefs = getSharedPreferences("login_prefs", MODE_PRIVATE)
        val provider = prefs.getString("provider", "guest") ?: "guest"
        val displayName = prefs.getString("display_name", "Ospite") ?: "Ospite"
        label("Connesso come: $displayName (${provider.replaceFirstChar { it.uppercase() }})")
        sublabel("Premi Logout per cambiare account o tornare alla schermata di accesso")
        val btnLogout = Button(this).apply {
            text = "🚪 Logout"
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#CC7B1FA2")); textSize = 13f
            setOnClickListener {
                android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Logout")
                    .setMessage("Vuoi davvero uscire dall'account?")
                    .setPositiveButton("Sì, esci") { _, _ ->
                        getSharedPreferences("login_prefs", MODE_PRIVATE).edit()
                            .putBoolean("logged_in", false).apply()
                        startActivity(android.content.Intent(this@SettingsActivity, LoginActivity::class.java))
                        finishAffinity()
                    }
                    .setNegativeButton("Annulla", null).show()
            }
        }
        root.addView(btnLogout)

        setContentView(scroll)
    }
}
