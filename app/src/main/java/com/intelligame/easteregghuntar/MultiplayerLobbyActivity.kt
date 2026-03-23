package com.intelligame.easteregghuntar

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class MultiplayerLobbyActivity : AppCompatActivity() {

    private enum class Screen { INITIAL, CREATE, BROWSE, WAITING_HOST, JOINING, WAITING_GUEST }
    private var screen = Screen.INITIAL

    private lateinit var dm: GameDataManager
    private lateinit var mp: MultiplayerManager
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView

    // Config partita
    private var eggCountVal    = 4
    private var trapCountVal   = 0
    private var penaltySecsVal = 30
    private var riddlesList    = mutableListOf<String>()
    private var isPublicRoom   = true

    // Chat
    private val chatMessages   = mutableListOf<MultiplayerManager.ChatMessage>()
    private var chatListView   : LinearLayout? = null
    private var chatScrollView : ScrollView? = null
    private var chatUnread     = 0
    private var chatBadge      : TextView? = null
    private var chatPanel      : View? = null
    private var isChatOpen     = false

    // Player list
    private var playerListView : LinearLayout? = null
    private var riddleCountTv  : TextView? = null

    private val pickRiddles = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                riddlesList = r.readLines().filter { it.isNotBlank() }.toMutableList()
                toast("${riddlesList.size} indovinelli caricati")
                riddleCountTv?.text = "📄 ${riddlesList.size} indovinelli caricati"
            }
        } catch (_: Exception) { toast("Errore lettura file") }
    }

    // ─── Lifecycle ───────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dm = GameDataManager.get(this)
        mp = MultiplayerManager.get()
        try { assets.open("riddles.txt").bufferedReader().use { r ->
            riddlesList = r.readLines().filter { it.isNotBlank() }.toMutableList() }
        } catch (_: Exception) {}

        scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }
        root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(60), dp(20), dp(40))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(root)
        setContentView(scroll)

        if (!dm.isMultiplayerUnlocked()) { showPremiumGate(); return }
        setupMpCallbacks()
        buildInitialScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ─── Premium Gate ────────────────────────────────────────────
    private fun showPremiumGate() {
        root.removeAllViews()
        addHeader("🌐", "MULTIPLAYER", "#00C6FF")
        addSpacer(dp(8))
        addSubtitle("Gioca in tempo reale con i tuoi amici\novunque nel mondo")
        addSpacer(dp(24))
        listOf("🎮" to "Fino a 6 giocatori in contemporanea",
               "⚡" to "Sincronizzazione in tempo reale",
               "🏆" to "Leaderboard live durante la caccia",
               "💬" to "Chat integrata tra giocatori",
               "🏠" to "Stanze con nome personalizzato",
               "🔍" to "Sfoglia stanze pubbliche",
               "♾️" to "Accesso illimitato, acquisto unico"
        ).forEach { (e, t) -> root.addView(buildFeatureRow(e, t)) }
        addSpacer(dp(24))
        val pc = CardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = dp(8).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC1A237E"))
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(16) }
        }
        val pcl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24),dp(20),dp(24),dp(20)) }
        pcl.addView(tv("MULTIPLAYER PREMIUM", 13f, Color.parseColor("#AAFFFFFF"), bold = true).also { it.letterSpacing = 0.12f })
        pcl.addView(tv("4,99 €", 36f, Color.parseColor("#FFD700"), bold = true).also { it.setPadding(0,dp(4),0,dp(4)) })
        pcl.addView(tv("Acquisto unico — nessun abbonamento", 12f, Color.parseColor("#88FFFFFF")))
        pc.addView(pcl); root.addView(pc)
        root.addView(buildBtn("🛒  ACQUISTA ORA — 4,99 €", "#FFD700", "#000000") { showPurchaseDialog() })
        addSpacer(dp(8))
        root.addView(buildBtn("🔓  Sblocca (test)", "#CC333333", "#CCFFFFFF") {
            dm.unlockMultiplayer(); toast("✅ Multiplayer sbloccato!")
            root.removeAllViews(); setupMpCallbacks(); buildInitialScreen()
        })
        addSpacer(dp(20))
        root.addView(buildBtn("← Torna indietro", "#CC222222", "#AAFFFFFF") { finish() })
    }

    private fun showPurchaseDialog() {
        AlertDialog.Builder(this)
            .setTitle("Acquista Multiplayer Premium")
            .setMessage("Prezzo: 4,99 €  —  acquisto unico.\n\nIl pagamento reale sarà attivo tramite Google Play Billing al rilascio.\nPer ora usa il pulsante di sblocco test.")
            .setPositiveButton("Sblocca (test)") { _, _ ->
                dm.unlockMultiplayer(); toast("✅ Sbloccato!")
                root.removeAllViews(); setupMpCallbacks(); buildInitialScreen()
            }
            .setNegativeButton("Annulla", null).show()
    }

    // ─── Callbacks MP ─────────────────────────────────────────────
    private fun setupMpCallbacks() {
        mp.onPlayersChanged = { players -> runOnUiThread { updatePlayerList(players) } }
        mp.onScoresChanged  = { /* non usato in lobby */ }
        mp.onGameStarted    = { config -> runOnUiThread { launchGameAsGuest(config) } }
        mp.onChatMessage    = { msg -> runOnUiThread { handleIncomingChat(msg) } }
        mp.onError          = { msg -> runOnUiThread { toast("⚠️ $msg") } }
    }

    // ─── INITIAL ─────────────────────────────────────────────────
    private fun buildInitialScreen() {
        screen = Screen.INITIAL; root.removeAllViews(); isChatOpen = false; chatMessages.clear()
        addHeader("🌐", "MULTIPLAYER", "#00C6FF")
        addSpacer(dp(4))
        addSubtitle("Ogni giocatore usa il proprio telefono\nLeaderboard e chat live durante la caccia")
        addSpacer(dp(28))
        root.addView(buildBigActionCard("🎮", "CREA PARTITA", "Sei l'host: scegli nome, regole e avvia", "#1565C0") { buildCreateScreen() })
        addSpacer(dp(10))
        root.addView(buildBigActionCard("🔍", "SFOGLIA STANZE", "Unisciti a una stanza pubblica", "#006064") { buildBrowseScreen() })
        addSpacer(dp(10))
        root.addView(buildBigActionCard("🔗", "CODICE DIRETTO", "Hai già un codice? Inseriscilo subito", "#37474F") { buildJoinByCodeScreen() })
        addSpacer(dp(24))
        addInfoCard("ℹ️  Come funziona",
            "1. L'host crea la stanza e la condivide\n" +
            "2. I giocatori si uniscono con il codice o dalla lista\n" +
            "3. Ogni giocatore piazza la cassaforte AR nel proprio spazio\n" +
            "4. La caccia inizia simultaneamente su tutti i telefoni\n" +
            "5. Chat live + leaderboard in tempo reale durante il gioco")
        addSpacer(dp(20))
        root.addView(buildBtn("← Torna indietro", "#CC222222", "#AAFFFFFF") { finish() })
    }

    // ─── CREATE ──────────────────────────────────────────────────
    private var roomNameInput : EditText? = null
    private var playerNameInputC : EditText? = null

    private fun buildCreateScreen() {
        screen = Screen.CREATE; root.removeAllViews()
        addHeader("🎮", "CREA PARTITA", "#4CAF50")
        addSpacer(dp(20))

        root.addView(tv("Il tuo nome", 13f, "#AAFFFFFF".c))
        addSpacer(dp(4))
        playerNameInputC = buildInput("Es: Marco", defaultFromPlayers = true)
        root.addView(playerNameInputC)
        addSpacer(dp(12))

        root.addView(tv("Nome della stanza", 13f, "#AAFFFFFF".c))
        addSpacer(dp(4))
        roomNameInput = buildInput("Es: Caccia di Pasqua 2025 🐰", maxLen = 40)
        root.addView(roomNameInput)
        addSpacer(dp(16))

        // Toggle privata/pubblica
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = buildRoundBg("#CC1C1C2E", dp(10))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(16) }
        }
        val toggleLabel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        toggleLabel.addView(tv("Stanza pubblica", 14f, Color.WHITE, bold = true))
        toggleLabel.addView(tv("Visibile nella lista stanze", 12f, "#88FFFFFF".c))
        toggleRow.addView(toggleLabel)
        val sw = Switch(this).apply {
            isChecked = isPublicRoom
            setOnCheckedChangeListener { _, checked -> isPublicRoom = checked }
        }
        toggleRow.addView(sw)
        root.addView(toggleRow)

        addSectionHeader("⚙️  Configurazione")
        root.addView(buildCounterRow("🥚 Uova da trovare", eggCountVal, 1, 12) { eggCountVal = it })
        addSpacer(dp(8))
        root.addView(buildCounterRow("⚠️ Uova trappola", trapCountVal, 0, 4) { trapCountVal = it.coerceAtMost(eggCountVal - 1) })
        addSpacer(dp(8))
        root.addView(buildCounterRow("⏱ Penalità (sec)", penaltySecsVal, 10, 120, step = 10) { penaltySecsVal = it })
        addSpacer(dp(12))
        riddleCountTv = tv("📄 ${riddlesList.size} indovinelli", 13f, "#AAFFFFFF".c)
        root.addView(riddleCountTv)
        addSpacer(dp(6))
        root.addView(buildBtn("📂  Carica indovinelli personalizzati", "#CC333344", "#CCFFFFFF") {
            pickRiddles.launch(arrayOf("text/plain"))
        })
        addSpacer(dp(24))
        root.addView(buildBtn("🚀  CREA STANZA", "#FF4CAF50", "#000000", bold = true) { onCreateRoom() })
        addSpacer(dp(10))
        root.addView(buildBtn("← Indietro", "#CC222222", "#AAFFFFFF") { buildInitialScreen() })
    }

    // ─── BROWSE ──────────────────────────────────────────────────
    private fun buildBrowseScreen() {
        screen = Screen.BROWSE; root.removeAllViews()
        addHeader("🔍", "STANZE PUBBLICHE", "#00C6FF")
        addSpacer(dp(8))
        addSubtitle("Unisciti a una partita aperta")
        addSpacer(dp(16))

        val playerNameBrowse = buildInput("Il tuo nome", defaultFromPlayers = true)
        root.addView(tv("Il tuo nome", 13f, "#AAFFFFFF".c))
        addSpacer(dp(4))
        root.addView(playerNameBrowse)
        addSpacer(dp(16))

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(16) }
        }
        root.addView(listContainer)

        val loadingTv = tv("⏳ Caricamento stanze...", 14f, "#AAFFFFFF".c, gravity = Gravity.CENTER)
        listContainer.addView(loadingTv)

        root.addView(buildBtn("🔄  Aggiorna", "#CC1A237E", "#CCFFFFFF") {
            loadingTv.visibility = View.VISIBLE; listContainer.removeAllViews(); listContainer.addView(loadingTv)
            loadRoomList(listContainer, playerNameBrowse)
        })
        addSpacer(dp(10))
        root.addView(buildBtn("← Indietro", "#CC222222", "#AAFFFFFF") { buildInitialScreen() })

        loadRoomList(listContainer, playerNameBrowse)
    }

    private fun loadRoomList(container: LinearLayout, nameInput: EditText) {
        mp.getRoomList(
            onResult = { rooms ->
                runOnUiThread {
                    container.removeAllViews()
                    if (rooms.isEmpty()) {
                        container.addView(
                            tv("Nessuna stanza pubblica attiva.\nCrea tu la prima!", 14f,
                               "#66FFFFFF".c, gravity = Gravity.CENTER).also {
                                it.setPadding(0, dp(20), 0, dp(20))
                            })
                        return@runOnUiThread
                    }
                    rooms.forEach { room -> container.addView(buildRoomCard(room, nameInput)) }
                }
            },
            onFail = { msg -> runOnUiThread {
                container.removeAllViews()
                container.addView(tv("⚠️ $msg", 13f, "#FF8888".c).also { it.setPadding(0,dp(8),0,dp(8)) })
            }}
        )
    }

    private fun buildRoomCard(room: MultiplayerManager.RoomInfo, nameInput: EditText): View {
        val statusColor = if (room.status == "playing") "#FF5722" else "#4CAF50"
        val statusLabel = if (room.status == "playing") "In gioco" else "In attesa"
        val card = CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = dp(4).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC0A1930"))
            isClickable = room.status != "playing"; isFocusable = room.status != "playing"
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(10) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(tv(room.roomName, 16f, Color.WHITE, bold = true))
        info.addView(tv("Host: ${room.hostName}  •  👥 ${room.playerCount} giocatori", 12f, "#AAFFFFFF".c))
        row.addView(info)
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8),0,0,0) }
        right.addView(tv(statusLabel, 11f, Color.parseColor(statusColor), bold = true))
        if (room.status != "playing") {
            right.addView(tv("UNISCITI ›", 11f, Color.parseColor("#00C6FF"), bold = true).also { it.setPadding(0,dp(4),0,0) })
            card.setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) { toast("Inserisci il tuo nome prima!"); return@setOnClickListener }
                onJoinRoom(name, room.code)
            }
        }
        row.addView(right); card.addView(row); return card
    }

    // ─── JOIN BY CODE ────────────────────────────────────────────
    private fun buildJoinByCodeScreen() {
        screen = Screen.JOINING; root.removeAllViews()
        addHeader("🔗", "ENTRA CON CODICE", "#00C6FF")
        addSpacer(dp(20))

        root.addView(tv("Il tuo nome", 13f, "#AAFFFFFF".c))
        addSpacer(dp(4))
        val nameIn = buildInput("Es: Sara", defaultFromPlayers = true)
        root.addView(nameIn)
        addSpacer(dp(16))

        root.addView(tv("Codice stanza", 13f, "#AAFFFFFF".c))
        addSpacer(dp(4))
        val codeIn = EditText(this).apply {
            hint = "Es: ABC123"; setHintTextColor("#66FFFFFF".c); setTextColor("#FFD700".c)
            textSize = 28f; background = buildRoundBg("#CC006064", dp(12))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(6)); letterSpacing = 0.25f; gravity = Gravity.CENTER
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(24) }
        }
        root.addView(codeIn)

        root.addView(buildBtn("🚀  ENTRA NELLA STANZA", "#CC00C6FF", "#000000", bold = true) {
            val name = nameIn.text.toString().trim()
            val code = codeIn.text.toString().trim().uppercase()
            if (name.isEmpty()) { toast("Inserisci il tuo nome!"); return@buildBtn }
            if (code.length < 4) { toast("Codice troppo corto!"); return@buildBtn }
            onJoinRoom(name, code)
        })
        addSpacer(dp(10))
        root.addView(buildBtn("← Indietro", "#CC222222", "#AAFFFFFF") { buildInitialScreen() })
    }

    // ─── HOST WAITING SCREEN ─────────────────────────────────────
    private fun buildHostWaitingScreen(roomCode: String) {
        screen = Screen.WAITING_HOST; root.removeAllViews()
        addHeader("🎮", "STANZA CREATA!", "#4CAF50")
        addSpacer(dp(4))
        addSubtitle("\"${mp.currentRoomName}\"")
        addSpacer(dp(16))

        // Code card
        val codeCard = CardView(this).apply {
            radius = dp(20).toFloat(); cardElevation = dp(10).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC006064"))
            isClickable = true; isFocusable = true
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(4) }
            setOnClickListener { copyCode(roomCode) }
        }
        val cc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20),dp(18),dp(20),dp(14)) }
        cc.addView(tv("CODICE STANZA", 11f, "#AAFFFFFF".c, gravity = Gravity.CENTER, bold = true).also { it.letterSpacing = 0.15f })
        cc.addView(tv(roomCode, 46f, "#FFD700".c, gravity = Gravity.CENTER, bold = true).also { it.letterSpacing = 0.25f })
        cc.addView(tv("👆 Tocca per copiare", 11f, "#77FFFFFF".c, gravity = Gravity.CENTER))
        codeCard.addView(cc); root.addView(codeCard)
        ObjectAnimator.ofFloat(codeCard, "alpha", 0.8f, 1f).apply {
            duration = 1200; repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE; interpolator = LinearInterpolator()
        }.start()

        addSpacer(dp(16))
        root.addView(tv("👥  Giocatori", 14f, "#AAFFFFFF".c, bold = true))
        addSpacer(dp(8))
        playerListView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(16) }
        }
        root.addView(playerListView)

        // Chat in-lobby
        addSectionHeader("💬  Chat Stanza")
        root.addView(buildLobbyChatPanel())
        addSpacer(dp(16))

        addSectionHeader("⚙️  Configurazione")
        root.addView(buildCounterRow("🥚 Uova", eggCountVal, 1, 12) { eggCountVal = it })
        addSpacer(dp(6))
        root.addView(buildCounterRow("⚠️ Trappole", trapCountVal, 0, 4) { trapCountVal = it.coerceAtMost(eggCountVal - 1) })
        addSpacer(dp(6))
        root.addView(buildCounterRow("⏱ Penalità (s)", penaltySecsVal, 10, 120, step = 10) { penaltySecsVal = it })
        addSpacer(dp(8))
        riddleCountTv = tv("📄 ${riddlesList.size} indovinelli", 13f, "#AAFFFFFF".c)
        root.addView(riddleCountTv)
        addSpacer(dp(6))
        root.addView(buildBtn("📂  Carica indovinelli", "#CC333344", "#CCFFFFFF") { pickRiddles.launch(arrayOf("text/plain")) })
        addSpacer(dp(20))
        root.addView(buildBtn("▶  AVVIA LA CACCIA!", "#FF4CAF50", "#000000", bold = true) { onHostStartGame() })
        addSpacer(dp(10))
        root.addView(buildBtn("← Annulla", "#CC222222", "#AAFFFFFF") { mp.disconnect(); buildInitialScreen() })
    }

    // ─── GUEST WAITING SCREEN ────────────────────────────────────
    private fun buildGuestWaitingScreen() {
        screen = Screen.WAITING_GUEST; root.removeAllViews()
        addHeader("⏳", "IN ATTESA...", "#FFC107")
        addSpacer(dp(8))
        val codeCard = CardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = dp(6).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC006064"))
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(8) }
        }
        val cc2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20),dp(14),dp(20),dp(14)) }
        cc2.addView(tv("\"${mp.currentRoomName}\"", 15f, Color.WHITE, gravity = Gravity.CENTER, bold = true))
        cc2.addView(tv(mp.currentRoomCode, 36f, "#FFD700".c, gravity = Gravity.CENTER, bold = true).also { it.letterSpacing = 0.2f })
        codeCard.addView(cc2); root.addView(codeCard)
        addSubtitle("In attesa che l'host avvii la partita…\nNon uscire dall'app!")
        addSpacer(dp(16))
        root.addView(tv("👥  Giocatori nella stanza", 14f, "#AAFFFFFF".c, bold = true))
        addSpacer(dp(8))
        playerListView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(16) }
        }
        root.addView(playerListView)
        addSectionHeader("💬  Chat Stanza")
        root.addView(buildLobbyChatPanel())
        addSpacer(dp(20))
        root.addView(buildBtn("← Lascia la stanza", "#CC222222", "#AAFFFFFF") { mp.disconnect(); buildInitialScreen() })
    }

    // ─── Chat in-lobby ────────────────────────────────────────────
    private fun buildLobbyChatPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = buildRoundBg("#CC0A1430", dp(12))
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(8) }
        }
        // Messages area
        val chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200))
        }
        val msgList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        chatScroll.addView(msgList)
        panel.addView(chatScroll)

        chatListView = msgList; chatScrollView = chatScroll

        // Ripopola messaggi già ricevuti
        chatMessages.forEach { addChatBubble(it) }

        // Divider
        panel.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // Input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val msgInput = EditText(this).apply {
            hint = "Scrivi un messaggio..."; setHintTextColor("#55FFFFFF".c); setTextColor(Color.WHITE)
            textSize = 14f; background = buildRoundBg("#CC1A237E", dp(10))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            filters = arrayOf(InputFilter.LengthFilter(200))
            maxLines = 1; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(6) }
            setOnEditorActionListener { _, _, _ -> sendLobbyChat(this); true }
        }
        val sendBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "📤"; textSize = 16f; setPadding(dp(12),0,dp(12),0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#CC00C6FF"))
            cornerRadius = dp(10); strokeWidth = 0; insetTop = 0; insetBottom = 0
            setOnClickListener { sendLobbyChat(msgInput) }
        }
        inputRow.addView(msgInput); inputRow.addView(sendBtn)
        panel.addView(inputRow)
        return panel
    }

    private fun sendLobbyChat(input: EditText) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        mp.sendChatMessage(text)
        input.setText("")
    }

    private fun handleIncomingChat(msg: MultiplayerManager.ChatMessage) {
        chatMessages.add(msg)
        addChatBubble(msg)
        chatScrollView?.post { chatScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addChatBubble(msg: MultiplayerManager.ChatMessage) {
        val list = chatListView ?: return
        val isMe     = msg.senderId == mp.currentPlayerId
        val isSystem = msg.type == "system"

        if (isSystem) {
            list.addView(tv(msg.text, 11f, Color.parseColor("#AAFFC107"), gravity = Gravity.CENTER).also {
                it.setPadding(0, dp(3), 0, dp(3))
            })
            return
        }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isMe) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }
        if (!isMe) {
            bubble.addView(tv(msg.senderName, 10f, Color.parseColor("#AAFFFFFF"), bold = true).also { it.setPadding(dp(4),0,0,dp(2)) })
        }
        val bgColor = if (isMe) "#1565C0" else "#1C2E40"
        val card = CardView(this).apply {
            radius = dp(12).toFloat(); cardElevation = 0f
            setCardBackgroundColor(Color.parseColor(bgColor))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.gravity = if (isMe) Gravity.END else Gravity.START
                it.marginStart = if (isMe) dp(40) else 0
                it.marginEnd   = if (isMe) 0 else dp(40)
            }
        }
        card.addView(tv(msg.text, 14f, Color.WHITE).also { it.setPadding(dp(10), dp(6), dp(10), dp(6)) })
        bubble.addView(card)
        list.addView(bubble)
    }

    // ─── Azioni principali ────────────────────────────────────────
    private fun onCreateRoom() {
        val name = playerNameInputC?.text?.toString()?.trim() ?: ""
        val rName = roomNameInput?.text?.toString()?.trim() ?: ""
        if (name.isEmpty())  { showAlert("Nome richiesto", "Inserisci il tuo nome."); return }
        if (rName.isEmpty()) { showAlert("Nome stanza richiesto", "Dai un nome alla stanza."); return }
        val config = MultiplayerManager.GameConfig(eggCountVal, trapCountVal, penaltySecsVal, riddlesList)
        val progress = showLoading("Creazione stanza \"$rName\"...")
        mp.createRoom(name, rName, isPublicRoom, config,
            onSuccess = { code -> runOnUiThread { progress.dismiss(); buildHostWaitingScreen(code) } },
            onFail    = { msg  -> runOnUiThread { progress.dismiss()
                if (!mp.isFirebaseAvailable()) showFirebaseDialog() else showAlert("Errore", msg) }}
        )
    }

    private fun onJoinRoom(name: String, code: String) {
        val progress = showLoading("Connessione alla stanza...")
        mp.joinRoom(code, name,
            onSuccess = { rName -> runOnUiThread { progress.dismiss()
                toast("✅ Entrato in \"$rName\"!"); buildGuestWaitingScreen() }},
            onFail    = { msg  -> runOnUiThread { progress.dismiss()
                if (!mp.isFirebaseAvailable()) showFirebaseDialog() else showAlert("Impossibile entrare", msg) }}
        )
    }

    private fun onHostStartGame() {
        val config = MultiplayerManager.GameConfig(eggCountVal, trapCountVal, penaltySecsVal, riddlesList)
        mp.startGame(config) { runOnUiThread { launchGameAsHost(config) } }
    }

    private fun launchGameAsHost(config: MultiplayerManager.GameConfig) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putStringArrayListExtra("players", ArrayList(listOf(mp.currentPlayerName)))
            putStringArrayListExtra("riddles", ArrayList(config.riddles))
            putExtra(MultiplayerManager.EXTRA_IS_MP,        true)
            putExtra(MultiplayerManager.EXTRA_IS_HOST,      true)
            putExtra(MultiplayerManager.EXTRA_ROOM_CODE,    mp.currentRoomCode)
            putExtra(MultiplayerManager.EXTRA_ROOM_NAME,    mp.currentRoomName)
            putExtra(MultiplayerManager.EXTRA_PLAYER_ID,    mp.currentPlayerId)
            putExtra(MultiplayerManager.EXTRA_PLAYER_NAME,  mp.currentPlayerName)
            putExtra(MultiplayerManager.EXTRA_EGG_COUNT,    config.eggCount)
            putExtra(MultiplayerManager.EXTRA_TRAP_COUNT,   config.trapCount)
            putExtra(MultiplayerManager.EXTRA_PENALTY_SECS, config.penaltySecs)
            putExtra(EggSetupModeActivity.EXTRA_SETUP_MODE,      "auto")
            putExtra(EggSetupModeActivity.EXTRA_AUTO_EGG_COUNT,  config.eggCount)
            putExtra(EggSetupModeActivity.EXTRA_TRAP_EGG_COUNT,  config.trapCount)
            putExtra(EggSetupModeActivity.EXTRA_PENALTY_SECS,    config.penaltySecs)
        })
    }

    private fun launchGameAsGuest(config: MultiplayerManager.GameConfig) {
        toast("🚀 La partita è iniziata!")
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java).apply {
                putStringArrayListExtra("players", ArrayList(listOf(mp.currentPlayerName)))
                putStringArrayListExtra("riddles", ArrayList(config.riddles))
                putExtra(MultiplayerManager.EXTRA_IS_MP,        true)
                putExtra(MultiplayerManager.EXTRA_IS_HOST,      false)
                putExtra(MultiplayerManager.EXTRA_ROOM_CODE,    mp.currentRoomCode)
                putExtra(MultiplayerManager.EXTRA_ROOM_NAME,    mp.currentRoomName)
                putExtra(MultiplayerManager.EXTRA_PLAYER_ID,    mp.currentPlayerId)
                putExtra(MultiplayerManager.EXTRA_PLAYER_NAME,  mp.currentPlayerName)
                putExtra(MultiplayerManager.EXTRA_EGG_COUNT,    config.eggCount)
                putExtra(MultiplayerManager.EXTRA_TRAP_COUNT,   config.trapCount)
                putExtra(MultiplayerManager.EXTRA_PENALTY_SECS, config.penaltySecs)
                putExtra(EggSetupModeActivity.EXTRA_SETUP_MODE,      "auto")
                putExtra(EggSetupModeActivity.EXTRA_AUTO_EGG_COUNT,  config.eggCount)
                putExtra(EggSetupModeActivity.EXTRA_TRAP_EGG_COUNT,  config.trapCount)
                putExtra(EggSetupModeActivity.EXTRA_PENALTY_SECS,    config.penaltySecs)
            })
        }, 400)
    }

    // ─── Update player list ───────────────────────────────────────
    private fun updatePlayerList(players: List<MultiplayerManager.RoomPlayer>) {
        val v = playerListView ?: return
        v.removeAllViews()
        val medals = listOf("🥇","🥈","🥉","4️⃣","5️⃣","6️⃣")
        if (players.isEmpty()) { v.addView(tv("Nessun giocatore ancora…", 13f, "#66FFFFFF".c)); return }
        players.forEachIndexed { i, p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = buildRoundBg(if (p.id == mp.currentPlayerId) "#CC1A237E" else "#CC1C1C2E", dp(10))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = lp(matchW = true).also { it.bottomMargin = dp(6) }
                alpha = 0f
            }
            row.addView(tv(medals.getOrElse(i) { "👤" }, 18f, Color.WHITE).also {
                it.layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(tv(
                "${p.name}${if (p.id == mp.currentPlayerId) " (tu)" else ""}",
                15f, Color.WHITE,
                bold = p.id == mp.currentPlayerId
            ).also {
                it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(tv(if (p.online) "🟢" else "🔴", 13f, Color.WHITE))
            v.addView(row)
            row.animate().alpha(1f).setDuration(250).setStartDelay(i * 60L)
                .setInterpolator(OvershootInterpolator()).start()
        }
    }

    // ─── Firebase setup dialog ────────────────────────────────────
    private fun showFirebaseDialog() {
        val err = mp.getFirebaseInitError()
        val errSection = if (err.isNotEmpty()) "\n\n⚠️ Errore:\n$err\n" else ""
        AlertDialog.Builder(this)
            .setTitle("⚙️ Firebase — Setup richiesto")
            .setMessage("Per il Multiplayer devi configurare Firebase.$errSection\n" +
                "1. console.firebase.google.com\n" +
                "2. Build → Realtime Database → Crea database\n" +
                "3. Regole: { \".read\": true, \".write\": true }\n" +
                "4. Scarica google-services.json aggiornato\n" +
                "5. Sostituisci il file in app/ e ricompila")
            .setPositiveButton("OK", null).show()
    }

    // ─── UI helpers ──────────────────────────────────────────────
    private fun addHeader(emoji: String, title: String, color: String) {
        root.addView(tv(emoji, 50f, Color.WHITE, gravity = Gravity.CENTER))
        root.addView(tv(title, 24f, Color.parseColor(color), gravity = Gravity.CENTER, bold = true).also {
            it.letterSpacing = 0.06f; it.setPadding(0, dp(4), 0, dp(2))
        })
    }
    private fun addSubtitle(text: String) =
        root.addView(tv(text, 13f, "#AAFFFFFF".c, gravity = Gravity.CENTER).also { it.setPadding(0, dp(2), 0, dp(2)) })
    private fun addSectionHeader(text: String) =
        root.addView(tv(text, 14f, "#AAFFFFFF".c, bold = true).also { it.setPadding(0, dp(4), 0, dp(6)) })
    private fun addSpacer(h: Int) =
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h) })
    private fun addInfoCard(title: String, body: String) {
        val card = CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = dp(4).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC0A1930"))
            layoutParams = lp(matchW = true)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)) }
        col.addView(tv(title, 13f, "#FFD700".c, bold = true).also { it.setPadding(0,0,0,dp(8)) })
        col.addView(tv(body, 12f, "#CCFFFFFF".c).also { it.setLineSpacing(0f, 1.4f) })
        card.addView(col); root.addView(card)
    }
    private fun buildBigActionCard(emoji: String, title: String, sub: String, color: String, onClick: () -> Unit): View {
        val card = CardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = dp(6).toFloat()
            setCardBackgroundColor(Color.parseColor("CC$color".replace("CC#","#CC")))
            isClickable = true; isFocusable = true
            layoutParams = lp(matchW = true)
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)) }
        row.addView(tv(emoji, 32f, Color.WHITE, gravity = Gravity.CENTER).also {
            it.layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(12) }
        }
        texts.addView(tv(title, 16f, Color.WHITE, bold = true))
        texts.addView(tv(sub, 12f, "#CCFFFFFF".c))
        row.addView(texts)
        row.addView(tv("›", 24f, "#88FFFFFF".c))
        card.addView(row); return card
    }
    private fun buildFeatureRow(emoji: String, text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(8) }
        }
        row.addView(tv(emoji, 18f, Color.WHITE).also { it.layoutParams = LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT) })
        row.addView(tv(text, 14f, "#E0FFFFFF".c))
        return row
    }
    private fun buildCounterRow(label: String, init: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit): View {
        var value = init
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = buildRoundBg("#CC1C1C2E", dp(10))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = lp(matchW = true)
        }
        row.addView(tv(label, 13f, "#CCFFFFFF".c).also { it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val valTv = tv(value.toString(), 18f, "#FFD700".c, gravity = Gravity.CENTER, bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val minus = tv("  −  ", 20f, Color.WHITE, gravity = Gravity.CENTER).apply {
            setOnClickListener { if (value - step >= min) { value -= step; valTv.text = value.toString(); onChange(value) } }
        }
        val plus = tv("  +  ", 20f, Color.WHITE, gravity = Gravity.CENTER).apply {
            setOnClickListener { if (value + step <= max) { value += step; valTv.text = value.toString(); onChange(value) } }
        }
        row.addView(minus); row.addView(valTv); row.addView(plus)
        return row
    }
    private fun buildBtn(label: String, bg: String, fg: String, bold: Boolean = false, onClick: () -> Unit): View =
        com.google.android.material.button.MaterialButton(this).apply {
            text = label; textSize = 14f; setTextColor(Color.parseColor(fg))
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(bg))
            cornerRadius = dp(14); strokeWidth = 0; insetTop = 0; insetBottom = 0
            elevation = dp(5).toFloat()
            layoutParams = lp(matchW = true, h = dp(52))
            setOnClickListener { onClick() }
        }
    private fun buildInput(hint: String, defaultFromPlayers: Boolean = false, maxLen: Int = 20): EditText =
        EditText(this).apply {
            this.hint = hint; setHintTextColor("#66FFFFFF".c); setTextColor(Color.WHITE)
            textSize = 16f; background = buildRoundBg("#CC1A237E", dp(12))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(maxLen))
            layoutParams = lp(matchW = true).also { it.bottomMargin = dp(4) }
            if (defaultFromPlayers) {
                val saved = dm.getPlayers().firstOrNull()?.name ?: ""
                if (saved.isNotEmpty()) setText(saved)
            }
        }
    private fun buildRoundBg(colorHex: String, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat(); setColor(Color.parseColor(colorHex))
        }
    private fun copyCode(code: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("room_code", code))
        toast("✅ Codice $code copiato!")
    }
    private fun showAlert(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun showLoading(message: String): AlertDialog {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        row.addView(android.widget.ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also { it.marginEnd = dp(16) }
            isIndeterminate = true
        })
        row.addView(tv(message, 15f, Color.parseColor("#FFFFFF")))
        return AlertDialog.Builder(this).setView(row).setCancelable(false).show().also {
            it.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }
    private fun tv(text: String, size: Float, color: Int, gravity: Int = Gravity.START, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); this.gravity = gravity
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    private fun lp(matchW: Boolean = true, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(if (matchW) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT, h)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private val String.c get() = Color.parseColor(this)
}
