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
 * OutdoorGuestActivity — schermata per i guest.
 *
 * Il guest può:
 *  1. Sfogliare le stanze pubbliche (lista aggiornata da Firebase)
 *  2. Inserire direttamente un codice stanza a 6 caratteri
 *
 * Al tap su una stanza → verifica validità → OutdoorHuntActivity (senza piazzamento uova)
 */
class OutdoorGuestActivity : AppCompatActivity() {

    private var playerName = "Giocatore"
    private var playerId   = OutdoorRoomManager.generatePlayerId()
    private lateinit var listContainer: LinearLayout
    private lateinit var tvStatus:      TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val players = intent.getStringArrayListExtra("players") ?: arrayListOf()
        playerName  = players.firstOrNull() ?: "Giocatore"

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(48))
        }
        scroll.addView(root)

        // ── Header ────────────────────────────────────────────────
        root.addView(tv("🔑 Unisciti a una stanza", 20f, Color.parseColor("#FFD700"),
            Gravity.CENTER, bold = true).also { it.setPadding(0, 0, 0, dp(4)) })
        root.addView(tv("Sei: $playerName", 13f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER)
            .also { it.setPadding(0, 0, 0, dp(16)) })

        // ── Inserisci codice ──────────────────────────────────────
        root.addView(sectionHeader("📝 Hai un codice?"))
        val etCode = EditText(this).apply {
            hint = "Codice stanza (6 caratteri)"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#88FFFFFF"))
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        root.addView(etCode)

        root.addView(Button(this).apply {
            text = "🔑  ENTRA CON CODICE"
            setTextColor(Color.parseColor("#001020"))
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#FFD700")); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
                .also { it.bottomMargin = dp(16) }
            setOnClickListener {
                val code = etCode.text.toString().trim().uppercase()
                if (code.length != 6) {
                    Toast.makeText(this@OutdoorGuestActivity, "Il codice deve essere di 6 caratteri", Toast.LENGTH_SHORT).show()
                } else {
                    joinByCode(code)
                }
            }
        })

        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .also { it.topMargin = dp(8); it.bottomMargin = dp(16) }
        })

        // ── Lista stanze pubbliche ────────────────────────────────
        root.addView(sectionHeader("🌍 Stanze pubbliche attive"))

        tvStatus = tv("Caricamento...", 12f, Color.parseColor("#AAFFFFFF")).also { it.setPadding(0, 0, 0, dp(8)) }
        root.addView(tvStatus)

        val btnRefresh = Button(this).apply {
            text = "🔄 Aggiorna lista"
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#CC1565C0"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                .also { it.bottomMargin = dp(10) }
            setOnClickListener { loadPublicRooms() }
        }
        root.addView(btnRefresh)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(scroll)
        loadPublicRooms()
    }

    private fun loadPublicRooms() {
        tvStatus.text = "Caricamento stanze..."
        listContainer.removeAllViews()

        OutdoorRoomManager.listPublicRooms(
            onResult = { rooms ->
                runOnUiThread {
                    if (rooms.isEmpty()) {
                        tvStatus.text = "Nessuna stanza pubblica attiva al momento."
                        return@runOnUiThread
                    }
                    tvStatus.text = "${rooms.size} stanze disponibili:"
                    rooms.forEach { room -> listContainer.addView(buildRoomCard(room)) }
                }
            },
            onError = { msg ->
                runOnUiThread { tvStatus.text = "Errore: $msg" }
            }
        )
    }

    private fun buildRoomCard(room: OutdoorRoomManager.RoomInfo): CardView {
        val card = CardView(this).apply {
            radius = dp(12).toFloat(); cardElevation = dp(4).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC1A237E"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(10) }
            isClickable = true; isFocusable = true
            setOnClickListener { joinByCode(room.code) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        // Nome stanza + codice
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tv(room.roomName, 16f, Color.WHITE, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(tv(room.code, 14f, Color.parseColor("#FFD700"), bold = true))
        })
        // Info riga
        col.addView(tv("Host: ${room.hostName}  •  ${room.eggCount} uova  •  ${room.playerCount} giocatori",
            12f, Color.parseColor("#CCFFFFFF")).also { it.setPadding(0, dp(3), 0, 0) })
        // TTL
        val daysLeft = room.daysRemaining
        val ttlText  = if (daysLeft == Int.MAX_VALUE) "senza scadenza" else "scade in ${daysLeft}g"
        col.addView(tv(ttlText, 11f, Color.parseColor("#88FFFFFF")).also { it.setPadding(0, dp(2), 0, 0) })

        card.addView(col); return card
    }

    private fun joinByCode(code: String) {
        tvStatus.text = "Connessione a $code..."
        OutdoorRoomManager.joinRoom(
            code       = code,
            playerId   = playerId,
            playerName = playerName,
            onSuccess  = { eggs, penaltySecs, roomName ->
                runOnUiThread {
                    // Crea sessione locale con le uova scaricate
                    val sess = OutdoorSession(
                        id          = code,
                        createdAt   = System.currentTimeMillis().toString(),
                        players     = listOf(playerName),
                        eggs        = eggs,
                        penaltySecs = penaltySecs,
                        isMultiplayer = true,
                        roomCode    = code
                    )
                    startActivity(Intent(this, OutdoorHuntActivity::class.java).apply {
                        putExtra(OutdoorSetupActivity.EXTRA_SESSION_JSON, sess.toJson().toString())
                        putExtra(OutdoorModeActivity.EXTRA_IS_MP,      true)
                        putExtra(OutdoorModeActivity.EXTRA_IS_HOST,    false)
                        putExtra(OutdoorModeActivity.EXTRA_ROOM_CODE,  code)
                        putExtra(OutdoorModeActivity.EXTRA_PLAYER_NAME, playerName)
                        putExtra(OutdoorModeActivity.EXTRA_PLAYER_ID,   playerId)
                        putExtra(OutdoorModeActivity.EXTRA_PENALTY_SECS, penaltySecs)
                    })
                }
            },
            onError = { msg ->
                runOnUiThread {
                    tvStatus.text = "Errore: $msg"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun sectionHeader(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, dp(8), 0, dp(8))
    }
    private fun tv(text: String, size: Float, color: Int,
                   gravity: Int = Gravity.START, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); this.gravity = gravity
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
