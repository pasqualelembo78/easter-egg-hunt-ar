package com.intelligame.easteregghuntar

import android.Manifest
import android.animation.*
import android.content.Context
import android.content.Intent
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import kotlin.math.*

/**
 * OutdoorWorldActivity — mondo aperto stile Pokémon GO.
 *
 * Caratteristiche:
 *  - Personaggio animato che cammina sulla mappa (Canvas-drawn)
 *  - Cerchio raggio di azione (70m) sempre visibile intorno al giocatore
 *  - Uova con marker 3D colorati con nome fantasy, rimangono nella mappa
 *  - Click su uovo vicino (≤70m) → dialogo: scatta in AR o cattura dalla mappa
 *  - Click su uovo lontano → "Troppo lontano!"
 *  - Palestre: stessa logica (100m)
 *  - Mini-gioco cattura: target mobile che scala con rarità
 *  - Mini-gioco allenamento: tap veloci
 */
class OutdoorWorldActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val LOC_PERM       = 210
        const val  ACTION_RADIUS_M       = 70.0   // raggio azione Pokémon GO style
        const val  GYM_RADIUS_M          = 100.0  // raggio per palestre
        private const val REFRESH_MS     = 30_000L
        private const val CHAR_ANIM_MS   = 80L    // ms per frame animazione personaggio
        private const val CHAR_SIZE_DP   = 64     // dimensione sprite personaggio
    }

    // ── Stato ─────────────────────────────────────────────────────
    private var myProfile: PlayerProfile? = null
    private val worldEggs     = mutableMapOf<String, WorldEgg>()
    private val gymLocations  = mutableMapOf<String, GymLocation>()
    private var selectedEgg:  WorldEgg?    = null
    private var selectedGym:  GymLocation? = null

    // ── Mappa OSMDroid ────────────────────────────────────────────
    private lateinit var mapView:       MapView
    private var myCharMarker:           Marker? = null
    private var actionCircle:           Polygon? = null
    private val eggMarkers    = mutableMapOf<String, Marker>()
    private val gymMarkers    = mutableMapOf<String, Marker>()

    // ── GPS ───────────────────────────────────────────────────────
    private lateinit var locationManager: LocationManager
    private var lastLocation:   Location? = null
    private var prevLocation:   Location? = null
    private var firstFix        = false
    private var followPlayer    = true

    private val locationListener = LocationListener { loc ->
        prevLocation = lastLocation
        lastLocation = loc
        runOnUiThread {
            val pt = GeoPoint(loc.latitude, loc.longitude)
            // Crea subito il marker se non esiste ancora
            ensureCharacterMarkerExists()
            updateCharacterOnMap(pt)
            if (!firstFix) {
                firstFix = true
                // Primo fix: animazione fluida di entrata
                followPlayer = true
                mapView.controller.animateTo(pt, 17.0, 800L)
                onFirstLocation(loc)
            } else {
                checkProximity(loc)
                // Follow continuo: setCenter è istantaneo e non triggera onScroll
                // così la mappa segue il personaggio senza scatti
                if (followPlayer) {
                    mapView.controller.setCenter(pt)
                }
            }
        }
    }

    // ── Bussola ───────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private val accV = FloatArray(3); private val magV = FloatArray(3)
    private var azimuth = 0f

    // ── Animazione personaggio ────────────────────────────────────
    private var walkTick        = 0
    private var isMoving        = false
    private var facingDeg       = 0f
    private val charAnimHandler = Handler(Looper.getMainLooper())
    private val charAnimRunnable = object : Runnable {
        override fun run() {
            if (isMoving) {
                walkTick = (walkTick + 1) % 64
            } else {
                walkTick = 0
            }
            refreshCharacterSprite()
            charAnimHandler.postDelayed(this, CHAR_ANIM_MS)
        }
    }

    // ── Refresh world data ────────────────────────────────────────
    private val refreshHandler  = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            lastLocation?.let { refreshWorldData(it) }
            refreshHandler.postDelayed(this, REFRESH_MS)
        }
    }

    // ── UI ────────────────────────────────────────────────────────
    private lateinit var tvPlayerName:  TextView
    private lateinit var tvLevel:       TextView
    private lateinit var tvPower:       TextView
    private lateinit var tvTitle:       TextView
    private lateinit var xpBar:         ProgressBar
    private lateinit var tvNearby:      TextView
    private lateinit var btnLeaderboard:Button
    private lateinit var btnProfile:    Button
    private lateinit var btnRefresh:    Button

    // ── Catch mini-game (Cestello) ────────────────────────────────
    private lateinit var catchOverlay:   FrameLayout
    private lateinit var tvCatchTitle:   TextView
    private lateinit var tvCatchName:    TextView
    private lateinit var tvCatchReward:  TextView
    private lateinit var catchEggView:   TextView       // Uovo grande oscillante
    private lateinit var catchBasket:    TextView       // Cestello lanciabile
    private lateinit var tvCatchAttempts:TextView
    private lateinit var tvCatchInstr:   TextView
    private lateinit var tvCatchResult:  TextView
    private var catchCurrentEgg:         WorldEgg? = null
    private var catchAttempts            = 0
    private val catchMaxAttempts         = 3
    private var catchThrowStartX         = 0f
    private var catchThrowStartY         = 0f
    private var catchTracking            = false
    private var catchBasketAnim:         AnimatorSet? = null
    private var catchSucceeded           = false

    // ── Train mini-game ───────────────────────────────────────────
    private lateinit var trainOverlay:  FrameLayout
    private lateinit var tvTrainGym:    TextView
    private lateinit var tvTrainScore:  TextView
    private lateinit var trainProgressBar: ProgressBar
    private var trainCurrentGym:  GymLocation? = null
    private var trainTapCount     = 0
    private val trainMaxTaps      = 20
    private var trainTimeoutHandler: Handler? = null

    // ── AR launch ────────────────────────────────────────────────
    private val AR_REQ = 300

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

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager   = getSystemService(SENSOR_SERVICE) as SensorManager

        buildUI(savedInstanceState)
        loadPlayerProfile()
        checkGpsPermission()
        setupBackHandler()
        charAnimHandler.post(charAnimRunnable)

        EggSpawnManager.purgeExpiredEggs()
    }

    // ── Player profile ────────────────────────────────────────────

    private fun loadPlayerProfile() {
        val name = GameDataManager.get(this).getPlayers().firstOrNull()?.name ?: "Cacciatore"
        PlayerProfileManager.initMyProfile(
            context  = this,
            name     = name,
            onReady  = { profile -> runOnUiThread { myProfile = profile; updateHUD(profile) } },
            onError  = { msg -> runOnUiThread { toast("Profilo: $msg") } }
        )
    }

    private fun updateHUD(profile: PlayerProfile) {
        tvPlayerName.text = profile.name
        tvLevel.text      = "Lv.${profile.level}"
        tvPower.text      = "⚡ ${profile.power}"
        tvTitle.text      = profile.title
        xpBar.max         = profile.xpNeededForNextLevel.toInt().coerceAtLeast(1)
        xpBar.progress    = profile.xpProgressInLevel.toInt()
        refreshCharacterSprite()
    }

    // ── Build UI ──────────────────────────────────────────────────

    private fun buildUI(savedInstanceState: Bundle?) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Mappa OSMDroid
        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
            controller.setCenter(GeoPoint(41.9, 12.5))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        // Usa MapListener per rilevare SOLO gli scroll avviati dall'utente
        // (non gli scroll programmatici di setCenter/animateTo)
        mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                // L'utente ha spostato la mappa manualmente → disattiva follow
                followPlayer = false
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean = false
        })
        root.addView(mapView)

        // Bottone re-centra sul giocatore (in basso a destra)
        val btnCenter = Button(this).apply {
            text = "📍"; textSize = 20f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC001020"))
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52)).also {
                it.gravity = Gravity.BOTTOM or Gravity.END
                it.bottomMargin = dp(104); it.marginEnd = dp(12)
            }
            setOnClickListener {
                followPlayer = true
                lastLocation?.let { loc ->
                    mapView.controller.setZoom(17.0)
                    mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                }
            }
        }
        root.addView(btnCenter)

        // ── HUD profilo (in alto) ──────────────────────────────
        val hudTop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE001020"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.TOP }
            setPadding(dp(14), dp(44), dp(14), dp(10))
        }

        // Riga 1: nome + livello + potere + bottoni
        hudTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            tvPlayerName = tv("...", 15f, Color.WHITE, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            tvLevel = tv("Lv.1", 14f, Color.parseColor("#FFD700"), bold = true).apply {
                setPadding(0, 0, dp(6), 0) }
            tvPower = tv("⚡ 0", 12f, Color.parseColor("#64B5F6"))
            btnProfile = Button(this@OutdoorWorldActivity).apply {
                text = "👤"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#44FFFFFF"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(30)).also { it.marginStart = dp(4) }
                setOnClickListener { startActivity(Intent(this@OutdoorWorldActivity, PlayerProfileActivity::class.java)) }
            }
            btnLeaderboard = Button(this@OutdoorWorldActivity).apply {
                text = "🏆"; setTextColor(Color.parseColor("#FFD700"))
                setBackgroundColor(Color.parseColor("#44FFD700")); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(30)).also { it.marginStart = dp(4) }
                setOnClickListener { showLeaderboard() }
            }
            addView(tvPlayerName); addView(tvLevel); addView(tvPower)
            addView(btnProfile); addView(btnLeaderboard)
        })

        // Riga 2: titolo + XP bar
        hudTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            tvTitle = tv("🐣 Principiante", 11f, Color.parseColor("#AAFFFFFF")).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(8) } }
            xpBar = ProgressBar(this@OutdoorWorldActivity, null,
                android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD700"))
                layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
            }
            addView(tvTitle); addView(xpBar)
        })

        // Riga 3: info uova vicine + refresh
        hudTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            tvNearby = tv("Cerco uova...", 11f, Color.parseColor("#AAFFFFFF")).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            btnRefresh = Button(this@OutdoorWorldActivity).apply {
                text = "🔄"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#44FFFFFF"))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(28))
                setOnClickListener { lastLocation?.let { refreshWorldData(it) } }
            }
            addView(tvNearby); addView(btnRefresh)
        })

        root.addView(hudTop)

        // ── Overlay cattura ────────────────────────────────────
        buildCatchOverlay(root)
        buildTrainOverlay(root)

        setContentView(root)
    }

    private fun buildCatchOverlay(root: FrameLayout) {
        // Overlay a schermo intero stile Pokémon GO con cestello
        catchOverlay = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F0020818"), Color.parseColor("#F0001A10"))
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }

        // HUD superiore con info uovo
        val hudInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC000010"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.TOP }
            setPadding(dp(20), dp(40), dp(20), dp(12))
        }
        tvCatchTitle = tv("", 13f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER)
        tvCatchName  = tv("", 22f, Color.WHITE, Gravity.CENTER, bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { lp -> lp.bottomMargin = dp(2) }
        }
        tvCatchReward = tv("", 13f, Color.parseColor("#FFD700"), Gravity.CENTER)
        hudInfo.addView(tvCatchTitle); hudInfo.addView(tvCatchName); hudInfo.addView(tvCatchReward)
        catchOverlay.addView(hudInfo)

        // Ring pulsante intorno all'uovo
        val ringSize = dp(190)
        val catchRing = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(ringSize, ringSize).also {
                it.gravity = Gravity.CENTER; it.bottomMargin = dp(80)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Color.parseColor("#88FFD700"))
            }
        }
        catchOverlay.addView(catchRing)
        ObjectAnimator.ofFloat(catchRing, "scaleX", 1f, 1.18f, 1f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE; start()
        }
        ObjectAnimator.ofFloat(catchRing, "scaleY", 1f, 1.18f, 1f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE; start()
        }

        // Uovo gigante oscillante al centro
        catchEggView = tv("🥚", 88f, Color.WHITE, Gravity.CENTER).apply {
            layoutParams = FrameLayout.LayoutParams(dp(180), dp(180)).also {
                it.gravity = Gravity.CENTER; it.bottomMargin = dp(80)
            }
        }
        catchOverlay.addView(catchEggView)
        ObjectAnimator.ofFloat(catchEggView, "translationY", 0f, -dp(14).toFloat(), 0f).apply {
            duration = 2000; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator(); start()
        }

        // Cestello lanciabile
        catchBasket = tv("🧺", 44f, Color.WHITE, Gravity.CENTER).apply { alpha = 0f }
        catchOverlay.addView(catchBasket)

        // Tentativi rimasti
        tvCatchAttempts = tv("🧺 🧺 🧺", 18f, Color.WHITE, Gravity.CENTER).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(128) }
        }
        catchOverlay.addView(tvCatchAttempts)

        // Istruzione
        tvCatchInstr = tv("↑  Scorri in su per lanciare il cestello  ↑",
            14f, Color.parseColor("#CCFFFFFF"), Gravity.CENTER).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(80) }
        }
        catchOverlay.addView(tvCatchInstr)

        // Risultato
        tvCatchResult = tv("", 24f, Color.WHITE, Gravity.CENTER).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.CENTER; it.topMargin = dp(60) }
        }
        catchOverlay.addView(tvCatchResult)

        // Bottone scappa
        val btnCancel = TextView(this).apply {
            text = "Lascia andare 🏃"; textSize = 13f
            setTextColor(Color.parseColor("#77FFFFFF")); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(24) }
            setOnClickListener { catchOverlay.visibility = View.GONE }
        }
        catchOverlay.addView(btnCancel)

        // Touch handler per swipe
        catchOverlay.setOnTouchListener { _, event ->
            if (!catchSucceeded && catchAttempts < catchMaxAttempts)
                handleCatchTouch(event)
            true
        }

        root.addView(catchOverlay)
    }

    private fun buildTrainOverlay(root: FrameLayout) {
        trainOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F0001020"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(32), 0, dp(32), 0)
        }
        tvTrainGym = tv("", 18f, Color.parseColor("#FFD700"), Gravity.CENTER, bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { lp -> lp.bottomMargin = dp(8) } }
        val instrTrain = tv("Tocca veloce! $trainMaxTaps tap per completare",
            13f, Color.parseColor("#AAFFFFFF"), Gravity.CENTER).also {
            it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { lp -> lp.bottomMargin = dp(16) } }
        trainProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = trainMaxTaps; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5722"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16))
                .also { it.bottomMargin = dp(12) }
        }
        tvTrainScore = tv("💪 0 / $trainMaxTaps", 24f, Color.WHITE, Gravity.CENTER, bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { lp -> lp.bottomMargin = dp(20) } }
        val bigBtn = Button(this).apply {
            text = "💪  TAP!"; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF5722")); textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(80))
            setOnClickListener { onTrainTap() }
        }
        val btnCancel = Button(this).apply {
            text = "Vai via"; setTextColor(Color.parseColor("#AAFFFFFF"))
            setBackgroundColor(Color.TRANSPARENT); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40)).also { it.topMargin = dp(8) }
            setOnClickListener {
                trainTimeoutHandler?.removeCallbacksAndMessages(null)
                trainOverlay.visibility = View.GONE
            }
        }
        content.addView(tvTrainGym); content.addView(instrTrain)
        content.addView(trainProgressBar); content.addView(tvTrainScore)
        content.addView(bigBtn); content.addView(btnCancel)
        trainOverlay.addView(content)
        root.addView(trainOverlay)
    }

    // ── Personaggio animato ───────────────────────────────────────

    private fun refreshCharacterSprite() {
        val level = myProfile?.level ?: 1
        val icon  = CharacterSpriteManager.makeCharacterDrawable(
            resources = resources,
            sizeDp    = CHAR_SIZE_DP,
            walkTick  = walkTick,
            level     = level,
            facing    = facingDeg
        )
        ensureCharacterMarkerExists()
        myCharMarker!!.icon = icon
        // Mantieni la posizione corrente (non resettarla)
        lastLocation?.let { loc ->
            myCharMarker!!.position = GeoPoint(loc.latitude, loc.longitude)
        }
        mapView.invalidate()
    }

    /**
     * Crea il marker del personaggio se non esiste ancora.
     * Separato da refreshCharacterSprite per poter essere chiamato subito al primo GPS fix.
     */
    private fun ensureCharacterMarkerExists() {
        if (myCharMarker != null) return
        val level = myProfile?.level ?: 1
        val icon  = CharacterSpriteManager.makeCharacterDrawable(
            resources = resources, sizeDp = CHAR_SIZE_DP,
            walkTick = 0, level = level, facing = 0f)
        myCharMarker = Marker(mapView).apply {
            this.icon = icon
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(this)
        }
    }

    private fun updateCharacterOnMap(pt: GeoPoint) {
        // Calcola direzione e stato camminata
        val prev = prevLocation
        val curr = lastLocation
        if (prev != null && curr != null && curr.distanceTo(prev) > 0.5f) {
            isMoving  = true
            facingDeg = prev.bearingTo(curr)
        } else if (curr != null && (prev == null || curr.distanceTo(prev) <= 0.5f)) {
            isMoving = false
        }

        // Aggiorna posizione marker (crea se necessario)
        ensureCharacterMarkerExists()
        myCharMarker!!.position = pt

        // Aggiorna il cerchio d'azione: aggiorna i punti se già esiste,
        // crea solo la prima volta (evita accumulo di overlays)
        val newPoints = Polygon.pointsAsCircle(pt, ACTION_RADIUS_M)
        val existing = actionCircle
        if (existing != null) {
            existing.points = newPoints
        } else {
            actionCircle = Polygon(mapView).apply {
                points      = newPoints
                fillColor   = 0x1100AAFF.toInt()
                strokeColor = 0x6600AAFF.toInt()
                strokeWidth = 2f
            }.also { mapView.overlays.add(0, it) }
        }

        mapView.invalidate()
    }

    // ── GPS / World data ──────────────────────────────────────────

    private fun onFirstLocation(loc: Location) {
        refreshWorldData(loc)
        refreshHandler.postDelayed(refreshRunnable, REFRESH_MS)
    }

    private fun refreshWorldData(loc: Location) {
        val level = myProfile?.level ?: 1
        EggSpawnManager.spawnNearPlayer(
            playerLat   = loc.latitude,
            playerLng   = loc.longitude,
            playerLevel = level,
            onComplete  = { n ->
                if (n > 0) runOnUiThread { toast("$n nuove uova apparse nelle vicinanze!") }
                loadEggsOnMap(loc)
            },
            onError = { loadEggsOnMap(loc) }
        )
        GymManager.loadGymsNear(
            lat      = loc.latitude,
            lng      = loc.longitude,
            onResult = { gyms ->
                runOnUiThread {
                    gyms.forEach { gym ->
                        gymLocations[gym.gymId] = gym
                        if (!gymMarkers.containsKey(gym.gymId)) addGymMarker(gym)
                    }
                }
            },
            onError  = {}
        )
    }

    private fun loadEggsOnMap(loc: Location) {
        EggSpawnManager.loadEggsNearPlayer(
            playerLat = loc.latitude,
            playerLng = loc.longitude,
            onResult  = { eggs ->
                runOnUiThread {
                    eggs.forEach { egg ->
                        worldEggs[egg.id] = egg
                        if (!eggMarkers.containsKey(egg.id)) addEggMarker(egg)
                    }
                    checkProximity(loc)
                }
            },
            onError = {}
        )
    }

    private fun checkProximity(loc: Location) {
        val uncaught = worldEggs.values.filter { !it.caught && !it.isExpired }
        val inRange  = uncaught.filter { it.distanceTo(loc.latitude, loc.longitude) <= ACTION_RADIUS_M }
        val nearGym  = gymLocations.values.filter {
            it.distanceTo(loc.latitude, loc.longitude) <= GYM_RADIUS_M }

        // Aggiorna HUD con riepilogo uova per rarità nell'area
        val counts = uncaught.groupBy { it.rarity }
            .entries.sortedByDescending { it.key.ordinal }
            .joinToString("  ") { (r, l) -> "${r.emoji}×${l.size}" }
        tvNearby.text = when {
            counts.isNotEmpty() -> counts + (if (inRange.isNotEmpty()) "  •  ${inRange.size} nel raggio" else "")
            else                -> "Cammina per trovare uova!"
        }
    }

    // ── Marker uova 3D ────────────────────────────────────────────

    private fun addEggMarker(egg: WorldEgg) {
        val marker = Marker(mapView).apply {
            position = GeoPoint(egg.lat, egg.lng)
            title    = "${egg.rarity.emoji} ${egg.displayLabel}"
            snippet  = "${egg.rarity.displayName} • +${egg.rarity.xpReward} XP • +${egg.rarity.basePower} ⚡"
            icon     = makeEggMarker3D(egg)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ ->
                onEggMarkerClick(egg)
                true
            }
        }
        mapView.overlays.add(marker)
        eggMarkers[egg.id] = marker
        mapView.invalidate()
    }

    private fun onEggMarkerClick(egg: WorldEgg) {
        val loc = lastLocation ?: return
        val dist = egg.distanceTo(loc.latitude, loc.longitude)

        if (dist > ACTION_RADIUS_M) {
            // Troppo lontano
            showTooFarDialog("uovo", dist)
            return
        }
        // Nel raggio: mostra dialogo scelta cattura
        selectedEgg = egg
        showCatchChoiceDialog(egg)
    }

    private fun showTooFarDialog(target: String, distM: Double) {
        android.app.AlertDialog.Builder(this)
            .setTitle("📍 Troppo lontano!")
            .setMessage(
                "Il ${target} è a ${distM.toInt()}m da te.\n\n" +
                "Devi essere entro ${ACTION_RADIUS_M.toInt()}m per interagire.\n" +
                "Avvicinati e riprova!"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showCatchChoiceDialog(egg: WorldEgg) {
        android.app.AlertDialog.Builder(this)
            .setTitle("${egg.rarity.emoji} ${egg.displayLabel}")
            .setMessage(
                "${egg.rarity.displayName}  •  +${egg.rarity.xpReward} XP  •  +${egg.rarity.basePower} ⚡\n\n" +
                "Scorri in su per lanciare il cestello!\n" +
                "Rarità alta = più difficile da prendere."
            )
            .setPositiveButton("📷 AR — Lancia il cestello") { _, _ ->
                launchArCatch(egg)
            }
            .setNegativeButton("🗺️ Mappa — Lancia il cestello") { _, _ ->
                startCatchMinigame(egg)
            }
            .setNeutralButton("Annulla", null)
            .show()
    }

    private fun launchArCatch(egg: WorldEgg) {
        val profile = myProfile
        val intent = Intent(this, OutdoorArCatchActivity::class.java).apply {
            putExtra(OutdoorArCatchActivity.EXTRA_EGG_ID,     egg.id)
            putExtra(OutdoorArCatchActivity.EXTRA_EGG_NAME,   egg.displayLabel)
            putExtra(OutdoorArCatchActivity.EXTRA_EGG_RARITY, egg.rarity.id)
            putExtra(OutdoorArCatchActivity.EXTRA_EGG_XP,     egg.rarity.xpReward)
            putExtra(OutdoorArCatchActivity.EXTRA_EGG_POWER,  egg.rarity.basePower)
            putExtra(OutdoorArCatchActivity.EXTRA_PLAYER_LVL, profile?.level ?: 1)
        }
        selectedEgg = egg
        startActivityForResult(intent, AR_REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AR_REQ && resultCode == RESULT_OK) {
            val caught = data?.getBooleanExtra(OutdoorArCatchActivity.RESULT_CAUGHT, false) ?: false
            if (caught) {
                val eggId = data?.getStringExtra(OutdoorArCatchActivity.RESULT_EGG_ID) ?: return
                val egg   = worldEggs[eggId] ?: selectedEgg ?: return
                finalizeEggCatch(egg)
            }
        }
    }

    // ── Palestre marker ────────────────────────────────────────────

    private fun addGymMarker(gym: GymLocation) {
        val marker = Marker(mapView).apply {
            position = GeoPoint(gym.lat, gym.lng)
            title    = "${gym.emoji} ${gym.name}"
            snippet  = "⚡ ${gym.power} | 💪 ${gym.trainCount} allenamenti | Top: ${gym.topTrainer.ifBlank { "—" }}"
            icon     = makeGymMarker3D(gym)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ ->
                onGymMarkerClick(gym)
                true
            }
        }
        mapView.overlays.add(marker)
        gymMarkers[gym.gymId] = marker
        mapView.invalidate()
    }

    private fun onGymMarkerClick(gym: GymLocation) {
        val loc  = lastLocation ?: return
        val dist = gym.distanceTo(loc.latitude, loc.longitude)

        if (dist > GYM_RADIUS_M) {
            showTooFarDialog("${gym.emoji} ${gym.name}", dist)
            return
        }
        // Nel raggio: mostra info e bottone allenamento
        android.app.AlertDialog.Builder(this)
            .setTitle("${gym.emoji} ${gym.name}")
            .setMessage(
                "Tipo: ${gym.type.replaceFirstChar { it.uppercase() }}\n" +
                "⚡ Potere accumulato: ${gym.power}\n" +
                "💪 Allenamenti: ${gym.trainCount}\n" +
                "🏆 Top trainer: ${gym.topTrainer.ifBlank { "—" }}\n\n" +
                "Allenati per guadagnare XP e potere!\n" +
                "(cooldown 23 ore per palestra)"
            )
            .setPositiveButton("💪 ALLENATI!") { _, _ ->
                selectedGym = gym
                startTrainMinigame(gym)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    // ── Catch mini-game (Cestello) ────────────────────────────────

    private fun startCatchMinigame(egg: WorldEgg) {
        catchCurrentEgg = egg
        catchAttempts   = 0
        catchSucceeded  = false
        catchBasketAnim?.cancel()
        catchBasket.alpha = 0f
        catchBasket.scaleX = 1f; catchBasket.scaleY = 1f

        val r = egg.rarity
        tvCatchTitle.text   = "${r.emoji}  ${r.displayName}"
        tvCatchName.text    = egg.displayLabel
        tvCatchReward.text  = "+${r.xpReward} XP  ·  +${r.basePower} ⚡"
        tvCatchInstr.text   = "↑  Scorri in su per lanciare il cestello  ↑"
        tvCatchResult.alpha = 0f
        tvCatchAttempts.text = "🧺 🧺 🧺"

        // Uovo: emoji basato su rarità
        catchEggView.text = r.emoji.ifBlank { "🥚" }
        catchEggView.alpha = 1f; catchEggView.scaleX = 1f; catchEggView.scaleY = 1f

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
                if (dy > dp(60)) {
                    launchBasket(event.x - catchThrowStartX, dy)
                } else {
                    catchBasket.alpha = 0f
                }
            }
        }
    }

    private fun launchBasket(dx: Float, dy: Float) {
        val egg = catchCurrentEgg ?: return
        catchAttempts++
        updateAttemptsHUD()
        SoundManager.playThrow()

        val r = egg.rarity
        val screenW = catchOverlay.width.toFloat()
        val screenH = catchOverlay.height.toFloat()
        val eggCX   = screenW / 2f
        val eggCY   = screenH / 2f - dp(80)
        val force   = (dy / screenH).coerceIn(0.1f, 1f)

        // Calcolo probabilità
        val baseCatch = r.catchRadius
        val lvlBonus  = ((myProfile?.level ?: 1) - 1) * 0.03f
        val forceMult = (0.7f + force * 0.6f).coerceIn(0.5f, 1.3f)
        val catchProb = ((baseCatch + lvlBonus) * forceMult).coerceIn(0.05f, 0.92f)

        val eggPowerNorm = 1f - r.catchRadius
        val breakProb = (eggPowerNorm * (0.4f - force).coerceAtLeast(0f) * 2f).coerceIn(0f, 0.4f)

        val animX = ObjectAnimator.ofFloat(catchBasket, "x", catchBasket.x, eggCX - dp(22)).apply { duration = 550 }
        val animY = ObjectAnimator.ofFloat(catchBasket, "y", catchBasket.y, eggCY - dp(22)).apply {
            duration = 550; interpolator = android.view.animation.DecelerateInterpolator()
        }
        val animSx = ObjectAnimator.ofFloat(catchBasket, "scaleX", 1f, 0.55f).apply { duration = 550 }
        val animSy = ObjectAnimator.ofFloat(catchBasket, "scaleY", 1f, 0.55f).apply { duration = 550 }

        catchBasketAnim = AnimatorSet().apply {
            playTogether(animX, animY, animSx, animSy)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    val rand = Math.random()
                    when {
                        rand < breakProb         -> onBasketBreak()
                        rand < breakProb + catchProb -> onCatchSuccess(egg)
                        else                     -> onCatchMiss()
                    }
                }
            })
            start()
        }
    }

    private fun onBasketBreak() {
        catchBasket.text = "💥"
        showCatchResult("💥 Il cestello si è rotto!", Color.parseColor("#FF5555"))
        ObjectAnimator.ofFloat(catchBasket, "rotation", -20f, 20f, -10f, 10f, 0f).apply {
            duration = 400; start()
        }
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            catchBasket.alpha = 0f; catchBasket.text = "🧺"
            catchBasket.scaleX = 1f; catchBasket.scaleY = 1f
            tvCatchResult.alpha = 0f
            if (catchAttempts >= catchMaxAttempts) allCatchAttemptsFailed()
            else tvCatchInstr.text = "Rotto! Riprova (${catchMaxAttempts - catchAttempts} rimasti)"
        }, 2000L)
    }

    private fun onCatchMiss() {
        showCatchResult("🐣 Ha schivato!", Color.parseColor("#FFAA44"))
        ObjectAnimator.ofFloat(catchEggView, "translationX", 0f, dp(28).toFloat(), -dp(28).toFloat(), 0f).apply {
            duration = 500; start()
        }
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            catchBasket.alpha = 0f; catchBasket.scaleX = 1f; catchBasket.scaleY = 1f
            tvCatchResult.alpha = 0f
            if (catchAttempts >= catchMaxAttempts) allCatchAttemptsFailed()
            else tvCatchInstr.text = "Mancato! (${catchMaxAttempts - catchAttempts} rimasti)"
        }, 2000L)
    }

    private fun onCatchSuccess(egg: WorldEgg) {
        catchSucceeded = true
        showCatchResult("✅ Preso! +${egg.rarity.xpReward} XP  +${egg.rarity.basePower} ⚡", Color.parseColor("#44FF88"))
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(catchEggView, "scaleX", 1f, 0.2f),
                ObjectAnimator.ofFloat(catchEggView, "scaleY", 1f, 0.2f),
                ObjectAnimator.ofFloat(catchEggView, "alpha", 1f, 0f),
                ObjectAnimator.ofFloat(catchBasket, "scaleX", 0.55f, 1.3f, 1f),
                ObjectAnimator.ofFloat(catchBasket, "scaleY", 0.55f, 1.3f, 1f)
            )
            duration = 600; start()
        }
        SoundManager.playEggFound()
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            catchOverlay.visibility = View.GONE
            finalizeEggCatch(egg)
        }, 2000L)
    }

    private fun allCatchAttemptsFailed() {
        showCatchResult("😢 L'uovo è scappato...", Color.parseColor("#FF6666"))
        ObjectAnimator.ofFloat(catchEggView, "translationY", catchEggView.translationY, -dp(400).toFloat()).apply {
            duration = 1200; start()
        }
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            catchOverlay.visibility = View.GONE
        }, 2500L)
    }

    private fun showCatchResult(msg: String, color: Int) {
        tvCatchResult.text = msg; tvCatchResult.setTextColor(color)
        ObjectAnimator.ofFloat(tvCatchResult, "alpha", 0f, 1f).apply { duration = 300; start() }
    }

    private fun updateAttemptsHUD() {
        val left = (catchMaxAttempts - catchAttempts).coerceAtLeast(0)
        tvCatchAttempts.text = "🧺 ".repeat(left) + "✕ ".repeat(catchAttempts.coerceAtMost(catchMaxAttempts))
    }

    private fun finalizeEggCatch(egg: WorldEgg) {
        val profile = myProfile ?: return
        EggSpawnManager.catchEgg(
            egg        = egg,
            playerId   = profile.playerId,
            playerName = profile.name,
            onSuccess  = { _ ->
                worldEggs.remove(egg.id)
                eggMarkers.remove(egg.id)?.let { m -> runOnUiThread { mapView.overlays.remove(m); mapView.invalidate() } }
                runOnUiThread {
                    SoundManager.playEggFound()
                    showCatchSuccessToast(egg)
                    PlayerProfileManager.recordEggCatch(egg.rarity) { newProfile ->
                        runOnUiThread { myProfile = newProfile; updateHUD(newProfile) }
                    }
                    selectedEgg = null
                    lastLocation?.let { checkProximity(it) }
                }
            },
            onFailed = { msg ->
                runOnUiThread { toast(msg) }
            }
        )
    }

    private fun showCatchSuccessToast(egg: WorldEgg) {
        val msg = "✅ ${egg.displayLabel} catturato!\n+${egg.rarity.xpReward} XP  +${egg.rarity.basePower} ⚡"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ── Train mini-game ───────────────────────────────────────────

    private fun startTrainMinigame(gym: GymLocation) {
        trainCurrentGym = gym; trainTapCount = 0
        trainProgressBar.progress = 0
        trainOverlay.visibility = View.VISIBLE
        tvTrainGym.text   = "${gym.emoji} ${gym.name}"
        tvTrainScore.text = "💪 0 / $trainMaxTaps"
        trainTimeoutHandler?.removeCallbacksAndMessages(null)
        trainTimeoutHandler = Handler(Looper.getMainLooper()).also { h ->
            h.postDelayed({
                if (trainOverlay.visibility == View.VISIBLE)
                    finishTraining((trainTapCount * 100f / trainMaxTaps).toInt())
            }, 15_000L)
        }
    }

    private fun onTrainTap() {
        if (trainTapCount >= trainMaxTaps) return
        trainTapCount++
        trainProgressBar.progress = trainTapCount
        tvTrainScore.text = "💪 $trainTapCount / $trainMaxTaps"
        if (trainTapCount >= trainMaxTaps) {
            trainTimeoutHandler?.removeCallbacksAndMessages(null)
            finishTraining(100)
        }
    }

    private fun finishTraining(scorePercent: Int) {
        trainOverlay.visibility = View.GONE
        val gym     = trainCurrentGym ?: return
        val profile = myProfile ?: return
        GymManager.trainAtGym(
            gym          = gym,
            player       = profile,
            scorePercent = scorePercent,
            onSuccess    = { result ->
                runOnUiThread {
                    toast("${result.message}\n+${result.powerGained} ⚡  +${result.xpGained} XP")
                    PlayerProfileManager.recordTraining(result.powerGained, result.xpGained) { p ->
                        runOnUiThread { myProfile = p; updateHUD(p) }
                    }
                    SoundManager.playVictory()
                }
            },
            onAlreadyTrained = { runOnUiThread { toast("Hai già allenato qui oggi. Torna domani! ⏳") } },
            onError          = { msg -> runOnUiThread { toast("Errore: $msg") } }
        )
    }

    // ── Leaderboard ───────────────────────────────────────────────

    private fun showLeaderboard() {
        PlayerProfileManager.getLeaderboard(
            onResult = { entries ->
                runOnUiThread {
                    val sb = StringBuilder()
                    entries.forEachIndexed { i, e ->
                        val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i+1}." }
                        sb.append("$medal ${e.name}  Lv.${e.level}  ⚡${e.power}  ${e.title}\n")
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("🏆 Classifica Mondiale")
                        .setMessage(sb.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
            },
            onError = { msg -> runOnUiThread { toast("Errore classifica: $msg") } }
        )
    }

    // ── Marker drawing ─────────────────────────────────────────────

    /**
     * Marker 3D uovo: forma ovoidale, ombra, glow per rare+, nome fantasy sotto.
     */
    private fun makeEggMarker3D(egg: WorldEgg): BitmapDrawable {
        val dp      = resources.displayMetrics.density
        val r       = egg.rarity
        val isRare  = r.ordinal >= EggRarity.RARE.ordinal
        val w       = dp(52); val h = dp(76)  // più alto che largo = forma uovo
        val bmp     = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c       = Canvas(bmp)
        val p       = Paint(Paint.ANTI_ALIAS_FLAG)

        // Ombra
        p.color = 0x44000000
        c.drawOval(RectF(4*dp, h - 10*dp, w - 4*dp, h - 2*dp), p)

        // Alone per uova rare+ (glow)
        if (isRare) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color  = r.glowColor and 0x55FFFFFF.toInt()
                maskFilter = BlurMaskFilter(8*dp, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawOval(RectF(2*dp, 4*dp, w - 2*dp, h - 14*dp), glowPaint)
        }

        // Corpo uovo: gradiente radiale
        val eggRect = RectF(5*dp, 5*dp, w - 5*dp, h - 16*dp)
        val eggH    = eggRect.height()
        val eggW    = eggRect.width()

        // Base color + gradient
        val shader = RadialGradient(
            eggRect.centerX(), eggRect.top + eggH * 0.35f,
            eggW * 0.65f,
            intArrayOf(Color.WHITE, r.color, darkenColor(r.color, 0.6f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        p.shader = shader; p.style = Paint.Style.FILL
        c.drawOval(eggRect, p)
        p.shader = null

        // Bordo bianco
        p.color = 0xCCFFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f*dp
        c.drawOval(eggRect, p)
        p.style = Paint.Style.FILL

        // Emoji al centro
        p.textSize = 15*dp; p.textAlign = Paint.Align.CENTER; p.color = Color.WHITE
        val fm = p.fontMetrics
        val textY = eggRect.centerY() - (fm.top + fm.bottom) / 2
        c.drawText(r.emoji.take(1), eggRect.centerX(), textY, p)

        // Per leggendari: stelle animate (stelle fisse qui, animazione via re-draw periodico)
        if (r == EggRarity.LEGENDARY) {
            p.color = 0xCCFFD700.toInt(); p.textSize = 7*dp
            c.drawText("★", eggRect.left + 4*dp, eggRect.top + 10*dp, p)
            c.drawText("★", eggRect.right - 6*dp, eggRect.top + 14*dp, p)
        }

        // Nome fantasy sotto (piccolo)
        val name  = egg.displayLabel.take(18)
        val bgPaint = Paint().apply {
            color = darkenColor(r.color, 0.7f) or 0xCC000000.toInt()
        }
        val nameH  = 12*dp
        val nameY  = h - 13*dp
        p.textSize  = 8f*dp; p.textAlign = Paint.Align.CENTER; p.color = Color.WHITE
        val textBg = RectF(0f, nameY - nameH + 2*dp, w.toFloat(), nameY + 2*dp)
        c.drawRoundRect(textBg, 3*dp, 3*dp, bgPaint)
        c.drawText(name, w/2f, nameY, p)

        return BitmapDrawable(resources, bmp)
    }

    private fun makeGymMarker3D(gym: GymLocation): BitmapDrawable {
        val dp    = resources.displayMetrics.density
        val size  = dp(56)
        val bmp   = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c     = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val bgColor = when (gym.type) {
            "legendary" -> Color.parseColor("#FFD700")
            "elite"     -> Color.parseColor("#FF5722")
            else        -> Color.parseColor("#1565C0")
        }

        // Ombra
        p.color = 0x44000000
        c.drawOval(RectF(4*dp, size - 8*dp, size - 4*dp, size - 2*dp), p)

        // Esagono (palestra)
        val hexPath = hexagonPath(size/2f, size/2f - 2*dp, size/2f - 6*dp)
        val gradShader = RadialGradient(
            size/2f, size/3f, size/2.5f,
            intArrayOf(lightenColor(bgColor, 1.4f), bgColor, darkenColor(bgColor, 0.7f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        p.shader = gradShader; p.style = Paint.Style.FILL
        c.drawPath(hexPath, p)
        p.shader = null

        // Bordo
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f*dp
        c.drawPath(hexPath, p)
        p.style = Paint.Style.FILL

        // Emoji
        p.textSize = 20*dp; p.textAlign = Paint.Align.CENTER; p.color = Color.WHITE
        val fm = p.fontMetrics
        c.drawText("💪", size/2f, size/2f - 2*dp - (fm.top + fm.bottom)/2, p)

        return BitmapDrawable(resources, bmp)
    }

    private fun hexagonPath(cx: Float, cy: Float, radius: Float): Path {
        val path = Path()
        for (i in 0..5) {
            val angle = Math.toRadians((60 * i - 30).toDouble()).toFloat()
            val x = cx + radius * cos(angle)
            val y = cy + radius * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); return path
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color)   * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color)  * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        val r = ((Color.red(color)   * factor).toInt()).coerceIn(0, 255)
        val g = ((Color.green(color) * factor).toInt()).coerceIn(0, 255)
        val b = ((Color.blue(color)  * factor).toInt()).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    // ── Sensori bussola ───────────────────────────────────────────

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

    // ── GPS ───────────────────────────────────────────────────────

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
            // minTime=1500ms, minDistance=0f — aggiornamento ad ogni fix GPS
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1500L, 0f, locationListener)
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000L, 0f, locationListener)
            if (locationManager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, 1000L, 0f, locationListener)
            }

            // Usa subito l'ultima posizione nota — l'omino appare subito
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastKnown != null) {
                lastLocation = lastKnown
                firstFix     = true
                followPlayer = true   // riattiva il follow al resume
                val pt = GeoPoint(lastKnown.latitude, lastKnown.longitude)
                mapView.controller.setZoom(17.0)
                mapView.controller.setCenter(pt)
                ensureCharacterMarkerExists()
                updateCharacterOnMap(pt)
                onFirstLocation(lastKnown)
            }
        } catch (e: Exception) {
            android.util.Log.e("OutdoorWorld", "GPS start error: ${e.message}")
        }
    }

    // ── Back handler ──────────────────────────────────────────────

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    catchOverlay.visibility == View.VISIBLE -> {
                        catchBasketAnim?.cancel()
                        catchOverlay.visibility = View.GONE
                    }
                    trainOverlay.visibility == View.VISIBLE -> {
                        trainTimeoutHandler?.removeCallbacksAndMessages(null)
                        trainOverlay.visibility = View.GONE
                    }
                    else -> finish()
                }
            }
        })
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onPause() {
        super.onPause(); mapView.onPause()
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        sensorManager.unregisterListener(this)
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onResume() {
        super.onResume(); mapView.onResume()
        sensorManager.registerListener(this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this,
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_UI)
        // Riavvia GPS al resume (riattiva dopo onPause)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) startGps()
    }

    override fun onDestroy() {
        catchBasketAnim?.cancel()
        trainTimeoutHandler?.removeCallbacksAndMessages(null)
        refreshHandler.removeCallbacks(refreshRunnable)
        charAnimHandler.removeCallbacks(charAnimRunnable)
        super.onDestroy(); mapView.onDetach()
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun tv(t: String, size: Float, color: Int,
                   gravity: Int = Gravity.START, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color); this.gravity = gravity
            if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD)
        }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
