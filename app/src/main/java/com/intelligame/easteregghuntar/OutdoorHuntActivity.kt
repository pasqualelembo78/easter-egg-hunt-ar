package com.intelligame.easteregghuntar

import android.Manifest
import android.animation.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import kotlin.math.*

/**
 * OutdoorHuntActivity — caccia outdoor stile Pokemon GO.
 * OSMDroid + Android LocationManager + Firebase (se multiplayer).
 *
 * Funziona in due modalità:
 *  - SINGOLO: legge le uova da OutdoorSession (Intent)
 *  - MULTIPLAYER HOST: legge da Intent, aggiorna Firebase
 *  - MULTIPLAYER GUEST: legge le uova da Firebase (outdoor_rooms/{code}/outdoor_eggs)
 */
class OutdoorHuntActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val LOC_PERM   = 202
        private const val REVEAL_DIST = 20.0
        private const val CATCH_DIST  = 5.0
        private val EGG_COLORS = listOf(
            0xFFE8336D.toInt(), 0xFFFFCC00.toInt(), 0xFF8A2BE2.toInt(),
            0xFF00B894.toInt(), 0xFFFF6B35.toInt(), 0xFF0099CC.toInt()
        )
    }

    // ── Sessione ─────────────────────────────────────────────────
    private val mutableEggs  = mutableListOf<OutdoorEgg>()
    private var penaltySecs  = 30
    private var sessionPlayers = listOf<String>()

    // ── Multiplayer ──────────────────────────────────────────────
    private var isMultiplayer = false
    private var isHost        = false
    private var roomCode      = ""
    private var myPlayerName  = ""
    private var myPlayerId    = OutdoorRoomManager.generatePlayerId()
    // Listener real-time tramite OutdoorRoomManager
    private var eggsListener:   Pair<DatabaseReference, ChildEventListener>? = null
    private var scoresListener: Pair<DatabaseReference, ValueEventListener>? = null

    // ── Mappa ────────────────────────────────────────────────────
    private lateinit var mapView: MapView
    private var myMarker: Marker? = null
    private val eggMarkers  = mutableMapOf<Int, Marker>()
    private val eggCircles  = mutableMapOf<Int, Polygon>()

    // ── GPS ──────────────────────────────────────────────────────
    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var firstFix     = false

    private val locationListener = LocationListener { loc ->
        lastLocation = loc
        runOnUiThread {
            onLocationChanged(loc)
            if (!firstFix) {
                firstFix = true
                mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude), 18.0, 600L)
            }
        }
    }

    // ── Bussola ──────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private val accV = FloatArray(3); private val magV = FloatArray(3)
    private var azimuth = 0f

    // ── Timer ────────────────────────────────────────────────────
    private var huntStartMs    = 0L
    private var penaltyAccumMs = 0L
    private var isHuntActive   = false
    private val timerH         = Handler(Looper.getMainLooper())
    private val timerR         = object : Runnable {
        override fun run() {
            if (isHuntActive) {
                tvTimer.text = fmtMs(System.currentTimeMillis() - huntStartMs + penaltyAccumMs)
                timerH.postDelayed(this, 500)
            }
        }
    }

    // ── UI ───────────────────────────────────────────────────────
    private lateinit var tvTimer:      TextView
    private lateinit var tvEggsLeft:   TextView
    private lateinit var tvDistance:   TextView
    private lateinit var tvDirection:  TextView
    private lateinit var compassArrow:    TextView
    private lateinit var btnCatch:        Button
    private lateinit var catchOverlay:    FrameLayout
    private lateinit var tvCatchEgg:      TextView
    private lateinit var tvCatchMsg:      TextView
    private lateinit var tvCatchAttempts: TextView
    private lateinit var catchBasket:     TextView
    private lateinit var tvPlayerName:    TextView
    private var tvMpStatus: TextView? = null

    private var nearestEgg:       OutdoorEgg? = null
    private var catchAttempts     = 0
    private val catchMaxAttempts  = 3
    private var catchThrowStartX  = 0f
    private var catchThrowStartY  = 0f
    private var catchTracking     = false
    private var catchBasketAnim:  AnimatorSet? = null
    private var catchSucceeded    = false
    private var currentPlayerIdx  = 0
    private val currentPlayer get() = sessionPlayers.getOrElse(currentPlayerIdx) { myPlayerName }

    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Configuration.getInstance().apply {
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
            userAgentValue    = packageName
            osmdroidBasePath  = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        // Legge config dall'Intent
        isMultiplayer  = intent.getBooleanExtra(OutdoorModeActivity.EXTRA_IS_MP, false)
        isHost         = intent.getBooleanExtra(OutdoorModeActivity.EXTRA_IS_HOST, true)
        roomCode       = intent.getStringExtra(OutdoorModeActivity.EXTRA_ROOM_CODE) ?: ""
        myPlayerName   = intent.getStringExtra(OutdoorModeActivity.EXTRA_PLAYER_NAME) ?: "Giocatore"
        penaltySecs    = intent.getIntExtra(OutdoorModeActivity.EXTRA_PENALTY_SECS, 30)
        // Usa l'ID passato dall'host, oppure genera uno nuovo per il guest
        val intentPlayerId = intent.getStringExtra(OutdoorModeActivity.EXTRA_PLAYER_ID) ?: ""
        if (intentPlayerId.isNotBlank()) myPlayerId = intentPlayerId

        val jsonStr = intent.getStringExtra(OutdoorSetupActivity.EXTRA_SESSION_JSON) ?: ""
        if (jsonStr.isNotBlank()) {
            val sess = OutdoorSession.fromJson(JSONObject(jsonStr))
            mutableEggs.addAll(sess.eggs)
            penaltySecs    = sess.penaltySecs
            sessionPlayers = sess.players
            if (sessionPlayers.isEmpty()) sessionPlayers = listOf(myPlayerName)
        } else {
            sessionPlayers = listOf(myPlayerName)
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager   = getSystemService(SENSOR_SERVICE) as SensorManager

        buildUI(savedInstanceState)
        checkGpsPermission()
        setupBackHandler()
        // Avvia sincronizzazione multiplayer (per host e guest)
        if (isMultiplayer && roomCode.isNotEmpty()) setupMultiplayer()
    }

    // ── Build UI ──────────────────────────────────────────────────

    private fun buildUI(savedInstanceState: Bundle?) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Mappa
        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
            controller.setCenter(GeoPoint(41.9, 12.5))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(mapView)

        // HUD top
        val hudTop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD001020"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.TOP }
            setPadding(dp(16), dp(44), dp(16), dp(10))
        }
        hudTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tv("Caccia Outdoor", 17f, Color.parseColor("#FFD700"), true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            tvTimer = tv("0s", 16f, Color.WHITE, true); addView(tvTimer)
        })
        tvPlayerName = tv(myPlayerName, 12f, Color.parseColor("#AAFFFFFF")).apply { setPadding(0, dp(2), 0, 0) }
        hudTop.addView(tvPlayerName)
        if (isMultiplayer) {
            tvMpStatus = tv(if (isHost) "Host | Codice: $roomCode" else "Guest | Stanza: $roomCode",
                11f, Color.parseColor("#FFD700")).apply { setPadding(0, dp(2), 0, 0) }
            hudTop.addView(tvMpStatus!!)
        }
        root.addView(hudTop)

        // HUD bottom
        val hudBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD001020"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM }
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val rowCompass = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        compassArrow = tv("^", 34f, Color.parseColor("#FFD700")).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        rowCompass.addView(compassArrow)
        val colInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, 0, 0)
        }
        tvDistance  = tv("Cerca uova vicino a te", 15f, Color.WHITE, true)
        tvDirection = tv("Cammina e guarda intorno", 12f, Color.parseColor("#AAFFFFFF"))
        colInfo.addView(tvDistance); colInfo.addView(tvDirection); rowCompass.addView(colInfo)
        tvEggsLeft = tv("-- uova", 13f, Color.WHITE); rowCompass.addView(tvEggsLeft)
        hudBottom.addView(rowCompass)

        btnCatch = Button(this).apply {
            text = "CATTURA!"; setTextColor(Color.parseColor("#001020"))
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#FFD700")); textSize = 16f; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { startCatchSequence() }
        }
        hudBottom.addView(btnCatch)
        root.addView(hudBottom)

        // ── Catch overlay — Cestello stile Pokémon GO ─────────────
        catchOverlay = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F2020818"), Color.parseColor("#F2001A10"))
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }

        // Uovo gigante oscillante
        tvCatchEgg = tv("🥚", 96f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(160), dp(160)).also {
                it.gravity = Gravity.CENTER; it.bottomMargin = dp(80) }
        }
        catchOverlay.addView(tvCatchEgg)
        ObjectAnimator.ofFloat(tvCatchEgg, "translationY", 0f, -dp(14).toFloat(), 0f).apply {
            duration = 2000; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator(); start()
        }

        // Ring pulsante
        val catchRing = View(this).apply {
            val s = dp(170)
            layoutParams = FrameLayout.LayoutParams(s, s).also {
                it.gravity = Gravity.CENTER; it.bottomMargin = dp(80) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Color.parseColor("#88FFD700"))
            }
        }
        catchOverlay.addView(catchRing)
        ObjectAnimator.ofFloat(catchRing, "scaleX", 1f, 1.2f, 1f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE; start()
        }
        ObjectAnimator.ofFloat(catchRing, "scaleY", 1f, 1.2f, 1f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE; start()
        }

        // Messaggio
        tvCatchMsg = tv("↑  Scorri in su per lanciare il cestello", 14f,
            Color.parseColor("#CCFFFFFF")).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(80) }
        }
        catchOverlay.addView(tvCatchMsg)

        // Tentativi
        tvCatchAttempts = tv("🧺 🧺 🧺", 18f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(128) }
        }
        catchOverlay.addView(tvCatchAttempts)

        // Cestello
        catchBasket = tv("🧺", 44f, Color.WHITE).apply {
            gravity = Gravity.CENTER; alpha = 0f }
        catchOverlay.addView(catchBasket)

        // Tasto "Lascia andare"
        val btnCancelCatch = TextView(this).apply {
            text = "Lascia andare 🏃"; textSize = 13f
            setTextColor(Color.parseColor("#77FFFFFF")); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(24) }
            setOnClickListener { catchOverlay.visibility = View.GONE }
        }
        catchOverlay.addView(btnCancelCatch)

        // Touch handler
        catchOverlay.setOnTouchListener { _, event ->
            if (!catchSucceeded && catchAttempts < catchMaxAttempts)
                handleCatchTouch(event)
            true
        }

        root.addView(catchOverlay)
        setContentView(root)
    }

    // ── Location ─────────────────────────────────────────────────

    private fun onLocationChanged(loc: Location) {
        if (!isHuntActive) {
            isHuntActive = true; huntStartMs = System.currentTimeMillis()
            timerH.post(timerR)
        }
        updateMyMarker(GeoPoint(loc.latitude, loc.longitude))
        revealNearbyEggs(loc)
        updateHUD(loc)
    }

    private fun updateMyMarker(pt: GeoPoint) {
        if (myMarker == null) {
            myMarker = Marker(mapView).apply {
                icon = makeMarker(Color.parseColor("#2979FF"), "", 20)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
        }
        myMarker!!.position = pt; mapView.invalidate()
    }

    private fun revealNearbyEggs(loc: Location) {
        mutableEggs.filter { !it.caught }.forEach { egg ->
            val dist   = egg.distanceTo(loc.latitude, loc.longitude)
            val center = GeoPoint(egg.lat, egg.lng)
            if (dist <= REVEAL_DIST && !eggMarkers.containsKey(egg.id)) {
                val color = EGG_COLORS[egg.colorIdx % EGG_COLORS.size]
                val marker = Marker(mapView).apply {
                    position = center
                    title    = if (egg.isTrap) "??" else egg.label
                    snippet  = "${dist.toInt()}m"
                    icon     = makeMarker(if (egg.isTrap) Color.parseColor("#DD2222") else color,
                        if (egg.isTrap) "?" else "${egg.id+1}", 30)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { m, _ -> m.showInfoWindow(); true }
                }
                mapView.overlays.add(marker); eggMarkers[egg.id] = marker
                mapView.overlays.add(Polygon(mapView).apply {
                    points      = Polygon.pointsAsCircle(center, CATCH_DIST)
                    fillColor   = if (egg.isTrap) 0x22FF4444.toInt() else 0x2244FF44.toInt()
                    strokeColor = if (egg.isTrap) 0xAAFF4444.toInt() else 0xAA44FF44.toInt()
                    strokeWidth = 3f
                }.also { eggCircles[egg.id] = it })
                mapView.invalidate()
                Toast.makeText(this,
                    if (egg.isTrap) "C'e' qualcosa a ${dist.toInt()}m..." else "Uovo a ${dist.toInt()}m!",
                    Toast.LENGTH_SHORT).show()
                SoundManager.playEggFound()
            }
        }
    }

    private fun updateHUD(loc: Location) {
        val uncaught = mutableEggs.filter { !it.caught }
        if (uncaught.isEmpty()) { showVictory(); return }
        val nearest = uncaught.minByOrNull { it.distanceTo(loc.latitude, loc.longitude) }
        nearestEgg = nearest
        if (nearest != null) {
            val dist    = nearest.distanceTo(loc.latitude, loc.longitude)
            val bearing = nearest.bearingFrom(loc.latitude, loc.longitude)
            tvDistance.text = when {
                dist > 200 -> "${nearest.label} - ${dist.toInt()}m"
                dist > 50  -> "${nearest.label} vicino! ${dist.toInt()}m"
                dist > 20  -> "${nearest.label} A ${dist.toInt()}m!"
                else       -> "${nearest.label} SEI LI'!"
            }
            compassArrow.rotation = bearing - azimuth
            tvDirection.text = when {
                dist <= CATCH_DIST  -> "Premi CATTURA!"
                dist <= REVEAL_DIST -> "Visibile sulla mappa!"
                else                -> dirLabel(bearing)
            }
            val canCatch = dist <= CATCH_DIST
            if (canCatch && btnCatch.visibility == View.GONE) {
                btnCatch.visibility = View.VISIBLE
                ObjectAnimator.ofFloat(btnCatch, "alpha", 0f, 1f).apply { duration = 400; start() }
            } else if (!canCatch) {
                btnCatch.visibility = View.GONE
            }
        }
        val remaining = mutableEggs.count { !it.caught && !it.isTrap }
        tvEggsLeft.text  = "$remaining uova"
        tvPlayerName.text = currentPlayer
    }

    private fun dirLabel(b: Float): String {
        val d = listOf("Nord","NE","Est","SE","Sud","SO","Ovest","NO")
        return d[((b + 22.5f) / 45f).toInt() % 8]
    }

    // ── Cattura Cestello ──────────────────────────────────────────

    private fun startCatchSequence() {
        val egg = nearestEgg ?: return
        catchAttempts  = 0
        catchSucceeded = false
        catchBasketAnim?.cancel()
        catchBasket.alpha = 0f; catchBasket.scaleX = 1f; catchBasket.scaleY = 1f
        tvCatchEgg.text  = if (egg.isTrap) "❓" else "🥚"
        tvCatchEgg.alpha = 1f; tvCatchEgg.scaleX = 1f; tvCatchEgg.scaleY = 1f
        tvCatchMsg.text  = if (egg.isTrap) "↑  Sembra sospetto... lancia il cestello!"
                           else "↑  Scorri in su per lanciare il cestello"
        tvCatchAttempts.text = "🧺 🧺 🧺"
        catchOverlay.visibility = View.VISIBLE
    }

    private fun handleCatchTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (catchBasketAnim?.isRunning == true) return
                catchThrowStartX = event.x; catchThrowStartY = event.y; catchTracking = true
                catchBasket.apply {
                    alpha = 1f
                    x = catchThrowStartX - dp(22).toFloat()
                    y = catchThrowStartY - dp(22).toFloat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!catchTracking) return
                catchBasket.x = event.x - dp(22).toFloat()
                catchBasket.y = event.y - dp(22).toFloat()
            }
            MotionEvent.ACTION_UP -> {
                if (!catchTracking) return
                catchTracking = false
                val dy = catchThrowStartY - event.y
                if (dy > dp(60)) throwBasket(event.x - catchThrowStartX, dy)
                else catchBasket.alpha = 0f
            }
        }
    }

    private fun throwBasket(dx: Float, dy: Float) {
        val egg = nearestEgg ?: return
        catchAttempts++
        val left = (catchMaxAttempts - catchAttempts).coerceAtLeast(0)
        tvCatchAttempts.text = "🧺 ".repeat(left) + "✕ ".repeat(catchAttempts.coerceAtMost(catchMaxAttempts))
        SoundManager.playThrow()

        val screenW = catchOverlay.width.toFloat()
        val screenH = catchOverlay.height.toFloat()
        val eggCX   = screenW / 2f - dp(22)
        val eggCY   = screenH / 2f - dp(80) - dp(22)
        val force   = (dy / screenH).coerceIn(0.1f, 1f)

        // Probabilità: uovo normale = 80%; trappola = 60% di scattarla
        val catchProb = if (egg.isTrap) 0.6f else 0.80f
        val breakProb = (0.3f - force * 0.25f).coerceAtLeast(0f)

        catchBasketAnim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(catchBasket, "x", catchBasket.x, eggCX).apply { duration = 500 },
                ObjectAnimator.ofFloat(catchBasket, "y", catchBasket.y, eggCY).apply {
                    duration = 500; interpolator = android.view.animation.DecelerateInterpolator() },
                ObjectAnimator.ofFloat(catchBasket, "scaleX", 1f, 0.5f).apply { duration = 500 },
                ObjectAnimator.ofFloat(catchBasket, "scaleY", 1f, 0.5f).apply { duration = 500 }
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    val r = Math.random()
                    when {
                        r < breakProb              -> onBasketBreak()
                        r < breakProb + catchProb  -> onCatchSuccess(egg)
                        else                       -> onCatchMiss()
                    }
                }
            })
            start()
        }
    }

    private fun onBasketBreak() {
        catchBasket.text = "💥"
        tvCatchMsg.text  = "Il cestello si è rotto!"
        ObjectAnimator.ofFloat(catchBasket, "rotation", -20f, 20f, -10f, 10f, 0f).apply {
            duration = 400; start()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            catchBasket.alpha = 0f; catchBasket.text = "🧺"
            catchBasket.scaleX = 1f; catchBasket.scaleY = 1f
            if (catchAttempts >= catchMaxAttempts) onAllMissed()
            else tvCatchMsg.text = "Rotto! Riprova (${catchMaxAttempts - catchAttempts} rimasti)"
        }, 1800L)
    }

    private fun onCatchMiss() {
        tvCatchMsg.text = "Ha schivato! (${catchMaxAttempts - catchAttempts} rimasti)"
        ObjectAnimator.ofFloat(tvCatchEgg, "translationX", 0f, dp(24).toFloat(), -dp(24).toFloat(), 0f).apply {
            duration = 450; start()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            catchBasket.alpha = 0f; catchBasket.scaleX = 1f; catchBasket.scaleY = 1f
            if (catchAttempts >= catchMaxAttempts) onAllMissed()
        }, 1500L)
    }

    private fun onCatchSuccess(egg: OutdoorEgg) {
        catchSucceeded = true
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(tvCatchEgg, "scaleX", 1f, 0.15f),
                ObjectAnimator.ofFloat(tvCatchEgg, "scaleY", 1f, 0.15f),
                ObjectAnimator.ofFloat(tvCatchEgg, "alpha", 1f, 0f),
                ObjectAnimator.ofFloat(catchBasket, "scaleX", 0.5f, 1.3f, 1f),
                ObjectAnimator.ofFloat(catchBasket, "scaleY", 0.5f, 1.3f, 1f)
            )
            duration = 550; start()
        }
        if (egg.isTrap) {
            tvCatchMsg.text = "⚠️ TRAPPOLA! +${penaltySecs}s"
            penaltyAccumMs += penaltySecs * 1000L
            SoundManager.playTrap()
            Handler(Looper.getMainLooper()).postDelayed({
                catchOverlay.visibility = View.GONE; markCaught(egg)
            }, 2000L)
        } else {
            tvCatchMsg.text = "✅ $currentPlayer ha trovato ${egg.label}!"
            SoundManager.playEggFound()
            Handler(Looper.getMainLooper()).postDelayed({
                catchOverlay.visibility = View.GONE; markCaught(egg)
            }, 1800L)
        }
    }

    private fun onAllMissed() {
        tvCatchMsg.text = "😢 L'uovo è scappato..."
        ObjectAnimator.ofFloat(tvCatchEgg, "translationY",
            tvCatchEgg.translationY, -dp(400).toFloat()).apply { duration = 1000; start() }
        Handler(Looper.getMainLooper()).postDelayed({
            catchOverlay.visibility = View.GONE
            nearestEgg = null; btnCatch.visibility = View.GONE
        }, 2000L)
    }

    private fun markCaught(egg: OutdoorEgg) {
        val idx = mutableEggs.indexOfFirst { it.id == egg.id }
        if (idx >= 0) mutableEggs[idx] = egg.copy(caught = true, caughtBy = currentPlayer)
        nearestEgg = null; btnCatch.visibility = View.GONE
        eggMarkers.remove(egg.id)?.let { mapView.overlays.remove(it) }
        eggCircles.remove(egg.id)?.let { mapView.overlays.remove(it) }
        mapView.overlays.add(Marker(mapView).apply {
            position = GeoPoint(egg.lat, egg.lng)
            title    = "Trovato: ${egg.label}"; snippet = "da $currentPlayer"
            icon     = makeMarker(Color.parseColor("#4CAF50"), "OK", 28)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        })
        mapView.invalidate()

        // Aggiorna Firebase se multiplayer
        if (isMultiplayer && roomCode.isNotEmpty()) {
            OutdoorRoomManager.markEggCaught(roomCode, egg.id, currentPlayer)
            val eggsFound = mutableEggs.count { it.caught && !it.isTrap }
            val totalMs   = System.currentTimeMillis() - huntStartMs + penaltyAccumMs
            OutdoorRoomManager.updateScore(roomCode, myPlayerId, myPlayerName, eggsFound, totalMs)
        }

        if (sessionPlayers.size > 1) currentPlayerIdx = (currentPlayerIdx + 1) % sessionPlayers.size
        if (mutableEggs.none { !it.caught && !it.isTrap })
            Handler(Looper.getMainLooper()).postDelayed({ showVictory() }, 600L)
    }

    // ── Multiplayer Firebase ──────────────────────────────────────

    private fun setupMultiplayer() {
        if (!isMultiplayer || roomCode.isEmpty()) return

        // Registra questo giocatore nella stanza
        OutdoorRoomManager.setPlayerOffline(roomCode, "INIT")  // no-op, solo per warm-up

        // Ascolta in real-time i cambiamenti alle uova
        eggsListener = OutdoorRoomManager.listenToEggs(
            code        = roomCode,
            onEggUpdate = { updatedEgg ->
                runOnUiThread {
                    val idx = mutableEggs.indexOfFirst { it.id == updatedEgg.id }
                    if (idx >= 0 && updatedEgg.caught && !mutableEggs[idx].caught) {
                        // Un altro giocatore ha catturato quest'uovo
                        mutableEggs[idx] = updatedEgg
                        eggMarkers.remove(updatedEgg.id)?.let { mapView.overlays.remove(it) }
                        eggCircles.remove(updatedEgg.id)?.let { mapView.overlays.remove(it) }
                        // Marker "già preso"
                        mapView.overlays.add(org.osmdroid.views.overlay.Marker(mapView).apply {
                            position = org.osmdroid.util.GeoPoint(updatedEgg.lat, updatedEgg.lng)
                            title    = "Preso da ${updatedEgg.caughtBy}"
                            icon     = makeMarker(Color.parseColor("#4CAF50"), "OK", 24)
                            setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                                      org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                        })
                        mapView.invalidate()
                        val remaining = mutableEggs.count { !it.caught && !it.isTrap }
                        tvEggsLeft.text = "$remaining uova"
                        if (remaining == 0)
                            Handler(Looper.getMainLooper()).postDelayed({ showVictory() }, 600L)
                    }
                }
            },
            onError = { msg ->
                runOnUiThread { Toast.makeText(this, "Sync: $msg", Toast.LENGTH_SHORT).show() }
            }
        )

        // Ascolta il leaderboard
        scoresListener = OutdoorRoomManager.listenToScores(roomCode) { scores ->
            runOnUiThread { updateLeaderboard(scores) }
        }
    }

    private fun updateLeaderboard(scores: List<OutdoorRoomManager.PlayerScore>) {
        if (!isMultiplayer) return
        val top = scores.take(3).joinToString("  ") { "${it.playerName}: ${it.eggsFound}🥚" }
        tvMpStatus?.text = if (top.isNotEmpty()) "🏆 $top" else "Codice: $roomCode"
    }

    // ── Vittoria ─────────────────────────────────────────────────

    private fun showVictory() {
        isHuntActive = false; timerH.removeCallbacks(timerR)
        val totalMs = System.currentTimeMillis() - huntStartMs + penaltyAccumMs
        val real    = mutableEggs.filter { !it.isTrap }
        val caught  = real.filter { it.caught }
        SoundManager.playVictory()
        android.app.AlertDialog.Builder(this)
            .setTitle("Caccia completata!")
            .setMessage("Tempo: ${fmtMs(totalMs)}\n" +
                "Uova trovate: ${caught.size}/${real.size}\n" +
                (if (penaltyAccumMs > 0) "Penalita': +${fmtMs(penaltyAccumMs)}\n" else "") +
                sessionPlayers.joinToString(", "))
            .setPositiveButton("Menu") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    // ── Bussola ───────────────────────────────────────────────────

    override fun onSensorChanged(ev: SensorEvent) {
        when (ev.sensor.type) {
            Sensor.TYPE_ACCELEROMETER  -> ev.values.copyInto(accV)
            Sensor.TYPE_MAGNETIC_FIELD -> ev.values.copyInto(magV)
        }
        val R = FloatArray(9); val I = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, I, accV, magV)) {
            val ori = FloatArray(3); SensorManager.getOrientation(R, ori)
            azimuth = ((Math.toDegrees(ori[0].toDouble()).toFloat() + 360) % 360)
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ── GPS ──────────────────────────────────────────────────────

    private fun checkGpsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION), LOC_PERM)
        } else startGps()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == LOC_PERM && r.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps()
    }

    private fun startGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, locationListener)
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                lastLocation = loc; firstFix = true
                mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                mapView.controller.setZoom(18.0)
                updateMyMarker(GeoPoint(loc.latitude, loc.longitude))
                if (!isHuntActive) { isHuntActive = true; huntStartMs = System.currentTimeMillis()
                    timerH.post(timerR) }
            }
        } catch (_: Exception) {}
    }

    // ── Back handler ─────────────────────────────────────────────

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (catchOverlay.visibility == View.VISIBLE) {
                    catchBasketAnim?.cancel()
                    catchOverlay.visibility = View.GONE; return
                }
                android.app.AlertDialog.Builder(this@OutdoorHuntActivity)
                    .setTitle("Uscire dalla caccia?")
                    .setPositiveButton("Esci") { _, _ ->
                        if (isMultiplayer && roomCode.isNotEmpty()) {
                            OutdoorRoomManager.setPlayerOffline(roomCode, myPlayerId)
                        }
                        finish()
                    }
                    .setNegativeButton("Continua", null).show()
            }
        })
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onResume() {
        super.onResume(); mapView.onResume()
        sensorManager.registerListener(this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this,
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_UI)
    }
    override fun onPause() {
        super.onPause(); mapView.onPause()
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        sensorManager.unregisterListener(this)
    }
    override fun onDestroy() {
        timerH.removeCallbacks(timerR); catchBasketAnim?.cancel()
        eggsListener?.let { (ref, listener) -> ref.removeEventListener(listener) }
        scoresListener?.let { (ref, listener) -> ref.removeEventListener(listener) }
        if (isMultiplayer && roomCode.isNotEmpty())
            OutdoorRoomManager.setPlayerOffline(roomCode, myPlayerId)
        super.onDestroy(); mapView.onDetach()
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m > 0) "${m}m${"%02d".format(s % 60)}s" else "${s}s"
    }

    private fun makeMarker(color: Int, label: String, sizeDp: Int): BitmapDrawable {
        val dp   = resources.displayMetrics.density
        val size = (sizeDp * dp).toInt().coerceAtLeast(1)
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x44000000; c.drawCircle(size/2f, size/2f + dp, size/2f - dp, p)
        p.color = color; p.style = Paint.Style.FILL
        c.drawCircle(size/2f, size/2f, size/2f - 2*dp, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2*dp
        c.drawCircle(size/2f, size/2f, size/2f - 2*dp, p)
        if (label.isNotBlank()) {
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            p.textSize = sizeDp * dp * 0.40f; p.textAlign = Paint.Align.CENTER
            val fm = p.fontMetrics
            c.drawText(label, size/2f, size/2f - (fm.top + fm.bottom)/2, p)
        }
        return BitmapDrawable(resources, bmp)
    }
}
