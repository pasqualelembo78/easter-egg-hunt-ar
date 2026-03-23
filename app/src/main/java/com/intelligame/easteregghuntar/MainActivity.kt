package com.intelligame.easteregghuntar

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.intelligame.easteregghuntar.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.SphereNode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * MainActivity v12 — Turni alternati, Suoni, Shape picker, Safe picker, Multi-slot
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private val ADMOB_BANNER_ID   get() = BuildConfig.ADMOB_BANNER_ID
        private val ADMOB_REWARDED_ID get() = BuildConfig.ADMOB_REWARDED_ID

        private val EGG_COLORS = listOf(
            0xFFE8336D.toInt(), 0xFFFFCC00.toInt(), 0xFF8A2BE2.toInt(),
            0xFF00B894.toInt(), 0xFFFF6B35.toInt(), 0xFF0099CC.toInt()
        )
        private val KEY_COLORS = listOf(
            0xFFFF80B0.toInt(), 0xFFFFE566.toInt(), 0xFFBB80F0.toInt(),
            0xFF66DCC8.toInt(), 0xFFFFAA77.toInt(), 0xFF66CCEE.toInt()
        )
        private val EGG_LABELS = listOf("\uD83E\uDDE1","\uD83D\uDC9B","\uD83D\uDC9C","\uD83D\uDC9A","\uD83E\uDDE1","\uD83D\uDC99")
        private val TRAP_COLOR = 0xFFCC0000.toInt()
    }

    private enum class GamePhase { SCAN_ROOM, SETUP_SAFE, SETUP_EGGS, PLAYING, STATS }
    private enum class PlayState  { SEARCHING, NEAR_EGG, THROWING, KEY_OBTAINED, NEAR_SAFE, TICKET_SHOWN }

    // ─── Modello uovo ─────────────────────────────────────────────
    private inner class EggObject(
        val id: Int, val colorIdx: Int, val shape: String,
        val anchorNode: AnchorNode, val eggNode: Node,
        var isTrap: Boolean = false,
        val trapMarkerNode: SphereNode? = null,
        var pulseAnim: ValueAnimator? = null
    )

    private inner class SafeObject(
        val type: String,
        val anchorNode: AnchorNode,
        val bodyNode: Node,
        val doorNode: Node,
        val dialNode: CylinderNode,
        val keySlots: MutableList<CylinderNode> = mutableListOf()
    )

    // ─── Stato ────────────────────────────────────────────────────
    private lateinit var binding: ActivityMainBinding
    private lateinit var dm: GameDataManager

    private val eggs      = mutableListOf<EggObject>()
    private var safeObject: SafeObject? = null
    private var selectedEgg: EggObject? = null

    private var gamePhase = GamePhase.SETUP_SAFE
    private var playState = PlayState.SEARCHING
    private var currentEggIdx = 0
    private var keyInPocket   = false
    private var realEggsCaught = 0

    private var activePlayers = mutableListOf<String>()
    private var currentPlayerIdx = 0
    // Turni alternati: eggOwners[i] = giocatore assegnato all'uovo i
    private var eggOwners = mutableListOf<String>()
    private val currentPlayer: String
        get() = when (turnMode) {
            "alternating" -> eggOwners.getOrElse(currentEggIdx) { activePlayers.firstOrNull() ?: "Giocatore" }
            else          -> activePlayers.getOrElse(currentPlayerIdx) { "Giocatore" }
        }

    private var riddles = mutableListOf<String>()
    private var isMultiplayer      = false
    private var isMultiplayerHost  = false
    private var mpRoomCode         = ""
    private var mpPlayerId         = ""
    private var mpPlayerName       = ""
    private lateinit var mpManager: MultiplayerManager

    private var planeDetected = false
    private var lastArFrame: Frame? = null
    private var frameCount = 0
    private var trackingLostFrames = 0
    private var lastTrackingHintMs = 0L

    // ─── Nuove modalita' ──────────────────────────────────────────
    private var turnMode       = "sequential"
    private var eggSetupMode   = "manual"
    private var autoEggCount   = 4
    private var trapEggCount   = 0
    private var penaltySecs    = 30
    private var nextEggIsTrap  = false
    private var nextEggShape   = "sphere"
    private var selectedSafeType = "classic"

    // ─── Modalità AR ─────────────────────────────────────────────
    // "standard"  → solo piani (comportamento precedente)
    // "depth"     → Depth API: qualsiasi superficie
    // "room_scan" → scansione stanza + persistenza locale gratuita
    private var arMode = "depth"
    private val cloudAnchorManager = CloudAnchorManager()   // stub vuoto, non usato
    private val roomScanManager    = RoomScanManager()
    // Anchor salvati localmente (solo in room_scan mode)
    private lateinit var localAnchorStore: LocalAnchorStore
    private var localAnchorSessionId = ""   // ID sessione corrente salvata
    private var localAnchors = mutableListOf<LocalAnchorStore.LocalAnchor>()
    // Badge di stato (nessuna chiamata di rete necessaria)
    private var localSaveCount = 0

    // ─── Stats per turni alternati ────────────────────────────────
    // eggTimesMs[i] = tempo per trovare l'uovo i-esimo
    private val eggTimesMs = mutableListOf<Long>()
    private var penaltyAccumMs = 0L

    // ─── Ripristino ───────────────────────────────────────────────
    private var loadedSession: GameDataManager.SavedSession? = null
    private var isRestoreMode = false
    private var restoreSlotId = ""

    @Volatile private var isActive = false

    // ─── Timer ────────────────────────────────────────────────────
    private var huntStartMs = 0L; private var eggStartMs = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (gamePhase == GamePhase.PLAYING && playState != PlayState.TICKET_SHOWN)
                binding.tvTimer.text = fmtMs(SystemClock.elapsedRealtime() - huntStartMs + penaltyAccumMs)
            timerHandler.postDelayed(this, 500)
        }
    }

    // ─── Swipe lancio ─────────────────────────────────────────────
    private var swipeStartX = 0f; private var swipeStartY = 0f
    private var swipeStartTime = 0L; private var throwInProgress = false
    private var basketSizePx = 0f
    private var isMenuOpen = false
    private var rewardedAd: RewardedAd? = null
    private var hintCooldownUntilMs = 0L

    private val pickRiddlesInGame = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                riddles = r.readLines().filter { it.isNotBlank() }.toMutableList()
                Toast.makeText(this, "Caricati ${riddles.size} indovinelli", Toast.LENGTH_LONG).show()
                binding.menuTvRiddleCount.text = "${riddles.size} indovinelli caricati"
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Errore lettura file", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        dm = GameDataManager.get(this)
        localAnchorStore = LocalAnchorStore.get(this)
        // Pulizia sessioni scadute all'avvio (operazione veloce, locale)
        localAnchorStore.purgeExpired()
        SoundManager.enabled = dm.getSoundEnabled()
        basketSizePx = 60f * resources.displayMetrics.density

        activePlayers = intent.getStringArrayListExtra("players")?.toMutableList()
            ?: dm.getPlayers().map { it.name }.toMutableList()

        val riddlesFromIntent = intent.getStringArrayListExtra("riddles")
        if (!riddlesFromIntent.isNullOrEmpty()) riddles = riddlesFromIntent.toMutableList()
        else loadRiddles()

        turnMode       = intent.getStringExtra(GameModeActivity.EXTRA_TURN_MODE) ?: "sequential"
        eggSetupMode   = intent.getStringExtra(EggSetupModeActivity.EXTRA_SETUP_MODE) ?: "manual"
        autoEggCount   = intent.getIntExtra(EggSetupModeActivity.EXTRA_AUTO_EGG_COUNT, 4)
        trapEggCount   = intent.getIntExtra(EggSetupModeActivity.EXTRA_TRAP_EGG_COUNT, 0)
        penaltySecs    = intent.getIntExtra(EggSetupModeActivity.EXTRA_PENALTY_SECS, 30)

        isRestoreMode = intent.getBooleanExtra("restore_mode", false)
        restoreSlotId = intent.getStringExtra("restore_slot_id") ?: ""
        if (isRestoreMode) {
            loadedSession = if (restoreSlotId.isNotBlank()) dm.loadSaveSlot(restoreSlotId)
                            else dm.loadSession()
            loadedSession?.let {
                riddles = it.riddles.toMutableList()
                activePlayers = it.players.toMutableList()
                turnMode = it.turnMode
            }
        }

        // ─── Modalità AR ──────────────────────────────────────────
        arMode = intent.getStringExtra("ar_mode") ?: dm.getArMode()
        // In room_scan mode: avvia fase SCAN_ROOM prima di SETUP_SAFE
        if (arMode == "room_scan") gamePhase = GamePhase.SCAN_ROOM

        initAdMob(); setupButtons(); setupBackHandler(); checkCameraPermission(); updateUI()

        // ─── Multiplayer init ──────────────────────────────────────
        isMultiplayer     = intent.getBooleanExtra(MultiplayerManager.EXTRA_IS_MP, false)
        isMultiplayerHost = intent.getBooleanExtra(MultiplayerManager.EXTRA_IS_HOST, false)
        mpRoomCode        = intent.getStringExtra(MultiplayerManager.EXTRA_ROOM_CODE) ?: ""
        mpPlayerId        = intent.getStringExtra(MultiplayerManager.EXTRA_PLAYER_ID) ?: ""
        mpPlayerName      = intent.getStringExtra(MultiplayerManager.EXTRA_PLAYER_NAME) ?: ""
        mpManager         = MultiplayerManager.get()

        if (isMultiplayer && mpRoomCode.isNotEmpty()) {
            val roomName = intent.getStringExtra(MultiplayerManager.EXTRA_ROOM_NAME) ?: mpRoomCode
            binding.mpLeaderboardCard.visibility = android.view.View.VISIBLE
            binding.mpChatFab.visibility         = android.view.View.VISIBLE
            binding.mpChatTitle.text             = "💬  Chat — $roomName"
            mpManager.onScoresChanged = { scores -> runOnUiThread { updateMpLeaderboard(scores) } }
            mpManager.onChatMessage   = { msg    -> runOnUiThread { handleInGameChat(msg) } }
            mpManager.onError = { msg -> runOnUiThread {
                Toast.makeText(this, "MP: $msg", Toast.LENGTH_SHORT).show()
            }}
            // FAB apre/chiude la chat
            binding.mpChatFab.setOnClickListener { toggleChatOverlay() }
            binding.mpChatClose.setOnClickListener { toggleChatOverlay() }
            binding.mpChatSendBtn.setOnClickListener { sendInGameChat() }
            binding.mpChatInput.setOnEditorActionListener { _, _, _ -> sendInGameChat(); true }
        }
        // ──────────────────────────────────────────────────────────

        SoundManager.playIntro()
    }

    override fun onResume()  { super.onResume();  isActive = true  }
    override fun onPause()   { isActive = false;  super.onPause()  }
    override fun onDestroy() {
        isActive = false; super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        scanLiveBadgeAnim?.cancel()
        newSurfaceHideHandler?.removeCallbacksAndMessages(null)
        val copy = eggs.toList(); eggs.clear()
        copy.forEach { egg -> egg.pulseAnim?.cancel(); try { egg.anchorNode.destroy() } catch (_: Exception) {} }
        try { safeObject?.anchorNode?.destroy() } catch (_: Exception) {}
        roomScanManager.reset()
        if (isMultiplayer) { try { mpManager.disconnect() } catch (_: Exception) {} }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        isMenuOpen -> closeInGameMenu()
                        gamePhase == GamePhase.PLAYING -> openInGameMenu()
                        else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                    }
                }
            }
        )
    }

    // ─── AdMob ────────────────────────────────────────────────────
    private fun initAdMob() {
        MobileAds.initialize(this) { loadRewardedAd() }
        val adView = AdView(this).apply { setAdSize(AdSize.BANNER); adUnitId = ADMOB_BANNER_ID }
        binding.adBannerContainer.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    /** Precarica un Rewarded Ad per il pulsante Indizio. */
    private fun loadRewardedAd() {
        RewardedAd.load(this, ADMOB_REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    rewardedAd!!.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() { rewardedAd = null; loadRewardedAd() }
                        override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) {
                            rewardedAd = null; loadRewardedAd()
                        }
                    }
                }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    rewardedAd = null
                    timerHandler.postDelayed({ loadRewardedAd() }, 30_000)
                }
            })
    }

    // ─── Permessi ─────────────────────────────────────────────────
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) setupAR()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == CAMERA_PERMISSION_CODE && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            setupAR()
        } else {
            android.app.AlertDialog.Builder(this)
                .setTitle("📷 Fotocamera necessaria")
                .setMessage("Easter Egg Hunt AR ha bisogno della fotocamera per funzionare.\n\nVuoi abilitarla nelle impostazioni?")
                .setPositiveButton("Apri impostazioni") { _, _ ->
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)
                    ))
                }
                .setNegativeButton("Riprova") { _, _ ->
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
                }
                .setNeutralButton("Esci") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun loadRiddles() {
        try {
            assets.open("riddles.txt").bufferedReader().use { r ->
                riddles = r.readLines().filter { it.isNotBlank() }.toMutableList()
            }
        } catch (_: Exception) {
            riddles = mutableListOf(
                "Dove ogni mattina profuma di caldo - cerca vicino alla macchina del caffe\'",
                "Dove la notte arrivano i sogni - guarda sotto il cuscino del letto",
                "Dove la natura entra in casa - cerca tra le piante del balcone",
                "Dove si conservano le cose preziose - guarda nell\'armadio dei giochi"
            )
        }
    }

    // ─── Setup AR ─────────────────────────────────────────────────
    private fun setupAR() {
        try {
        binding.sceneView.apply {
            planeRenderer.isEnabled = true
            configureSession { session, config ->
                // Pianificazione piani sempre attiva
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                config.focusMode = Config.FocusMode.AUTO

                // Depth Mode: attiva in "depth" e "room_scan", disattiva solo in "standard"
                config.depthMode = when {
                    arMode == "standard" -> Config.DepthMode.DISABLED
                    session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
                    else -> Config.DepthMode.DISABLED  // fallback se il device non ha Depth
                }

                // Cloud Anchors rimossi — usiamo LocalAnchorStore (gratuito, locale)
                config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
            }
            onSessionUpdated = handler@{ session, frame ->
                if (!isActive) return@handler
                lastArFrame = frame

                // LocalAnchorStore non richiede aggiornamenti per frame (è locale, sincrono)

                // ── Scan Room: aggiornamento progresso ────────────────────────────
                if (gamePhase == GamePhase.SCAN_ROOM && roomScanManager.isScanning) {
                    val prog  = roomScanManager.getProgressPercent(session)
                    val secs  = roomScanManager.getRemainingSeconds()
                    val surfs = roomScanManager.getSurfaceDescriptions(session)
                    val count = roomScanManager.getTrackedPlaneCount(session)
                    runOnUiThread {
                        updateScanProgress(prog, secs, surfs, count)
                    }
                    if (roomScanManager.isScanComplete(session)) {
                        runOnUiThread { onScanComplete() }
                    }
                }

                // ── Monitoraggio tracking ─────────────────────────────────────────
                val camTracking = frame.camera.trackingState
                if (camTracking == TrackingState.TRACKING) {
                    if (trackingLostFrames > 30) {
                        runOnUiThread {
                            binding.statusDot.setBackgroundResource(R.drawable.circle_green)
                            binding.tvStatus.text = if (planeDetected) "Piano trovato" else "Cerco superfici..."
                        }
                    }
                    trackingLostFrames = 0
                } else {
                    val nowMs = System.currentTimeMillis()
                    if (++trackingLostFrames > 20 && nowMs - lastTrackingHintMs > 3000L) {
                        lastTrackingHintMs = nowMs
                        runOnUiThread { handleTrackingLost(frame.camera.trackingFailureReason) }
                    }
                }

                if (!planeDetected && camTracking == TrackingState.TRACKING) {
                    val hasPlane = session.getAllTrackables(Plane::class.java)
                        .any { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                    if (hasPlane) { planeDetected = true; runOnUiThread { onFirstPlaneDetected() } }
                }
                if (gamePhase == GamePhase.PLAYING &&
                        playState != PlayState.THROWING && playState != PlayState.TICKET_SHOWN &&
                        ++frameCount % 6 == 0 &&
                        camTracking == TrackingState.TRACKING) checkProximity(frame)
            }
            onTouchEvent = { event, svHit -> handleTouch(event, svHit?.node); true }
        }

        // Avvia automaticamente la scansione se siamo in room_scan
        if (arMode == "room_scan") {
            timerHandler.postDelayed({
                if (isActive && gamePhase == GamePhase.SCAN_ROOM) startRoomScan()
            }, 1000L)
        }

        // Suggerimento proattivo se dopo 10s non si trova ancora il piano
        timerHandler.postDelayed({
            if (!planeDetected && isActive &&
                (gamePhase == GamePhase.SETUP_SAFE || gamePhase == GamePhase.SCAN_ROOM)) {
                runOnUiThread {
                    showTemporaryOverlay(
                        "💡 Difficoltà con la superficie?\n" +
                        "Muovi lentamente il telefono\npuntando verso il pavimento.\n" +
                        "Evita superfici molto uniformi\no con scarsa illuminazione.", 5000L)
                }
            }
        }, 10_000L)
        } catch (e: Exception) {
            android.util.Log.e("EggHunt", "ARCore init failed: ${e.message}", e)
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle("⚠️ AR non disponibile")
                    .setMessage("Il tuo dispositivo non supporta ARCore o la versione installata è troppo vecchia.\n\nAggiorna ARCore dal Play Store e riprova.")
                    .setPositiveButton("Apri Play Store") { _, _ ->
                        try {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=com.google.ar.core")))
                        } catch (_: Exception) {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.ar.core")))
                        }
                    }
                    .setNegativeButton("Esci") { _, _ -> finish() }
                    .setCancelable(false).show()
            }
        }
    }

    // ─── Room Scan ────────────────────────────────────────────────

    // Conta i piani già noti per rilevare le nuove aree
    private var lastKnownPlaneCount = 0
    private var scanLiveBadgeAnim: ValueAnimator? = null
    private var newSurfaceHideHandler: Handler? = null

    private fun startRoomScan() {
        roomScanManager.startScan()
        lastKnownPlaneCount = 0

        // Abilita il plane renderer con colori vivaci in scan mode:
        // ARCore disegna già i piani rilevati come griglie — li lasciamo visibili
        // così l'utente vede in tempo reale cosa è già stato mappato.
        binding.sceneView.planeRenderer.isEnabled = true

        runOnUiThread {
            binding.scanOverlay.visibility = View.VISIBLE
            binding.scanGuideCenter.visibility = View.VISIBLE
            binding.tvScanTitle.text = "🏠 Scansione stanza"
            binding.tvScanInstruction.text =
                "Punta la fotocamera e cammina — pavimento, pareti, mobili"
            binding.scanProgressBar.progress = 0
            binding.tvScanPercent.text = "0%"
            binding.tvScanSurfaces.text = "🔍 In attesa di superfici..."
            binding.tvNewSurface.visibility = View.INVISIBLE

            // Badge "REC" pulsante rosso
            startLiveBadgePulse()

            binding.btnSkipScan.setOnClickListener {
                roomScanManager.forceComplete()
                onScanComplete()
            }
        }
    }

    /** Animazione pulsante del badge ● REC */
    private fun startLiveBadgePulse() {
        scanLiveBadgeAnim?.cancel()
        scanLiveBadgeAnim = ObjectAnimator.ofFloat(binding.tvScanLiveBadge, "alpha", 1f, 0.2f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Aggiornamento HUD ogni frame — chiamato dal thread AR, poi runOnUiThread */
    private fun updateScanProgress(percent: Int, remainingSecs: Long, surfaces: String, planeCount: Int) {
        if (!isActive) return

        binding.scanProgressBar.progress = percent
        binding.tvScanPercent.text = "$percent%"

        // Cambia colore barra: giallo sotto 40%, arancio 40-70%, verde sopra
        val barColor = when {
            percent >= 70 -> 0xFF4CAF50.toInt()   // verde
            percent >= 40 -> 0xFFFF9800.toInt()   // arancio
            else          -> 0xFFFFD700.toInt()   // giallo
        }
        binding.scanProgressBar.progressTintList =
            android.content.res.ColorStateList.valueOf(barColor)

        val timeStr = when {
            remainingSecs <= 0 -> "Quasi fatto..."
            remainingSecs < 5  -> "⚡ ${remainingSecs}s"
            else               -> "~${remainingSecs}s"
        }
        binding.tvScanInstruction.text = when {
            percent < 20 -> "Punta verso il pavimento e cammina — $timeStr"
            percent < 50 -> "Ora punta verso pareti e mobili — $timeStr"
            percent < 80 -> "Ottimo! Cerca angoli e sotto i mobili — $timeStr"
            else         -> "🎉 Copertura eccellente! $timeStr"
        }
        binding.tvScanSurfaces.text = "🔍 $surfaces"

        // Nascondi guida centrale una volta che ci sono piani
        if (planeCount > 0) binding.scanGuideCenter.visibility = View.GONE

        // Flash "✨ Nuova area!" quando viene rilevato un nuovo piano
        if (planeCount > lastKnownPlaneCount) {
            lastKnownPlaneCount = planeCount
            flashNewSurface()
        }
    }

    /** Flash "✨ Nuova area!" per 1.5 secondi */
    private fun flashNewSurface() {
        binding.tvNewSurface.visibility = View.VISIBLE
        binding.tvNewSurface.alpha = 1f
        newSurfaceHideHandler?.removeCallbacksAndMessages(null)
        newSurfaceHideHandler = Handler(Looper.getMainLooper()).also {
            it.postDelayed({
                ObjectAnimator.ofFloat(binding.tvNewSurface, "alpha", 1f, 0f).apply {
                    duration = 600
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            binding.tvNewSurface.visibility = View.INVISIBLE
                        }
                    })
                    start()
                }
            }, 900L)
        }
    }

    private fun onScanComplete() {
        if (!isActive || gamePhase != GamePhase.SCAN_ROOM) return
        roomScanManager.stopScan()
        scanLiveBadgeAnim?.cancel()
        newSurfaceHideHandler?.removeCallbacksAndMessages(null)
        gamePhase = GamePhase.SETUP_SAFE
        binding.scanOverlay.visibility = View.GONE
        // Mantieni i piani visibili anche dopo la scansione (aiuta a capire dove posizionare)
        binding.sceneView.planeRenderer.isEnabled = true
        showTemporaryOverlay(
            "✅ Stanza mappata!\n\nOra tocca qualsiasi\nsuperficie colorata per\npiazzare la cassaforte.", 4000L)
        updateUI()
    }

    // ─── Prossimita' ──────────────────────────────────────────────
    private fun checkProximity(frame: Frame) {
        if (!isActive || isFinishing || isDestroyed) return
        val target = eggs.getOrNull(currentEggIdx) ?: return
        try {
            val cam       = frame.camera.pose.translation
            val eggDist   = dist3(cam, target.anchorNode.anchor.pose.translation)
            val safeDist  = safeObject?.let { dist3(cam, it.anchorNode.anchor.pose.translation) } ?: Float.MAX_VALUE
            val revealD   = dm.getRevealDistMeters()
            val catchD    = dm.getCatchDistMeters()

            val shouldShow = !keyInPocket && eggDist < revealD
            if (target.anchorNode.isVisible != shouldShow) {
                target.anchorNode.isVisible = shouldShow
                if (shouldShow) runOnUiThread { startEggPulse(target) }
                else target.pulseAnim?.cancel()
            }

            val newState: PlayState? = when {
                playState == PlayState.SEARCHING   && !keyInPocket && eggDist < catchD -> PlayState.NEAR_EGG
                playState == PlayState.NEAR_EGG    && eggDist >= catchD                -> PlayState.SEARCHING
                playState == PlayState.KEY_OBTAINED && safeDist < 1.5f                 -> PlayState.NEAR_SAFE
                playState == PlayState.NEAR_SAFE   && safeDist >= 1.5f                 -> PlayState.KEY_OBTAINED
                else -> null
            }
            if (newState != null) runOnUiThread {
                if (isActive && !isFinishing) { playState = newState; updateUI() }
            }
        } catch (_: Exception) {}
    }

    // ─── Touch ────────────────────────────────────────────────────
    private fun handleTouch(event: MotionEvent, hitNode: Node?) {
        if (isMenuOpen) return
        when (gamePhase) {
            GamePhase.SCAN_ROOM  -> { /* nessun touch durante la scansione */ }
            GamePhase.SETUP_SAFE -> if (event.actionMasked == MotionEvent.ACTION_UP)
                arSurfaceHitTest(event)?.let { placeSafe(it) }
            GamePhase.SETUP_EGGS -> handleSetupTouch(event, hitNode)
            GamePhase.PLAYING    -> handlePlayingTouch(event)
            else -> Unit
        }
    }

    private fun handleSetupTouch(event: MotionEvent, hitNode: Node?) {
        if (event.actionMasked != MotionEvent.ACTION_UP) return
        if (eggSetupMode == "auto") return

        // SEMPRE usa l'ARCore plane hit-test come fonte primaria.
        // L'hit-test 3D di SceneView (hitNode) intercetta i nodi uovo anche quando
        // l'utente tocca il pavimento *vicino* a un uovo, causando selezioni false
        // invece di piazzare nuove uova. Usiamo la prossimita' in coordinate mondo
        // (piano XZ) per decidere se il tap e' "su un uovo" o "sul pavimento libero".
        val arHit = arSurfaceHitTest(event)
        if (arHit != null) {
            val hp = arHit.hitPose.translation
            val nearby = eggs.minByOrNull { egg ->
                val p = egg.anchorNode.anchor.pose.translation
                val dx = hp[0] - p[0]; val dz = hp[2] - p[2]
                dx * dx + dz * dz
            }?.takeIf { egg ->
                val p = egg.anchorNode.anchor.pose.translation
                val dx = hp[0] - p[0]; val dz = hp[2] - p[2]
                kotlin.math.sqrt(dx * dx + dz * dz) < 0.22f
            }
            if (nearby != null) {
                if (selectedEgg == nearby) deselectEgg() else selectEgg(nearby)
            } else {
                deselectEgg()
                placeEgg(arHit, nextEggIsTrap, nextEggShape)
            }
        } else {
            val tappedEgg = eggs.find { it.eggNode == hitNode || it.trapMarkerNode == hitNode }
            if (tappedEgg != null) {
                if (selectedEgg == tappedEgg) deselectEgg() else selectEgg(tappedEgg)
            } else {
                deselectEgg()
            }
        }
    }

    private fun selectEgg(egg: EggObject) {
        deselectEgg()
        selectedEgg = egg
        egg.eggNode.scale = Scale(1.35f, 1.35f * 1.45f, 1.35f)
        binding.btnDeleteEgg.visibility = View.VISIBLE
        val trapLbl = if (egg.isTrap) " [TRAPPOLA]" else ""
        binding.tvInstruction.text = "Uovo #${egg.id + 1}$trapLbl selezionato"
    }

    private fun deselectEgg() {
        selectedEgg?.eggNode?.scale = Scale(1f, 1.45f, 1f)
        selectedEgg = null; binding.btnDeleteEgg.visibility = View.GONE
        if (gamePhase == GamePhase.SETUP_EGGS) binding.tvInstruction.text = setupInstructionText()
    }

    private fun deleteSelectedEgg() {
        val egg = selectedEgg ?: return
        egg.pulseAnim?.cancel(); egg.anchorNode.destroy()
        eggs.remove(egg); selectedEgg = null
        binding.btnDeleteEgg.visibility = View.GONE
        runOnUiThread { updateUI() }
        Toast.makeText(this, "Uovo eliminato", Toast.LENGTH_SHORT).show()
    }

    private fun setupInstructionText() = when (eggSetupMode) {
        "auto"     -> "Uova piazzate in automatico - avvia la caccia"
        "combined" -> "Tocca pavimento per aggiungerne - tocca uovo per selezionare"
        else       -> "Tocca il pavimento - tocca un uovo per selezionarlo"
    }

    private fun handlePlayingTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x; swipeStartY = event.y
                swipeStartTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                if (playState != PlayState.NEAR_EGG || throwInProgress) return
                val dy = event.y - swipeStartY
                val dt = (System.currentTimeMillis() - swipeStartTime).coerceAtLeast(1L)
                val valid = dy < -90f && (dy / dt) * 1000f < -250f && swipeStartY > binding.root.height * 0.4f
                if (valid) { SoundManager.playThrow(); launchBasket(swipeStartX, swipeStartY, abs(event.x - swipeStartX) < abs(dy) * 1.8f) }
            }
        }
    }

    // ─── Lancio cestino ───────────────────────────────────────────
    private fun launchBasket(fromX: Float, fromY: Float, hit: Boolean) {
        throwInProgress = true; playState = PlayState.THROWING
        val basket = binding.throwBasket
        val sw = binding.root.width.toFloat(); val sh = binding.root.height.toFloat()
        basket.visibility = View.VISIBLE; basket.alpha = 1f; basket.scaleX = 1f; basket.scaleY = 1f
        basket.translationX = fromX - basketSizePx / 2; basket.translationY = fromY - basketSizePx / 2
        basket.animate()
            .translationX((if (hit) sw / 2f else sw * 0.82f) - basketSizePx / 2)
            .translationY(sh * 0.15f - basketSizePx / 2)
            .scaleX(if (hit) 2.2f else 1.3f).scaleY(if (hit) 2.2f else 1.3f)
            .alpha(0f).setDuration(560).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                basket.visibility = View.GONE; basket.scaleX = 1f; basket.scaleY = 1f
                basket.translationX = 0f; basket.translationY = 0f
                if (hit) onThrowHit() else onThrowMiss()
            }.start()
    }

    private fun onThrowHit() {
        throwInProgress = false
        val egg = eggs.getOrNull(currentEggIdx) ?: return
        egg.pulseAnim?.cancel()

        if (egg.isTrap) {
            egg.anchorNode.isVisible = false
            penaltyAccumMs += penaltySecs * 1000L
            SoundManager.playTrap()
            binding.catchBurst.visibility = View.VISIBLE; binding.catchBurst.alpha = 1f
            binding.catchBurst.setTextColor(AndroidColor.parseColor("#FF4444"))
            binding.catchBurst.text = "\u26A0\uFE0F\nTRAPPOLA!\n+${penaltySecs}s"
            binding.catchBurst.animate().alpha(0f).setStartDelay(700).setDuration(1800)
                .withEndAction { binding.catchBurst.visibility = View.GONE
                    binding.catchBurst.setTextColor(AndroidColor.parseColor("#FFD700")) }.start()
            val nextIdx = currentEggIdx + 1
            currentEggIdx = nextIdx; keyInPocket = false
            timerHandler.postDelayed({
                if (!isActive || isFinishing) return@postDelayed
                runOnUiThread {
                    if (currentEggIdx >= eggs.size) finishGame()
                    else { eggStartMs = SystemClock.elapsedRealtime(); playState = PlayState.SEARCHING; updateUI() }
                }
            }, 2400)
            runOnUiThread {
                Toast.makeText(this, "TRAPPOLA! +${penaltySecs} secondi!", Toast.LENGTH_LONG).show()
                playState = PlayState.SEARCHING; updateUI()
            }
            return
        }

        // Uovo reale
        SoundManager.playEggFound()
        val elapsed = SystemClock.elapsedRealtime() - eggStartMs
        eggTimesMs.add(elapsed); realEggsCaught++

        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 400; interpolator = AccelerateInterpolator()
            addUpdateListener { anim -> val s = anim.animatedValue as Float; egg.eggNode.scale = Scale(s, s * 1.45f, s) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { egg.anchorNode.isVisible = false }
            })
            start()
        }
        keyInPocket = true; playState = PlayState.KEY_OBTAINED

        // ─── Multiplayer sync ─────────────────────────────────────
        if (isMultiplayer && mpRoomCode.isNotEmpty()) {
            val totalElapsed = SystemClock.elapsedRealtime() - huntStartMs + penaltyAccumMs
            mpManager.reportEggFound(currentEggIdx, elapsed)
            mpManager.updateMyScore(realEggsCaught, totalElapsed)
        }
        // ─────────────────────────────────────────────────────────

        binding.catchBurst.visibility = View.VISIBLE; binding.catchBurst.alpha = 1f
        binding.catchBurst.setTextColor(AndroidColor.parseColor("#FFD700"))
        val label = EGG_LABELS[currentEggIdx % EGG_LABELS.size]
        binding.catchBurst.text = "$label\nPRESA!\n${fmtMs(elapsed)}"
        binding.catchBurst.animate().alpha(0f).setStartDelay(400).setDuration(1600)
            .withEndAction { binding.catchBurst.visibility = View.GONE }.start()

        runOnUiThread {
            updateUI()
            Toast.makeText(this, "Chiave #$realEggsCaught! Vai alla cassaforte!", Toast.LENGTH_LONG).show()
        }
    }

    private fun onThrowMiss() {
        throwInProgress = false; playState = PlayState.NEAR_EGG
        runOnUiThread { Toast.makeText(this, "Quasi! Riprova!", Toast.LENGTH_SHORT).show(); updateUI() }
    }

    // ─── Cassaforte ───────────────────────────────────────────────
    private fun insertKeyInSafe() {
        if (!keyInPocket || safeObject == null) return
        SoundManager.playKeyInsert()
        val safe = safeObject!!
        val keyColor = KEY_COLORS[(realEggsCaught - 1) % KEY_COLORS.size]
        val sv = binding.sceneView
        val mat = sv.materialLoader.createColorInstance(color = keyColor)
        val filled = CylinderNode(engine = sv.engine, radius = 0.020f, height = 0.016f, materialInstance = mat).apply {
            position = slotPositionFor(realEggsCaught - 1); rotation = Rotation(90f, 0f, 0f)
        }
        safe.anchorNode.addChildNode(filled); safe.keySlots.add(filled)

        val curY = safe.dialNode.rotation.y
        ValueAnimator.ofFloat(curY, curY + 90f).apply {
            duration = 800; interpolator = DecelerateInterpolator()
            addUpdateListener { anim -> safe.dialNode.rotation = Rotation(90f, anim.animatedValue as Float, 0f) }
            start()
        }

        timerHandler.postDelayed({
            if (!isActive || isFinishing) return@postDelayed
            openSafeDoor {
                SoundManager.playSafeOpen()
                val riddleText = riddles.getOrNull(currentEggIdx) ?: ""
                if (riddleText.isNotEmpty()) showTicket(currentEggIdx + 1, riddleText)
                else onTicketClosed()
            }
        }, 600)
    }

    private fun slotPositionFor(idx: Int): Position {
        val col = (idx % 3) - 1; val row = idx / 3
        return Position(col * 0.055f, 0.22f - row * 0.065f, 0.148f)
    }

    private fun openSafeDoor(onComplete: () -> Unit) {
        val safe = safeObject ?: run { onComplete(); return }

        // Fase 1 (0-700ms): il manubrio gira veloce come una combinazione
        val startDialY = safe.dialNode.rotation.y
        ValueAnimator.ofFloat(0f, 720f).apply {
            duration = 700; interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                safe.dialNode.rotation = Rotation(90f, startDialY + (anim.animatedValue as Float), 0f)
            }
            start()
        }

        // Fase 2 (600ms+): flash dorato + porta che si spalanca con overshoot
        timerHandler.postDelayed({
            if (!isActive || isFinishing) return@postDelayed
            // Flash dorato sull'intera AR view
            val flashView = View(this).apply {
                setBackgroundColor(AndroidColor.parseColor("#FFDD44"))
                alpha = 0f
                layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.MATCH_PARENT)
            }
            binding.arContainer.addView(flashView)
            flashView.animate().alpha(0.65f).setDuration(110).withEndAction {
                flashView.animate().alpha(0f).setDuration(420)
                    .withEndAction { try { binding.arContainer.removeView(flashView) } catch (_: Exception) {} }
                    .start()
            }.start()

            // Porta si spalanca con effetto overshoot
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 950; interpolator = OvershootInterpolator(0.5f)
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    safe.doorNode.rotation = Rotation(0f, t * -88f, 0f)
                    safe.doorNode.scale    = Scale(1f - t * 0.55f, 1f, 1f)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        launchGoldenBurst()
                        onComplete()
                    }
                })
                start()
            }
        }, 600)
    }

    /** Esplosione di sparkle dorati quando la cassaforte si apre */
    private fun launchGoldenBurst() {
        if (!isActive || isFinishing) return
        val goldenEmojis = listOf("✨","🌟","💫","⭐","✨","💛","🌟","✨","🎊","💎","✨","🌟")
        val root = binding.arContainer
        val (w, h) = windowSizePx()
        goldenEmojis.forEachIndexed { i, emoji ->
            val tv = TextView(this).apply {
                text = emoji; textSize = 30f; alpha = 0f
                x = w * 0.5f + (Random.nextFloat() - 0.5f) * w * 0.75f
                y = h * 0.38f + (Random.nextFloat() - 0.5f) * h * 0.38f
                layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT)
            }
            root.addView(tv)
            // Usa timerHandler (campo Activity) invece di Handler anonimi — evita leak
            timerHandler.postDelayed({
                if (!isActive) { try { root.removeView(tv) } catch (_: Exception) {}; return@postDelayed }
                tv.animate().alpha(1f).scaleX(2.4f).scaleY(2.4f).setDuration(190)
                    .withEndAction {
                        timerHandler.postDelayed({
                            if (!isActive) { try { root.removeView(tv) } catch (_: Exception) {}; return@postDelayed }
                            tv.animate().alpha(0f).scaleX(0.1f).scaleY(0.1f)
                                .translationYBy(-160f + Random.nextFloat() * -100f)
                                .translationXBy((Random.nextFloat() - 0.5f) * 260f)
                                .setDuration(560).setInterpolator(AccelerateInterpolator())
                                .withEndAction { try { root.removeView(tv) } catch (_: Exception) {} }
                                .start()
                        }, 180)
                    }.start()
            }, i * 65L)
        }
    }

    private fun closeSafeDoor(onComplete: () -> Unit) {
        val safe = safeObject ?: run { onComplete(); return }
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 700; interpolator = AccelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                safe.doorNode.rotation = Rotation(0f, t * -88f, 0f)
                safe.doorNode.scale    = Scale(1f - t * 0.55f, 1f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { onComplete() }
            })
            start()
        }
    }

    // ─── Biglietto ────────────────────────────────────────────────
    private fun showTicket(eggNumber: Int, riddleText: String) {
        playState = PlayState.TICKET_SHOWN
        val stars = listOf("\u2B50","\uD83C\uDF1F","\u2728","\uD83D\uDCE7","\uD83C\uDF89","\uD83C\uDF88")
        val star = stars[(eggNumber - 1) % stars.size]
        binding.tvRiddleTitle.text = "$star  Uovo #$eggNumber  $star"
        binding.tvRiddle.text = riddleText
        binding.btnCloseRiddle.text = "\uD83D\uDDFA\uFE0F  CAPITO! VADO A TROVARLO!"

        val overlay = binding.riddleOverlay
        val card    = overlay.getChildAt(0)
        overlay.visibility = View.VISIBLE; overlay.alpha = 0f
        card.scaleX = 0.4f; card.scaleY = 0.15f
        card.translationY = windowSizePx().second * 0.55f

        overlay.animate().alpha(1f).setDuration(200).start()
        timerHandler.postDelayed({
            if (!isActive || isFinishing) return@postDelayed
            card.animate().scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(700).setInterpolator(OvershootInterpolator(1.8f))
                .withEndAction { wobbleCard(card) }.start()
        }, 80)
        launchSparkles()
        updateUI()
    }

    private fun wobbleCard(card: View) {
        ValueAnimator.ofFloat(-4f, 4f, -2f, 2f, 0f).apply {
            duration = 500; interpolator = LinearInterpolator()
            addUpdateListener { anim -> card.rotation = anim.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { card.rotation = 0f }
            })
            start()
        }
    }

    private fun launchSparkles() {
        if (!isActive || isFinishing) return
        val sparkles = listOf("✨","🌟","⭐","✨","🌟","✨","⭐","🌟")
        val root = binding.arContainer
        val (w, h) = windowSizePx()
        sparkles.forEachIndexed { i, emoji ->
            val tv = TextView(this).apply {
                text = emoji; textSize = 26f; alpha = 0f
                x = (50f + Random.nextFloat() * (w - 100f))
                y = (h * 0.05f + Random.nextFloat() * h * 0.75f)
                layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT)
            }
            root.addView(tv)
            timerHandler.postDelayed({
                if (!isActive) { try { root.removeView(tv) } catch (_: Exception) {}; return@postDelayed }
                tv.animate().alpha(1f).scaleX(1.6f).scaleY(1.6f).setDuration(300)
                    .withEndAction {
                        timerHandler.postDelayed({
                            if (!isActive) { try { root.removeView(tv) } catch (_: Exception) {}; return@postDelayed }
                            tv.animate().alpha(0f).scaleX(0f).scaleY(0f).translationYBy(-60f)
                                .setDuration(500)
                                .withEndAction { try { root.removeView(tv) } catch (_: Exception) {} }
                                .start()
                        }, 500)
                    }.start()
            }, i * 100L)
        }
    }

    private fun onCloseTicket() {
        val overlay = binding.riddleOverlay; val card = overlay.getChildAt(0)
        card.animate().translationY(-windowSizePx().second)
            .scaleX(0.5f).scaleY(0.5f).rotation(15f)
            .setDuration(380).setInterpolator(AccelerateInterpolator(1.5f)).start()
        overlay.animate().alpha(0f).setStartDelay(260).setDuration(220)
            .withEndAction {
                overlay.visibility = View.GONE; overlay.alpha = 1f
                card.translationY = 0f; card.scaleX = 1f; card.scaleY = 1f; card.rotation = 0f
                onTicketClosed()
            }.start()
    }

    private fun onTicketClosed() {
        closeSafeDoor {
            val nextIdx = currentEggIdx + 1
            if (nextIdx >= eggs.size) { runOnUiThread { finishGame() }; return@closeSafeDoor }

            currentEggIdx = nextIdx; keyInPocket = false
            eggStartMs = SystemClock.elapsedRealtime()

            if (turnMode == "alternating") {
                // Mostra overlay "PASSA IL TURNO"
                runOnUiThread { showTurnSwitchOverlay(currentPlayer) }
            } else {
                runOnUiThread { playState = PlayState.SEARCHING; updateUI()
                    Toast.makeText(this, "Cerca l\'uovo #${currentEggIdx + 1}!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Overlay animato "TURNO DI [NOME]" */
    private fun showTurnSwitchOverlay(playerName: String) {
        SoundManager.playTurnSwitch()
        val root = binding.arContainer
        val tv = TextView(this).apply {
            text = "\uD83D\uDD04  Turno di\n$playerName!"
            textSize = 26f; setTextColor(AndroidColor.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setBackgroundColor(AndroidColor.parseColor("#DD1A237E"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            alpha = 0f; scaleX = 0.6f; scaleY = 0.6f
            x = root.width / 2f - 200f; y = root.height / 3f
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(tv)
        tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400)
            .setInterpolator(OvershootInterpolator(1.5f)).withEndAction {
                timerHandler.postDelayed({
                    tv.animate().alpha(0f).translationYBy(-100f).setDuration(400)
                        .withEndAction { root.removeView(tv)
                            playState = PlayState.SEARCHING; updateUI() }.start()
                }, 1800)
            }.start()
    }

    // ─── Fine gioco ───────────────────────────────────────────────
    /**
     * In modalita' sequenziale: finisce un giocatore, poi inizia il prossimo.
     * In modalita' alternata: tutti finiscono insieme, mostriamo le stats finali.
     */
    private fun finishGame() {
        timerHandler.removeCallbacks(timerRunnable)
        val totalMs = SystemClock.elapsedRealtime() - huntStartMs + penaltyAccumMs

        // ─── Multiplayer: notifica fine partita ───────────────────
        if (isMultiplayer && mpRoomCode.isNotEmpty()) {
            mpManager.updateMyScore(realEggsCaught, totalMs, finished = true)
        }
        // ─────────────────────────────────────────────────────────

        when (turnMode) {
            "alternating" -> {
                // FIX: I/O su disco in background, mai sul main thread (causa freeze)
                val snapPlayers = activePlayers.toList()
                val snapOwners  = eggOwners.toList()
                val snapEggs    = eggs.map { it.isTrap }
                val snapTimes   = eggTimesMs.toList()
                Thread {
                    try {
                        snapPlayers.forEach { player ->
                            val myEggs = snapOwners.indices.filter { snapOwners[it] == player && !snapEggs[it] }
                            val stats  = myEggs.mapIndexed { si, ei ->
                                GameDataManager.EggStat(si + 1, snapTimes.getOrElse(ei) { 0L })
                            }
                            val myTotal = stats.sumOf { it.timeMs }
                            if (stats.isNotEmpty()) dm.addRun(GameDataManager.GameRun(
                                id = dm.newRunId(), playerName = player, date = dm.todayString(),
                                eggCount = stats.size, eggStats = stats, totalMs = myTotal
                            ))
                        }
                        saveCurrentSession()
                    } catch (_: Exception) {}
                    runOnUiThread { SoundManager.playVictory(); showFinalStats() }
                }.start()
            }
            else -> {
                // FIX: Sequenziale - I/O su disco in background, mai sul main thread (causa freeze)
                val snapPlayer   = currentPlayer
                val snapTotal    = totalMs
                val snapCaught   = realEggsCaught
                val snapEggCount = eggs.count { !it.isTrap }
                val snapTimes    = eggTimesMs.toList()
                val nextIdx      = currentPlayerIdx + 1
                Thread {
                    try {
                        dm.addRun(GameDataManager.GameRun(
                            id = dm.newRunId(), playerName = snapPlayer, date = dm.todayString(),
                            eggCount = snapEggCount,
                            eggStats = snapTimes.mapIndexed { i, ms -> GameDataManager.EggStat(i + 1, ms) },
                            totalMs = snapTotal
                        ))
                        saveCurrentSession()
                    } catch (_: Exception) {}
                    runOnUiThread {
                        if (nextIdx < activePlayers.size) {
                            SoundManager.playVictory()
                            AlertDialog.Builder(this)
                                .setTitle("$snapPlayer ha finito!")
                                .setMessage("Tempo: ${fmtMs(snapTotal)}\nUova: $snapCaught\n\nOra tocca a ${activePlayers[nextIdx]}!\nRiposiziona le uova AR.")
                                .setPositiveButton("Pronti!") { _, _ -> currentPlayerIdx = nextIdx; resetForNextPlayer() }
                                .setNegativeButton("Esci") { _, _ -> showFinalStats() }
                                .setCancelable(false).show()
                        } else {
                            SoundManager.playVictory()
                            showFinalStats()
                        }
                    }
                }.start()
            }
        }
    }

    private fun resetForNextPlayer() {
        safeObject?.let { s ->
            s.doorNode.rotation = Rotation(0f,0f,0f); s.doorNode.scale = Scale(1f,1f,1f)
            s.keySlots.forEach { it.isVisible = false }; s.keySlots.clear()
        }
        eggs.forEach { egg -> egg.pulseAnim?.cancel(); egg.anchorNode.isVisible = false }
        currentEggIdx = 0; keyInPocket = false
        eggTimesMs.clear(); realEggsCaught = 0; penaltyAccumMs = 0
        huntStartMs = SystemClock.elapsedRealtime(); eggStartMs = huntStartMs
        timerHandler.post(timerRunnable)
        playState = PlayState.SEARCHING; runOnUiThread { updateUI() }
    }

    private fun showFinalStats() {
        gamePhase = GamePhase.STATS
        binding.statsOverlay.visibility = View.VISIBLE; binding.statsOverlay.alpha = 0f
        binding.statsOverlay.animate().alpha(1f).setDuration(600).start()
        renderStats(); updateUI()
    }

    private fun renderStats() {
        val sb = StringBuilder()
        when (turnMode) {
            "alternating" -> {
                sb.append("RISULTATI FINALI\n\n")
                val playerTotals = mutableListOf<Pair<String, Long>>()
                activePlayers.forEach { player ->
                    val myIdxs = eggOwners.indices.filter { eggOwners[it] == player && !eggs[it].isTrap }
                    if (myIdxs.isEmpty()) return@forEach
                    val times  = myIdxs.map { eggTimesMs.getOrElse(it) { 0L } }
                    val total  = times.sum()
                    playerTotals.add(player to total)
                    sb.append("$player\n")
                    times.forEachIndexed { i, ms -> sb.append("  Uovo #${myIdxs[i]+1}: ${fmtMs(ms)}\n") }
                    sb.append("  Totale: ${fmtMs(total)}\n\n")
                }
                if (playerTotals.size >= 2) {
                    val winner = playerTotals.minByOrNull { it.second }!!
                    sb.append("\uD83C\uDFC6 VINCITORE: ${winner.first} (${fmtMs(winner.second)})")
                }
            }
            else -> {
                activePlayers.forEach { player ->
                    val runs = dm.getRunsForPlayer(player); if (runs.isEmpty()) return@forEach
                    val last = runs.last()
                    sb.append("$player\n  Tempo: ${fmtMs(last.totalMs)}\n")
                    if (last.eggStats.isNotEmpty()) {
                        sb.append("  Migliore: ${fmtMs(last.bestMs())}  -  Peggiore: ${fmtMs(last.worstMs())}\n")
                        last.eggStats.forEach { e -> sb.append("    Uovo #${e.eggNumber}: ${fmtMs(e.timeMs)}\n") }
                    }
                    sb.append("\n")
                }
                if (activePlayers.size >= 2) {
                    val bests = activePlayers.mapNotNull { p ->
                        dm.getRunsForPlayer(p).lastOrNull()?.let { p to it.totalMs }
                    }
                    if (bests.size >= 2) {
                        val winner = bests.minByOrNull { it.second }!!
                        sb.append("\uD83C\uDFC6 Vincitore: ${winner.first} (${fmtMs(winner.second)})")
                    }
                }
            }
        }
        binding.tvStatsBody.text = sb.toString().trim()
        binding.tvStatsTitle.text = "\uD83C\uDF85 Partita completata!"
    }

    // ─── Pulsazione uovo ─────────────────────────────────────────
    private fun startEggPulse(egg: EggObject) {
        egg.pulseAnim?.cancel()
        egg.pulseAnim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 950; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (egg.anchorNode.isVisible) {
                    val p = 1f + 0.25f * sin((anim.animatedValue as Float).toDouble()).toFloat()
                    egg.eggNode.scale = Scale(p, p * 1.45f, p)
                }
            }
            start()
        }
    }

    // ─── Piazza cassaforte ────────────────────────────────────────
    private fun placeSafe(hit: HitResult) {
        val sv = binding.sceneView

        // ── Correzione inclinazione ────────────────────────────────────────
        // L'anchor di ARCore eredita la rotazione del piano rilevato, che può
        // essere leggermente inclinato. Creiamo una Pose "livellata":
        // - stessa traslazione dell'hit
        // - rotazione identità (nessun tilt), cassaforte perfettamente verticale
        val rawPose = hit.hitPose
        val leveledPose = Pose(rawPose.translation, floatArrayOf(0f, 0f, 0f, 1f))
        val anchor = hit.trackable.createAnchor(leveledPose)
        val an = AnchorNode(engine = sv.engine, anchor = anchor)
        val dial: CylinderNode
        val door: Node
        val body: Node

        val dialMat   = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(210, 170, 20))

        when (selectedSafeType) {
            "chest" -> {
                val bodyMat = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(101, 67, 33))
                val lidMat  = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(139, 90, 43))
                val metalMat= sv.materialLoader.createColorInstance(color = AndroidColor.rgb(210, 170, 20))
                body = CubeNode(sv.engine, Size(0.32f,0.26f,0.24f), materialInstance = bodyMat).also { it.position = Position(0f,0.13f,0f); an.addChildNode(it) }
                door = CubeNode(sv.engine, Size(0.32f,0.10f,0.26f), materialInstance = lidMat).also { it.position = Position(0f,0.31f,0f); an.addChildNode(it) }
                // lock
                CubeNode(sv.engine, Size(0.06f,0.06f,0.028f), materialInstance = metalMat).also { it.position = Position(0f,0.13f,0.125f); an.addChildNode(it) }
                dial = CylinderNode(sv.engine, 0.025f, 0.022f, materialInstance = dialMat).also { it.position = Position(0.06f,0.13f,0.125f); it.rotation = Rotation(90f,0f,0f); an.addChildNode(it) }
            }
            "vault" -> {
                val bodyMat = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(70, 70, 80))
                val doorMat = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(90, 90, 110))
                body = CubeNode(sv.engine, Size(0.34f,0.38f,0.28f), materialInstance = bodyMat).also { it.position = Position(0f,0.19f,0f); an.addChildNode(it) }
                door = CylinderNode(sv.engine, 0.16f, 0.030f, materialInstance = doorMat).also { it.position = Position(0f,0.22f,0.145f); it.rotation = Rotation(90f,0f,0f); an.addChildNode(it) }
                dial = CylinderNode(sv.engine, 0.040f, 0.028f, materialInstance = dialMat).also { it.position = Position(0f,0.22f,0.162f); it.rotation = Rotation(90f,0f,0f); an.addChildNode(it) }
            }
            "present" -> {
                val bodyMat    = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(200, 30, 80))
                val ribbonMat  = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(255, 215, 0))
                body = CubeNode(sv.engine, Size(0.30f,0.30f,0.30f), materialInstance = bodyMat).also { it.position = Position(0f,0.15f,0f); an.addChildNode(it) }
                door = CubeNode(sv.engine, Size(0.30f,0.02f,0.32f), materialInstance = ribbonMat).also { it.position = Position(0f,0.31f,0f); an.addChildNode(it) }
                CylinderNode(sv.engine, 0.025f, 0.32f, materialInstance = ribbonMat).also { it.position = Position(0f,0.15f,0f); it.rotation = Rotation(0f,0f,90f); an.addChildNode(it) }
                CylinderNode(sv.engine, 0.025f, 0.32f, materialInstance = ribbonMat).also { it.position = Position(0f,0.15f,0f); an.addChildNode(it) }
                dial = CylinderNode(sv.engine, 0.030f, 0.022f, materialInstance = dialMat).also { it.position = Position(0.06f,0.31f,0.01f); it.rotation = Rotation(90f,0f,0f); an.addChildNode(it) }
            }
            else -> { // classic
                val bodyMat   = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(28, 36, 46))
                val doorMat   = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(46, 60, 74))
                val handleMat = sv.materialLoader.createColorInstance(color = AndroidColor.rgb(185, 185, 185))
                body = CubeNode(sv.engine, Size(0.28f,0.34f,0.24f), materialInstance = bodyMat).also { it.position = Position(0f,0.17f,0f); an.addChildNode(it) }
                door = CubeNode(sv.engine, Size(0.26f,0.32f,0.026f), materialInstance = doorMat).also { it.position = Position(0f,0.17f,0.133f); an.addChildNode(it) }
                dial = CylinderNode(sv.engine, 0.036f, 0.026f, materialInstance = dialMat).also { it.position = Position(0.075f,0.22f,0.148f); it.rotation = Rotation(90f,0f,0f); an.addChildNode(it) }
                CylinderNode(sv.engine, 0.012f, 0.10f, materialInstance = handleMat).also { it.position = Position(-0.05f,0.17f,0.148f); it.rotation = Rotation(0f,0f,90f); an.addChildNode(it) }
            }
        }

        sv.addChildNode(an)
        safeObject = SafeObject(selectedSafeType, an, body, door, dial)

        if (isRestoreMode) {
            restoreEggsFromSession(an); isRestoreMode = false
            gamePhase = GamePhase.SETUP_EGGS
            runOnUiThread {
                binding.tvStatus.text = "Cassaforte e uova ripristinate"
                binding.btnStart.visibility = View.VISIBLE
                Toast.makeText(this, "${eggs.size} uova ripristinate!", Toast.LENGTH_LONG).show(); updateUI()
            }
        } else {
            gamePhase = GamePhase.SETUP_EGGS
            runOnUiThread { binding.tvStatus.text = "Cassaforte pronta"; updateUI() }
            if (eggSetupMode == "auto" || eggSetupMode == "combined") {
                timerHandler.postDelayed({
                    runOnUiThread { Toast.makeText(this, "Piazzamento automatico...", Toast.LENGTH_SHORT).show() }
                    autoPlaceEggs(autoEggCount, trapEggCount)
                }, 500)
            }
        }
    }

    // ─── Auto-piazzamento ─────────────────────────────────────────
    private fun autoPlaceEggs(eggCount: Int, trapCount: Int) {
        val session = binding.sceneView.session ?: run {
            runOnUiThread { Toast.makeText(this, "Sessione AR non disponibile", Toast.LENGTH_LONG).show() }; return
        }
        val planes = session.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null &&
            it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.extentX >= 0.25f && it.extentZ >= 0.25f
        }
        if (planes.isEmpty()) {
            runOnUiThread { Toast.makeText(this, "Nessun piano rilevato. Muovi il telefono lentamente.", Toast.LENGTH_LONG).show() }; return
        }
        val placed = mutableListOf<FloatArray>(); var attempts = 0
        while (placed.size < eggCount && attempts < eggCount * 40) {
            attempts++
            val plane = planes.random()
            val nearEdge = Random.nextFloat() > 0.3f
            val rx: Float; val rz: Float
            if (nearEdge) {
                val frac = 0.55f + Random.nextFloat() * 0.35f
                when (Random.nextInt(4)) {
                    0    -> { rx = frac * plane.extentX / 2f;  rz = (Random.nextFloat() - 0.5f) * plane.extentZ * 0.8f }
                    1    -> { rx = -frac * plane.extentX / 2f; rz = (Random.nextFloat() - 0.5f) * plane.extentZ * 0.8f }
                    2    -> { rx = (Random.nextFloat() - 0.5f) * plane.extentX * 0.8f; rz = frac * plane.extentZ / 2f  }
                    else -> { rx = (Random.nextFloat() - 0.5f) * plane.extentX * 0.8f; rz = -frac * plane.extentZ / 2f }
                }
            } else { rx = (Random.nextFloat() - 0.5f) * plane.extentX * 0.6f; rz = (Random.nextFloat() - 0.5f) * plane.extentZ * 0.6f }
            val cx = plane.centerPose.tx() + rx; val cy = plane.centerPose.ty(); val cz = plane.centerPose.tz() + rz
            if (placed.any { p -> hypot(p[0] - cx, p[2] - cz) < 0.35f }) continue
            try {
                val anchor = session.createAnchor(Pose(floatArrayOf(cx, cy, cz), floatArrayOf(0f,0f,0f,1f)))
                placeEggAtAnchor(anchor, false, nextEggShape)
                placed.add(floatArrayOf(cx, cy, cz))
            } catch (_: Exception) {}
        }
        if (trapCount > 0 && eggs.isNotEmpty()) {
            eggs.shuffled().take(trapCount.coerceAtMost(eggs.size)).forEach { egg ->
                egg.isTrap = true; egg.trapMarkerNode?.isVisible = true
            }
        }
        runOnUiThread {
            if (placed.isNotEmpty()) { binding.btnStart.visibility = View.VISIBLE; updateUI()
                Toast.makeText(this, "${placed.size} uova piazzate!", Toast.LENGTH_LONG).show()
            } else { Toast.makeText(this, "Nessuna posizione trovata. Muovi il telefono lentamente.", Toast.LENGTH_LONG).show() }
        }
    }

    // ─── Piazza uovo ─────────────────────────────────────────────
    private fun placeEgg(hit: HitResult, isTrap: Boolean, shape: String) {
        placeEggAtAnchor(hit.createAnchor(), isTrap, shape)
        runOnUiThread {
            binding.btnStart.visibility = View.VISIBLE; updateUI()
            val lbl = if (isTrap) "\u26A0\uFE0F Trappola #${eggs.size}" else "${EGG_LABELS[(eggs.size-1) % EGG_LABELS.size]} Uovo #${eggs.size}"
            Toast.makeText(this, "$lbl nascosto!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun placeEggAtAnchor(anchor: Anchor, isTrap: Boolean, shape: String) {
        val sv = binding.sceneView; val id = eggs.size
        val colorIdx = id % EGG_COLORS.size; val col = EGG_COLORS[colorIdx]
        val mat = sv.materialLoader.createColorInstance(color = col)
        val an  = AnchorNode(engine = sv.engine, anchor = anchor)

        val eggNode: Node = when (shape) {
            "cube"     -> CubeNode(sv.engine, Size(0.10f,0.12f,0.10f), materialInstance = mat).apply { position = Position(0f,0.075f,0f) }
            "cylinder" -> CylinderNode(sv.engine, 0.055f, 0.12f, materialInstance = mat).apply { position = Position(0f,0.075f,0f) }
            "diamond"  -> CubeNode(sv.engine, Size(0.09f,0.13f,0.09f), materialInstance = mat).apply { position = Position(0f,0.075f,0f); rotation = Rotation(45f,45f,0f) }
            else       -> SphereNode(sv.engine, 0.055f, materialInstance = mat).apply { position = Position(0f,0.075f,0f); scale = Scale(1f,1.45f,1f) }
        }
        an.addChildNode(eggNode)

        // Marcatore trappola: pallina rossa sopra (visibile SOLO in setup)
        val trapMarker: SphereNode? = if (isTrap) {
            val tm = sv.materialLoader.createColorInstance(color = TRAP_COLOR)
            SphereNode(sv.engine, 0.022f, materialInstance = tm).apply { position = Position(0f,0.26f,0f); isVisible = true }
                .also { an.addChildNode(it) }
        } else null

        an.isVisible = true; sv.addChildNode(an)
        eggs.add(EggObject(id, colorIdx, shape, an, eggNode, isTrap, trapMarker))

        // ── Salvataggio locale anchor (solo in room_scan mode) ────
        // Completamente gratuito, istantaneo, nessuna rete richiesta.
        // La cassaforte viene usata come punto di riferimento.
        if (arMode == "room_scan") {
            saveAnchorLocally(id, anchor, colorIdx, shape, isTrap)
        }
    }

    private fun saveAnchorLocally(eggId: Int, anchor: com.google.ar.core.Anchor,
                                   colorIdx: Int, shape: String, isTrap: Boolean) {
        val safePose = safeObject?.anchorNode?.anchor?.pose ?: return
        val eggPose  = anchor.pose
        val localAnchor = localAnchorStore.buildAnchor(
            id       = eggId,
            refTrans = safePose.translation,
            refRot   = safePose.rotationQuaternion,
            eggTrans = eggPose.translation,
            eggRot   = eggPose.rotationQuaternion,
            colorIdx = colorIdx,
            shape    = shape,
            isTrap   = isTrap,
            label    = if (isTrap) "Trappola #${eggId+1}" else "Uovo #${eggId+1}"
        )
        localAnchors.add(localAnchor)
        localSaveCount++
        runOnUiThread { updateLocalBadge() }
        persistLocalSession()
    }

    private fun persistLocalSession() {
        if (arMode != "room_scan") return
        val safePose = safeObject?.anchorNode?.anchor?.pose ?: return
        val ttl = dm.getLocalAnchorTtlDays()
        val session = if (localAnchorSessionId.isEmpty()) {
            val s = localAnchorStore.createSession(
                name           = "Caccia del ${dm.todayString()}",
                ttlDays        = ttl,
                refDescription = "Cassaforte — piazza nello stesso punto per ripristinare",
                anchors        = localAnchors.toList()
            )
            localAnchorSessionId = s.sessionId
            s
        } else {
            localAnchorStore.load(localAnchorSessionId)?.copy(anchors = localAnchors.toList())
                ?: localAnchorStore.createSession(
                    name    = "Caccia del ${dm.todayString()}",
                    ttlDays = ttl,
                    anchors = localAnchors.toList()
                )
        }
        localAnchorStore.save(session)
    }

    private fun updateLocalBadge() {
        if (arMode != "room_scan") return
        val total = eggs.size
        val saved = localAnchors.size
        binding.tvStatus.text = when {
            saved == total && total > 0 -> "💾 $saved/$total salvati"
            saved < total               -> "💾 $saved/$total..."
            else                        -> "💾 —"
        }
    }

    // ─── Ripristino da sessione ────────────────────────────────────
    private fun restoreEggsFromSession(safeAnchorNode: AnchorNode) {
        val session = loadedSession ?: return
        val sv = binding.sceneView
        val safeTrans = safeAnchorNode.anchor.pose.translation
        session.eggOffsets.forEachIndexed { i, offset ->
            val wx = safeTrans[0] + offset[0]; val wy = safeTrans[1] + offset[1]; val wz = safeTrans[2] + offset[2]
            try {
                val anchor = sv.session?.createAnchor(Pose(floatArrayOf(wx, wy, wz), floatArrayOf(0f,0f,0f,1f))) ?: return@forEachIndexed
                val colorIdx = session.eggColors.getOrElse(i) { i % EGG_COLORS.size }
                val shape    = session.eggShapes.getOrElse(i) { "sphere" }
                val isTrap   = session.trapMask.getOrElse(i) { false }
                val mat = sv.materialLoader.createColorInstance(color = EGG_COLORS[colorIdx % EGG_COLORS.size])
                val an  = AnchorNode(sv.engine, anchor)
                val eggNode: Node = when (shape) {
                    "cube"     -> CubeNode(sv.engine, Size(0.10f,0.12f,0.10f), materialInstance = mat).apply { position = Position(0f,0.075f,0f) }
                    "cylinder" -> CylinderNode(sv.engine, 0.055f, 0.12f, materialInstance = mat).apply { position = Position(0f,0.075f,0f) }
                    "diamond"  -> CubeNode(sv.engine, Size(0.09f,0.13f,0.09f), materialInstance = mat).apply { position = Position(0f,0.075f,0f); rotation = Rotation(45f,45f,0f) }
                    else       -> SphereNode(sv.engine, 0.055f, materialInstance = mat).apply { position = Position(0f,0.075f,0f); scale = Scale(1f,1.45f,1f) }
                }
                an.addChildNode(eggNode); an.isVisible = true; sv.addChildNode(an)
                eggs.add(EggObject(i, colorIdx, shape, an, eggNode, isTrap))
            } catch (_: Exception) {}
        }
    }

    // ─── Salvataggio ─────────────────────────────────────────────
    private fun saveCurrentSession() {
        val safeTrans = safeObject?.anchorNode?.anchor?.pose?.translation
        val offsets = if (safeTrans != null) eggs.map { egg ->
            val t = egg.anchorNode.anchor.pose.translation
            floatArrayOf(t[0]-safeTrans[0], t[1]-safeTrans[1], t[2]-safeTrans[2])
        } else emptyList()
        val slotId = if (isRestoreMode && restoreSlotId.isNotBlank()) restoreSlotId else dm.newRunId()
        dm.upsertSaveSlot(GameDataManager.SavedSession(
            id = slotId, savedAt = dm.todayString(),
            slotName = "Partita del ${dm.todayString()}",
            players = activePlayers, eggCount = eggs.size, riddles = riddles,
            parentNote = "", eggOffsets = offsets,
            eggColors = eggs.map { it.colorIdx }, eggShapes = eggs.map { it.shape },
            trapMask  = eggs.map { it.isTrap }, safeType = selectedSafeType,
            turnMode = turnMode
        ))
    }

    // ─── Avvio caccia ─────────────────────────────────────────────
    private fun startHunt() {
        if (eggs.isEmpty()) { Toast.makeText(this, "Nascondi almeno un uovo!", Toast.LENGTH_SHORT).show(); return }
        gamePhase = GamePhase.PLAYING; playState = PlayState.SEARCHING
        currentEggIdx = 0; keyInPocket = false
        eggTimesMs.clear(); realEggsCaught = 0; penaltyAccumMs = 0

        // Inizializza assegnazione uova per turni alternati
        eggOwners = when (turnMode) {
            "alternating" -> eggs.indices.map { i -> activePlayers[i % activePlayers.size] }.toMutableList()
            else          -> eggs.map { _ -> currentPlayer }.toMutableList()
        }

        eggs.forEach { egg -> egg.anchorNode.isVisible = false; egg.trapMarkerNode?.isVisible = false }
        huntStartMs = SystemClock.elapsedRealtime(); eggStartMs = huntStartMs
        timerHandler.post(timerRunnable)
        binding.btnTrapToggle.visibility = View.GONE
        updateUI()

        val trapInfo = if (eggs.any { it.isTrap }) "  Attenzione alle trappole!" else ""
        val firstMsg = when (turnMode) {
            "alternating" -> "Inizia ${currentPlayer}! Trova l\'uovo #1!$trapInfo"
            else          -> "Trova l\'uovo #1!$trapInfo"
        }
        Toast.makeText(this, firstMsg, Toast.LENGTH_LONG).show()
    }

    private fun onFirstPlaneDetected() {
        binding.statusDot.setBackgroundResource(R.drawable.circle_green)
        binding.tvStatus.text = "Piano trovato"
        binding.reticle.visibility = View.VISIBLE
        ObjectAnimator.ofFloat(binding.reticle, "alpha", 0.3f, 1.0f).apply {
            duration = 700; repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE; interpolator = LinearInterpolator()
        }.start()
        when {
            isRestoreMode -> {
                binding.tvInstruction.text = "Piazza la cassaforte NELLO STESSO PUNTO di prima"
                binding.tvInstruction.visibility = View.VISIBLE
            }
            gamePhase == GamePhase.SCAN_ROOM -> {
                // Durante la scansione non avanzare automaticamente, aggiorna solo il testo
                binding.tvScanInstruction.text = "✅ Prima superficie rilevata!\nContinua a muovere il telefono per mappare più aree"
            }
            gamePhase == GamePhase.SETUP_SAFE -> showSafeTypePicker()
        }
    }

    // ─── Gestione perdita tracking ────────────────────────────────
    private fun handleTrackingLost(reason: TrackingFailureReason) {
        val hint = when (reason) {
            TrackingFailureReason.INSUFFICIENT_LIGHT ->
                "💡 Troppo buio!\nSposta il telefono verso una zona più illuminata."
            TrackingFailureReason.EXCESSIVE_MOTION ->
                "📱 Movimenti troppo veloci!\nMuovi il telefono più lentamente."
            TrackingFailureReason.INSUFFICIENT_FEATURES ->
                "🔍 Superficie troppo uniforme!\nPunta verso oggetti, bordi o pavimento con più texture.\nEvita muri bianchi o pavimenti lisci monocolore."
            TrackingFailureReason.CAMERA_UNAVAILABLE ->
                "📷 Fotocamera non disponibile.\nRiavvia l'app se il problema persiste."
            else ->
                "🔄 AR persa!\nMuovi lentamente il telefono verso il pavimento."
        }
        binding.statusDot.setBackgroundResource(R.drawable.circle_red)
        binding.tvStatus.text = "Ricalibro..."
        showTemporaryOverlay(hint, 3000L)
    }

    private fun showTemporaryOverlay(message: String, durationMs: Long) {
        val root = binding.arContainer
        // Rimuovi eventuale hint precedente ancora visibile
        root.findViewWithTag<TextView>("trackingHint")?.let {
            try { root.removeView(it) } catch (_: Exception) {}
        }
        val tv = TextView(this).apply {
            tag = "trackingHint"
            text = message; textSize = 14f
            setTextColor(AndroidColor.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setBackgroundColor(AndroidColor.parseColor("#DD8B0000"))
            setPadding(dp(22), dp(14), dp(22), dp(14))
            alpha = 0f
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.topToTop      = ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToStart  = ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd      = ConstraintLayout.LayoutParams.PARENT_ID
                lp.verticalBias  = 0.72f
            }
        }
        root.addView(tv)
        tv.animate().alpha(1f).setDuration(250).withEndAction {
            timerHandler.postDelayed({
                if (!isActive) { try { root.removeView(tv) } catch (_: Exception) {}; return@postDelayed }
                tv.animate().alpha(0f).setDuration(350)
                    .withEndAction { runOnUiThread { try { root.removeView(tv) } catch (_: Exception) {} } }
                    .start()
            }, durationMs)
        }.start()
    }

    // ─── Picker tipo cassaforte ───────────────────────────────────
    private fun showSafeTypePicker() {
        val unlocked = dm.getUnlockedSafes()
        val allTypes = listOf("classic" to "Cassaforte", "chest" to "Forziere", "vault" to "Vault", "present" to "Regalo")
        val avail = allTypes.filter { (t, _) -> unlocked.contains(t) }
        if (avail.size <= 1) { selectedSafeType = "classic"; return }
        AlertDialog.Builder(this)
            .setTitle("Scegli il tipo di cassaforte")
            .setItems(avail.map { (t, n) -> if (t == selectedSafeType) "$n (selezionata)" else n }.toTypedArray()) { _, idx ->
                selectedSafeType = avail[idx].first
                Toast.makeText(this, "Cassaforte: ${avail[idx].second}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ─── Menu in-game ─────────────────────────────────────────────
    private fun openInGameMenu() {
        if (isMenuOpen) return; isMenuOpen = true
        binding.menuTvPlayer.text = "$currentPlayer - Uovo ${currentEggIdx + 1}/${eggs.size}"
        val elapsed = if (huntStartMs > 0L) SystemClock.elapsedRealtime() - huntStartMs + penaltyAccumMs else 0L
        binding.menuTvTimer.text = "Tempo: ${fmtMs(elapsed)}"
        binding.menuTvProgress.text = "$realEggsCaught/${eggs.count { !it.isTrap }} uova trovate"
        binding.menuTvRiddleCount.text = "${riddles.size} indovinelli"
        binding.menuTvSaveInfo.text = "Salvataggi: ..."

        // *** FIX: dm.getSaveSlots() legge e parsa JSON da disco — NON va sul main thread.
        //     Lo eseguiamo in background e aggiorniamo la UI solo quando pronto.
        Thread {
            val count = try { dm.getSaveSlots().size } catch (_: Exception) { 0 }
            runOnUiThread { if (isMenuOpen) binding.menuTvSaveInfo.text = "Salvataggi: $count" }
        }.start()

        // *** FIX: cancella esplicitamente le animazioni precedenti prima di avviarne di nuove.
        //     Senza cancel(), la withEndAction della close-animation puo' ancora scattare
        //     e settare visibility=GONE dopo che openInGameMenu ha gia' settato VISIBLE,
        //     lasciando l'overlay invisibile ma presente — schermata apparentemente bloccata.
        binding.inGameMenuBg.animate().cancel()
        binding.inGameMenuSheet.animate().cancel()

        binding.inGameMenuBg.visibility = View.VISIBLE
        binding.inGameMenuBg.alpha = 0f
        binding.inGameMenuBg.animate().alpha(1f).setDuration(220).start()
        binding.inGameMenuSheet.visibility = View.VISIBLE
        binding.inGameMenuSheet.translationY = 1200f
        binding.inGameMenuSheet.animate().translationY(0f).setDuration(340)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun closeInGameMenu() {
        if (!isMenuOpen) return; isMenuOpen = false
        // Cancella le animazioni in corso prima di avviarne di nuove
        binding.inGameMenuBg.animate().cancel()
        binding.inGameMenuSheet.animate().cancel()
        binding.inGameMenuBg.animate().alpha(0f).setDuration(200)
            .withEndAction { if (!isMenuOpen) binding.inGameMenuBg.visibility = View.GONE }.start()
        binding.inGameMenuSheet.animate().translationY(1200f).setDuration(280)
            .setInterpolator(AccelerateInterpolator(2f))
            .withEndAction { if (!isMenuOpen) binding.inGameMenuSheet.visibility = View.GONE }.start()
    }

    private fun showGameStatusDialog() {
        val elapsed = if (huntStartMs > 0L) SystemClock.elapsedRealtime() - huntStartMs + penaltyAccumMs else 0L
        val sb = StringBuilder()
        sb.append("Giocatore: $currentPlayer\n")
        sb.append("Turni: ${if (turnMode=="alternating") "Alternati" else "Sequenziali"}\n")
        sb.append("Tempo: ${fmtMs(elapsed)}\n\n")
        sb.append("Uova trovate: $realEggsCaught/${eggs.count{!it.isTrap}}\n")
        if (penaltyAccumMs > 0) sb.append("Penalita\' accumulate: ${fmtMs(penaltyAccumMs)}\n")
        AlertDialog.Builder(this).setTitle("Stato partita").setMessage(sb.toString()).setPositiveButton("OK", null).show()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Esci dalla partita?")
            .setMessage("Vuoi salvare la configurazione delle uova per ricominciare in seguito?")
            .setPositiveButton("Salva ed esci") { _, _ ->
                saveCurrentSession()
                binding.inGameMenuBg.animate().cancel(); binding.inGameMenuSheet.animate().cancel()
                binding.inGameMenuBg.visibility = View.GONE; binding.inGameMenuSheet.visibility = View.GONE
                finish()
            }
            .setNeutralButton("Esci senza salvare") { _, _ ->
                binding.inGameMenuBg.animate().cancel(); binding.inGameMenuSheet.animate().cancel()
                finish()
            }
            .setNegativeButton("Annulla", null).show()
    }

    // ─── Pulsanti ─────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnStart.setOnClickListener { startHunt() }
        binding.btnHint.setOnClickListener { onHintRequested() }
        binding.btnInsertKey.setOnClickListener { insertKeyInSafe() }
        binding.btnCloseRiddle.setOnClickListener { onCloseTicket() }
        binding.btnDeleteEgg.setOnClickListener { deleteSelectedEgg() }
        binding.btnPlayAgain.setOnClickListener { finish() }
        binding.btnFullReset.setOnClickListener { goToHome() }
        binding.btnReset.setOnClickListener { openInGameMenu() }
        binding.btnTrapToggle.setOnClickListener {
            nextEggIsTrap = !nextEggIsTrap; updateTrapToggleUI()
        }
        binding.inGameMenuBg.setOnClickListener { closeInGameMenu() }
        binding.menuBtnResume.setOnClickListener { closeInGameMenu() }
        binding.menuBtnHome.setOnClickListener { closeInGameMenu(); goToHome() }
        binding.menuBtnSave.setOnClickListener {
            // *** FIX: salvataggio su disco in background, mai sul main thread
            val saveTime = dm.todayString()
            binding.menuTvSaveInfo.text = "Salvataggio..."
            Thread {
                try { saveCurrentSession() } catch (_: Exception) {}
                runOnUiThread {
                    binding.menuTvSaveInfo.text = "Salvato: $saveTime"
                    Toast.makeText(this, "Partita salvata!", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        binding.menuBtnRiddles.setOnClickListener { pickRiddlesInGame.launch(arrayOf("text/plain")) }
        binding.menuBtnStatus.setOnClickListener { closeInGameMenu(); showGameStatusDialog() }
        binding.menuBtnHelp.setOnClickListener { closeInGameMenu(); startActivity(Intent(this, HelpActivity::class.java)) }
        binding.menuBtnExit.setOnClickListener { closeInGameMenu(); confirmExit() }
    }

    private fun updateTrapToggleUI() {
        if (nextEggIsTrap) {
            binding.btnTrapToggle.text = "\u26A0\uFE0F Trappola"
            binding.btnTrapToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(AndroidColor.parseColor("#CCB71C1C"))
        } else {
            binding.btnTrapToggle.text = "\uD83E\uDD5A Normale"
            binding.btnTrapToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(AndroidColor.parseColor("#CC1A237E"))
        }
    }

    // ─── UI ───────────────────────────────────────────────────────
    private fun updateUI() {
        updateKeyInventoryUI()
        binding.throwZone.visibility = View.GONE
        binding.btnInsertKey.visibility = View.GONE
        binding.playerSelectOverlay.visibility = View.GONE

        when (gamePhase) {
            GamePhase.SCAN_ROOM -> {
                binding.tvGamePhase.text = "SCANSIONE STANZA"
                binding.tvInstruction.visibility = View.VISIBLE
                binding.tvInstruction.text = "🏠 Cammina per la stanza per mapparla"
                binding.btnStart.visibility = View.GONE
                binding.tvTimer.visibility = View.GONE
                binding.btnTrapToggle.visibility = View.GONE
                binding.scanOverlay.visibility = View.VISIBLE
            }
            GamePhase.SETUP_SAFE -> {
                val safeLabel = when (selectedSafeType) {
                    "chest"   -> "Forziere"
                    "vault"   -> "Vault"
                    "present" -> "Regalo"
                    else      -> "Cassaforte"
                }
                binding.tvGamePhase.text = if (isRestoreMode) "RIPRISTINO" else "GENITORE"
                binding.tvInstruction.visibility = View.VISIBLE
                binding.tvInstruction.text = if (isRestoreMode)
                    "Piazza la ${safeLabel} NELLO STESSO PUNTO di prima"
                else "Tocca il pavimento per piazzare la ${safeLabel}"
                binding.btnStart.visibility = View.GONE; binding.tvTimer.visibility = View.GONE
                binding.btnTrapToggle.visibility = View.GONE
            }
            GamePhase.SETUP_EGGS -> {
                val trapCount = eggs.count { it.isTrap }
                val trapStr   = if (trapCount > 0) " - $trapCount trap" else ""
                binding.tvGamePhase.text = "UOVA - ${eggs.size} piazzate$trapStr"
                binding.tvInstruction.visibility = View.VISIBLE
                binding.tvInstruction.text = setupInstructionText()
                binding.btnStart.visibility = if (eggs.isNotEmpty()) View.VISIBLE else View.GONE
                binding.tvTimer.visibility = View.GONE
                binding.btnTrapToggle.visibility = if (eggSetupMode != "auto") View.VISIBLE else View.GONE
                if (eggSetupMode != "auto") updateTrapToggleUI()
            }
            GamePhase.PLAYING -> {
                binding.tvInstruction.visibility = View.GONE; binding.btnStart.visibility = View.GONE
                binding.tvTimer.visibility = View.VISIBLE; binding.btnTrapToggle.visibility = View.GONE
                updatePlayingUI()
            }
            GamePhase.STATS -> {
                binding.tvTimer.visibility = View.GONE; binding.btnTrapToggle.visibility = View.GONE
                binding.statsOverlay.visibility = View.VISIBLE
            }
        }
    }

    private fun updatePlayingUI() {
        val n = currentEggIdx + 1
        val playerLabel = if (turnMode == "alternating") "$currentPlayer - " else ""
        // Mostra il pulsante indizio solo quando si sta cercando un uovo
        binding.btnHint.visibility = if (
            playState == PlayState.SEARCHING || playState == PlayState.NEAR_EGG
        ) View.VISIBLE else View.GONE
        when (playState) {
            PlayState.SEARCHING    -> binding.tvGamePhase.text = "${playerLabel}Cerca uovo #$n"
            PlayState.NEAR_EGG    -> { binding.tvGamePhase.text = "${EGG_LABELS[currentEggIdx % EGG_LABELS.size]} UOVO #$n TROVATO!"; binding.throwZone.visibility = View.VISIBLE }
            PlayState.THROWING    -> Unit
            PlayState.KEY_OBTAINED -> binding.tvGamePhase.text = "Chiave #$realEggsCaught - Vai alla cassaforte!"
            PlayState.NEAR_SAFE   -> { binding.tvGamePhase.text = "CASSAFORTE VICINA!"; binding.btnInsertKey.visibility = View.VISIBLE }
            PlayState.TICKET_SHOWN -> binding.tvGamePhase.text = "Leggi il biglietto!"
        }
    }

    private fun updateKeyInventoryUI() {
        binding.llKeyInventory.removeAllViews()
        if (gamePhase != GamePhase.PLAYING && gamePhase != GamePhase.STATS) return
        val dp = resources.displayMetrics.density; val size = (16 * dp).toInt(); val m = (3 * dp).toInt()
        eggs.filter { !it.isTrap }.forEachIndexed { i, _ ->
            val inserted = i < realEggsCaught; val caught = i == realEggsCaught && keyInPocket
            val c = EGG_COLORS[i % EGG_COLORS.size]
            binding.llKeyInventory.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also { lp -> lp.setMargins(m,0,m,0) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    when {
                        inserted -> { setColor(c); setStroke((2*dp).toInt(), AndroidColor.WHITE) }
                        caught   -> { setColor(c); setStroke((2*dp).toInt(), AndroidColor.YELLOW) }
                        else     -> { setColor(AndroidColor.TRANSPARENT); setStroke((2*dp).toInt(),
                            AndroidColor.argb(110, AndroidColor.red(c), AndroidColor.green(c), AndroidColor.blue(c))) }
                    }
                }
            })
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    // === INDIZIO CON REWARDED AD ==========================================

    /**
     * Chiamato quando il bambino preme il pulsante Indizio.
     * Mostra un Rewarded Ad; al completamento sblocca l'indizio.
     */
    private fun onHintRequested() {
        if (gamePhase != GamePhase.PLAYING) return
        if (playState != PlayState.SEARCHING && playState != PlayState.NEAR_EGG) return
        val now = SystemClock.elapsedRealtime()
        if (now < hintCooldownUntilMs) return
        hintCooldownUntilMs = now + 3_000L

        val ad = rewardedAd
        if (ad != null) {
            ad.show(this, OnUserEarnedRewardListener { _ ->
                runOnUiThread { showHint() }
            })
        } else {
            Toast.makeText(this, "Pubblicita' non pronta, riprova tra poco!", Toast.LENGTH_LONG).show()
            loadRewardedAd()
        }
    }

    /**
     * Calcola e mostra distanza + direzione relativa rispetto alla camera.
     */
    private fun showHint() {
        val frame = lastArFrame ?: run {
            Toast.makeText(this, "AR non attiva, muovi il telefono", Toast.LENGTH_SHORT).show()
            return
        }
        val target = eggs.getOrNull(currentEggIdx) ?: return
        try {
            val cam = frame.camera.pose
            val egg = target.anchorNode.anchor.pose.translation
            val dx  = egg[0] - cam.tx()
            val dz  = egg[2] - cam.tz()
            val distM = hypot(dx, dz)

            val distLbl = when {
                distM < 0.5f  -> "meno di 50 cm"
                distM < 1.5f  -> "circa ${(distM * 100).toInt()} cm"
                distM < 5f    -> "circa ${"%.1f".format(distM)} m"
                else          -> "piu' di ${distM.toInt()} m"
            }

            val fwd   = cam.rotateVector(floatArrayOf(0f, 0f, -1f))
            val right = cam.rotateVector(floatArrayOf(1f, 0f,  0f))
            fun dot2D(ax: Float, az: Float, bx: Float, bz: Float) = ax * bx + az * bz
            val fwdDot   = dot2D(fwd[0],   fwd[2],   dx, dz)
            val rightDot = dot2D(right[0], right[2], dx, dz)

            val vertic = when {
                fwdDot >  0.3f * distM -> "davanti a te"
                fwdDot < -0.3f * distM -> "dietro di te"
                else -> ""
            }
            val horiz = when {
                rightDot >  0.3f * distM -> "a destra"
                rightDot < -0.3f * distM -> "a sinistra"
                else -> ""
            }
            val dir = listOf(vertic, horiz).filter { it.isNotEmpty() }.joinToString(" e ").ifEmpty { "intorno a te" }

            showHintOverlay("Indizio:\nL'uovo e' $distLbl\n$dir")
        } catch (_: Exception) {
            Toast.makeText(this, "Indizio non disponibile", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHintOverlay(message: String) {
        val root = binding.arContainer
        val tv = TextView(this).apply {
            text = message
            textSize = 18f
            setTextColor(AndroidColor.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setBackgroundColor(AndroidColor.parseColor("#DD1B5E20"))
            setPadding(dp(28), dp(20), dp(28), dp(20))
            alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.topToTop      = ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToStart  = ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd      = ConstraintLayout.LayoutParams.PARENT_ID
                lp.verticalBias  = 0.35f
            }
        }
        root.addView(tv)
        tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300)
            .setInterpolator(OvershootInterpolator(1.3f))
            .withEndAction {
                timerHandler.postDelayed({
                    if (!isActive) { try { root.removeView(tv) } catch (_: Exception) {}; return@postDelayed }
                    tv.animate().alpha(0f).translationYBy(-40f).setDuration(400)
                        .withEndAction { runOnUiThread { try { root.removeView(tv) } catch (_: Exception) {} } }
                        .start()
                }, 3_500L)
            }.start()
    }

    /** Aggiorna il leaderboard live multiplayer (angolo basso sinistra) */
    private fun updateMpLeaderboard(scores: List<MultiplayerManager.PlayerScore>) {
        if (!isMultiplayer) return
        val medals = listOf("🥇","🥈","🥉","4️⃣","5️⃣","6️⃣")
        val sb = StringBuilder()
        scores.forEachIndexed { i, s ->
            val medal = medals.getOrElse(i) { "👤" }
            val nameLabel = if (s.playerId == mpPlayerId) "${s.playerName} ◀" else s.playerName
            val finFlag = if (s.finished) " ✅" else ""
            sb.append("$medal $nameLabel  ${s.eggsFound}🥚  ${fmtMs(s.totalMs)}$finFlag\n")
        }
        binding.mpLeaderboardContent.text = sb.toString().trimEnd()
    }

    // ─── Chat in-game ─────────────────────────────────────────────
    private var mpUnreadCount = 0
    private var mpChatOpen    = false

    private fun toggleChatOverlay() {
        mpChatOpen = !mpChatOpen
        if (mpChatOpen) {
            mpUnreadCount = 0
            binding.mpChatBadge.visibility = android.view.View.GONE
            binding.mpChatOverlay.visibility = android.view.View.VISIBLE
            binding.mpChatOverlay.alpha = 0f
            binding.mpChatOverlay.animate().alpha(1f).setDuration(200).start()
            binding.mpChatScrollView.post { binding.mpChatScrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        } else {
            binding.mpChatOverlay.animate().alpha(0f).setDuration(180)
                .withEndAction { binding.mpChatOverlay.visibility = android.view.View.GONE }
                .start()
        }
    }

    private fun sendInGameChat() {
        val text = binding.mpChatInput.text.toString().trim()
        if (text.isEmpty()) return
        mpManager.sendChatMessage(text)
        binding.mpChatInput.setText("")
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.mpChatInput.windowToken, 0)
    }

    private fun handleInGameChat(msg: MultiplayerManager.ChatMessage) {
        addChatBubbleToOverlay(msg)
        if (!mpChatOpen) {
            mpUnreadCount++
            binding.mpChatBadge.text = if (mpUnreadCount > 9) "9+" else mpUnreadCount.toString()
            binding.mpChatBadge.visibility = android.view.View.VISIBLE
            // Mini notifica toast per messaggi di altri giocatori
            if (msg.type == "msg" && msg.senderId != mpPlayerId) {
                Toast.makeText(this, "💬 ${msg.senderName}: ${msg.text.take(40)}", Toast.LENGTH_SHORT).show()
            }
        }
        if (mpChatOpen) {
            binding.mpChatScrollView.post { binding.mpChatScrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun addChatBubbleToOverlay(msg: MultiplayerManager.ChatMessage) {
        val list = binding.mpChatMessageList
        val isMe     = msg.senderId == mpPlayerId
        val isSystem = msg.type == "system"
        val dp = resources.displayMetrics.density

        if (isSystem) {
            val tv = android.widget.TextView(this).apply {
                text = msg.text; textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#AAFFC107"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, (3*dp).toInt(), 0, (3*dp).toInt())
            }
            list.addView(tv); return
        }

        val bubble = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = if (isMe) android.view.Gravity.END else android.view.Gravity.START
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6*dp).toInt() }
        }
        if (!isMe) {
            val nameTv = android.widget.TextView(this).apply {
                text = msg.senderName; textSize = 10f
                setTextColor(android.graphics.Color.parseColor("#AAFFFFFF"))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding((4*dp).toInt(), 0, 0, (2*dp).toInt())
            }
            bubble.addView(nameTv)
        }
        val bgColor = if (isMe) "#1565C0" else "#1C2E40"
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = (12*dp)
            cardElevation = 0f
            setCardBackgroundColor(android.graphics.Color.parseColor(bgColor))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = if (isMe) android.view.Gravity.END else android.view.Gravity.START
                it.marginStart = if (isMe) (40*dp).toInt() else 0
                it.marginEnd   = if (isMe) 0 else (40*dp).toInt()
            }
        }
        val tv = android.widget.TextView(this).apply {
            text = msg.text; textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setPadding((10*dp).toInt(), (6*dp).toInt(), (10*dp).toInt(), (6*dp).toInt())
        }
        card.addView(tv); bubble.addView(card); list.addView(bubble)
    }

    /** Torna alla HomeActivity svuotando lo stack di navigazione. */
    private fun goToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    /**
     * Hit-test unificato che si adatta alla modalità AR attiva:
     *  - standard   → solo piani ARCore (comportamento originale)
     *  - depth      → piani + DepthPoint (qualsiasi superficie rilevata dal sensore)
     *  - room_scan  → come depth, ma privilegia anche risultati su mesh scansionata
     */
    private fun arSurfaceHitTest(event: MotionEvent): HitResult? {
        val frame = lastArFrame ?: return null
        val hits  = frame.hitTest(event)
        return when (arMode) {
            "standard" -> hits.firstOrNull { hr ->
                val t = hr.trackable
                t is Plane && t.isPoseInPolygon(hr.hitPose) && t.trackingState == TrackingState.TRACKING
            }
            else -> {
                // Depth/Room scan: accetta Plane O DepthPoint O InstantPlacementPoint
                hits.firstOrNull { hr ->
                    when (val t = hr.trackable) {
                        is Plane       -> t.isPoseInPolygon(hr.hitPose) && t.trackingState == TrackingState.TRACKING
                        is DepthPoint  -> true   // qualsiasi punto rilevato dal sensore depth
                        else           -> false
                    }
                } ?: hits.firstOrNull { hr ->   // fallback: qualunque hit valido
                    hr.trackable.trackingState == TrackingState.TRACKING
                }
            }
        }
    }

    // Mantenuto per retrocompatibilità con restore/autoPlace che usano ancora piani
    private fun arPlaneHitTest(event: MotionEvent): HitResult? =
        lastArFrame?.hitTest(event)?.firstOrNull { hr ->
            val t = hr.trackable
            t is Plane && t.isPoseInPolygon(hr.hitPose) && t.trackingState == TrackingState.TRACKING
        }

    private fun dist3(a: FloatArray, b: FloatArray) = hypot(hypot(a[0]-b[0], a[1]-b[1]), a[2]-b[2])

    /** Restituisce (larghezza, altezza) in pixel compatibile con API 26-34 */
    private fun windowSizePx(): Pair<Float, Float> {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            Pair(metrics.bounds.width().toFloat(), metrics.bounds.height().toFloat())
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m > 0) "${m}m${"%02d".format(s % 60)}s" else "${s}s"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
