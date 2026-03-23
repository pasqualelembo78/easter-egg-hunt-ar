package com.intelligame.easteregghuntar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import kotlin.random.Random

/**
 * OutdoorSetupActivity — il genitore nasconde le uova.
 *
 * Come piazzare uova (senza dover andare sul posto):
 *  1. CROSSHAIR al centro — sposta la mappa e premi "Piazza qui" (funziona anche con mouse!)
 *  2. TAP sulla mappa — piazza immediatamente dove hai toccato
 *  3. AUTO — l'app le distribuisce casualmente nell'area visibile
 *
 * Multiplayer: se attivo, al lancio le uova vengono caricate su Firebase
 * e viene mostrato il codice stanza per i guest.
 */
class OutdoorSetupActivity : AppCompatActivity() {

    companion object {
        private const val LOC_PERM = 201
        const val EXTRA_SESSION_JSON = "outdoor_session_json"
        private val EGG_COLORS = listOf(
            0xFFE8336D.toInt(), 0xFFFFCC00.toInt(), 0xFF8A2BE2.toInt(),
            0xFF00B894.toInt(), 0xFFFF6B35.toInt(), 0xFF0099CC.toInt()
        )
    }

    // ── Config ricevuta da OutdoorModeActivity ────────────────────
    private var placementMode  = "manual"
    private var autoEggCount   = 5
    private var autoTrapCount  = 0
    private var penaltySecs    = 30
    private var isMultiplayer  = false
    private var isHost         = true
    private var roomName       = "Caccia outdoor"
    private var ttlDays        = OutdoorRoomManager.DEFAULT_TTL_DAYS
    private var isPublicRoom   = true
    private lateinit var players: ArrayList<String>

    // ── Mappa ────────────────────────────────────────────────────
    private lateinit var mapView:  MapView
    private var myMarker: Marker? = null

    // ── GPS ──────────────────────────────────────────────────────
    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null

    private val locationListener = LocationListener { loc ->
        if (lastLocation == null) {
            // Prima fix: centra mappa sulla posizione
            mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude), 18.0, 800L)
        }
        lastLocation = loc
        runOnUiThread {
            tvCoords.text = "${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}  ±${loc.accuracy.toInt()}m"
            updateMyMarker(GeoPoint(loc.latitude, loc.longitude))
        }
    }

    // ── Dati uova ────────────────────────────────────────────────
    private val eggs       = mutableListOf<OutdoorEgg>()
    private val eggMarkers = mutableMapOf<Int, Marker>()
    private val eggCircles = mutableMapOf<Int, Polygon>()
    private var nextIsTrap = false

    // ── UI ───────────────────────────────────────────────────────
    private lateinit var tvCoords:      TextView
    private lateinit var tvEggCount:    TextView
    private lateinit var btnPlaceHere:  Button
    private lateinit var btnTrap:       Button
    private lateinit var btnUndo:       Button
    private lateinit var btnLaunch:     Button

    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
            userAgentValue = packageName
            osmdroidBasePath  = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        placementMode  = intent.getStringExtra(OutdoorModeActivity.EXTRA_PLACEMENT_MODE) ?: "manual"
        autoEggCount   = intent.getIntExtra(OutdoorModeActivity.EXTRA_AUTO_EGG_COUNT, 5)
        autoTrapCount  = intent.getIntExtra(OutdoorModeActivity.EXTRA_AUTO_TRAP_COUNT, 0)
        penaltySecs    = intent.getIntExtra(OutdoorModeActivity.EXTRA_PENALTY_SECS, 30)
        isMultiplayer  = intent.getBooleanExtra(OutdoorModeActivity.EXTRA_IS_MP, false)
        isHost         = intent.getBooleanExtra(OutdoorModeActivity.EXTRA_IS_HOST, true)
        players        = intent.getStringArrayListExtra("players") ?: arrayListOf("Giocatore")
        roomName       = intent.getStringExtra(OutdoorModeActivity.EXTRA_ROOM_NAME) ?: "Caccia outdoor"
        ttlDays        = intent.getIntExtra(OutdoorModeActivity.EXTRA_TTL_DAYS, OutdoorRoomManager.DEFAULT_TTL_DAYS)
        isPublicRoom   = intent.getBooleanExtra(OutdoorModeActivity.EXTRA_IS_PUBLIC, true)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUI()
        checkGpsPermission()

        // Auto: piazza uova dopo 1s (aspetta che la mappa carichi)
        if (placementMode == "auto") {
            Handler(Looper.getMainLooper()).postDelayed({ autoPlaceEggs() }, 1200L)
        }
    }

    // ── Build UI ──────────────────────────────────────────────────

    private fun buildUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#080E24")) }

        // ── Mappa OSMDroid full-screen ────────────────────────────
        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
            controller.setCenter(GeoPoint(41.9, 12.5))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        // Tap su mappa = piazza uovo immediatamente
        mapView.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                addEgg(p.latitude, p.longitude)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        root.addView(mapView)

        // ── Crosshair al centro ───────────────────────────────────
        // Permette di puntare qualsiasi punto spostando la mappa
        val crosshair = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = false; isFocusable = false
        }
        val crossH = View(this).apply {
            setBackgroundColor(Color.parseColor("#CCFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(2))
        }
        val crossV = View(this).apply {
            setBackgroundColor(Color.parseColor("#CCFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(dp(2), dp(30))
        }
        // FrameLayout per sovrapporre le due linee del crosshair
        val crossFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            isClickable = false
        }
        crossFrame.addView(crossH.apply {
            layoutParams = FrameLayout.LayoutParams(dp(30), dp(2)).also { it.gravity = Gravity.CENTER }
        })
        crossFrame.addView(crossV.apply {
            layoutParams = FrameLayout.LayoutParams(dp(2), dp(30)).also { it.gravity = Gravity.CENTER }
        })
        crosshair.addView(crossFrame)
        root.addView(crosshair)

        // ── HUD Superiore ─────────────────────────────────────────
        val hudTop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD001020"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.TOP }
            setPadding(dp(16), dp(48), dp(16), dp(10))
        }
        hudTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tv("Nascondi le uova", 17f, Color.parseColor("#FFD700"), bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            tvEggCount = tv("0 uova", 14f, Color.WHITE); addView(tvEggCount)
        })
        tvCoords = tv("In attesa GPS... (la mappa funziona anche senza)", 11f, Color.parseColor("#88FFFFFF")).apply {
            setPadding(0, dp(3), 0, 0)
        }
        hudTop.addView(tvCoords)
        hudTop.addView(tv(
            if (placementMode == "auto") "Le uova saranno piazzate automaticamente nell'area visibile"
            else "Tocca la mappa  oppure  sposta il mirino e premi 'Piazza qui'",
            11f, Color.parseColor("#AAFFFFFF")).also { it.setPadding(0, dp(2), 0, 0) })
        root.addView(hudTop)

        // ── HUD Inferiore ─────────────────────────────────────────
        val hudBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD001020"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM }
            setPadding(dp(16), dp(10), dp(16), dp(20))
        }

        // Riga pulsanti azione (visibile solo in manual/combined)
        if (placementMode != "auto") {
            val rowBtns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(8) }
            }
            btnPlaceHere = Button(this).apply {
                text = "📍 Piazza qui (mirino)"
                setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#FF4CAF50"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 2.5f).also { it.marginEnd = dp(8) }
                setOnClickListener { placeAtMapCenter() }
            }
            btnTrap = Button(this).apply {
                text = "Uovo"; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#CC1565C0")); textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).also { it.marginEnd = dp(8) }
                setOnClickListener { toggleTrap() }
            }
            btnUndo = Button(this).apply {
                text = "<<"; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#CC7B1FA2")); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                isEnabled = false; setOnClickListener { undoLast() }
            }
            rowBtns.addView(btnPlaceHere); rowBtns.addView(btnTrap); rowBtns.addView(btnUndo)
            hudBottom.addView(rowBtns)
        }

        btnLaunch = Button(this).apply {
            text = if (isMultiplayer) "CREA STANZA e avvia" else "AVVIA CACCIA!"
            setTextColor(Color.parseColor("#001020"))
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#FFD700")); textSize = 15f
            visibility = if (placementMode == "auto") View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { launchGame() }
        }
        hudBottom.addView(btnLaunch)
        root.addView(hudBottom)
        setContentView(root)
    }

    // ── Piazzamento uova ──────────────────────────────────────────

    /** Piazza un uovo al centro della mappa (crosshair) — funziona anche senza GPS */
    private fun placeAtMapCenter() {
        val center = mapView.mapCenter
        addEgg(center.latitude, center.longitude)
    }

    /** Piazza un uovo alle coordinate indicate */
    private fun addEgg(lat: Double, lng: Double, isTrapOverride: Boolean = nextIsTrap) {
        val id       = eggs.size
        val colorIdx = id % EGG_COLORS.size
        val isTrap   = isTrapOverride
        eggs.add(OutdoorEgg(id = id, lat = lat, lng = lng,
            label    = if (isTrap) "Trappola #${id+1}" else "Uovo #${id+1}",
            colorIdx = colorIdx, isTrap = isTrap))

        val center = GeoPoint(lat, lng)
        val color  = EGG_COLORS[colorIdx]

        val marker = Marker(mapView).apply {
            position = center
            title    = if (isTrap) "Trappola #${id+1}" else "Uovo #${id+1}"
            snippet  = if (isTrap) "TRAPPOLA — solo tu la vedi" else "Uovo #${id+1}"
            icon     = makeMarker(if (isTrap) Color.parseColor("#CC0000") else color,
                if (isTrap) "T${id+1}" else "${id+1}", 32)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { m, _ -> m.showInfoWindow(); true }
        }
        mapView.overlays.add(marker); eggMarkers[id] = marker

        // Cerchio raggio (20m = reveal dist)
        mapView.overlays.add(Polygon(mapView).apply {
            points      = Polygon.pointsAsCircle(center, 20.0)
            fillColor   = if (isTrap) 0x22FF4444.toInt() else 0x2244DD44.toInt()
            strokeColor = if (isTrap) 0xAAFF4444.toInt() else 0xAA44DD44.toInt()
            strokeWidth = 2f
        }.also { eggCircles[id] = it })

        mapView.invalidate()
        updateHud()
    }

    /** Auto-piazzamento casuale nell'area visibile della mappa */
    private fun autoPlaceEggs() {
        val bb: BoundingBox = mapView.boundingBox
        val latMin = bb.latSouth; val latMax = bb.latNorth
        val lngMin = bb.lonWest;  val lngMax = bb.lonEast
        val latRange = latMax - latMin; val lngRange = lngMax - lngMin

        // Riduce l'area dell'80% (evita i bordi)
        val margin = 0.1
        val placed = mutableListOf<Pair<Double, Double>>()

        repeat(autoEggCount + autoTrapCount) { i ->
            var attempts = 0
            var found = false
            while (!found && attempts < 60) {
                attempts++
                val lat = latMin + latRange * (margin + Random.nextDouble() * (1 - 2*margin))
                val lng = lngMin + lngRange * (margin + Random.nextDouble() * (1 - 2*margin))
                // Distanza minima tra uova: ~10m
                val tooClose = placed.any { (la, lo) ->
                    val dLat = (lat - la) * 111000
                    val dLng = (lng - lo) * 111000 * cos(Math.toRadians(lat))
                    sqrt(dLat*dLat + dLng*dLng) < 10.0
                }
                if (!tooClose) {
                    val isTrap = i >= autoEggCount   // le ultime N sono trappole
                    addEgg(lat, lng, isTrapOverride = isTrap)
                    placed.add(lat to lng)
                    found = true
                }
            }
        }

        runOnUiThread {
            val real  = eggs.count { !it.isTrap }
            val traps = eggs.count { it.isTrap }
            Toast.makeText(this,
                "$real uova${if (traps > 0) " + $traps trappole" else ""} piazzate automaticamente!",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun undoLast() {
        if (eggs.isEmpty()) return
        val last = eggs.removeLast()
        eggMarkers.remove(last.id)?.let { mapView.overlays.remove(it) }
        eggCircles.remove(last.id)?.let { mapView.overlays.remove(it) }
        mapView.invalidate(); updateHud()
    }

    private fun toggleTrap() {
        nextIsTrap = !nextIsTrap
        if (nextIsTrap) {
            btnTrap.text = "Trappola"; btnTrap.setBackgroundColor(Color.parseColor("#CCB71C1C"))
            btnPlaceHere.text = "Piazza trappola"; btnPlaceHere.setBackgroundColor(Color.parseColor("#CCB71C1C"))
        } else {
            btnTrap.text = "Uovo"; btnTrap.setBackgroundColor(Color.parseColor("#CC1565C0"))
            btnPlaceHere.text = "Piazza qui (mirino)"; btnPlaceHere.setBackgroundColor(Color.parseColor("#FF4CAF50"))
        }
    }

    private fun updateHud() {
        val real  = eggs.count { !it.isTrap }
        val traps = eggs.count { it.isTrap }
        tvEggCount.text = "$real uova${if (traps > 0) " +$traps trap" else ""}"
        if (::btnUndo.isInitialized) btnUndo.isEnabled = eggs.isNotEmpty()
        btnLaunch.visibility = if (real > 0) View.VISIBLE else View.GONE
    }

    // ── Avvia caccia ──────────────────────────────────────────────

    private fun launchGame() {
        val real = eggs.count { !it.isTrap }
        if (real == 0) { Toast.makeText(this, "Aggiungi almeno 1 uovo!", Toast.LENGTH_SHORT).show(); return }

        val hostId   = OutdoorRoomManager.generatePlayerId()
        val hostName = players.firstOrNull() ?: "Host"

        val sess = OutdoorSession(
            id            = GameDataManager.get(this).newRunId(),
            createdAt     = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date()),
            players       = players,
            eggs          = eggs.toList(),
            penaltySecs   = penaltySecs,
            isMultiplayer = isMultiplayer
        )

        if (isMultiplayer) {
            btnLaunch.isEnabled = false; btnLaunch.text = "Caricamento..."
            OutdoorRoomManager.createRoom(
                hostId      = hostId,
                hostName    = hostName,
                roomName    = roomName,
                isPublic    = isPublicRoom,
                eggs        = eggs.toList(),
                penaltySecs = penaltySecs,
                ttlDays     = ttlDays,
                onSuccess   = { code ->
                    runOnUiThread { showRoomCodeDialog(code, sess, hostId) }
                },
                onError     = { msg ->
                    runOnUiThread {
                        btnLaunch.isEnabled = true; btnLaunch.text = "CREA STANZA e avvia"
                        Toast.makeText(this, "Errore Firebase: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            )
        } else {
            launchHuntActivity(sess, roomCode = "", hostId = "")
        }
    }

    private fun showRoomCodeDialog(code: String, sess: OutdoorSession, hostId: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("✅ Stanza creata!")
            .setMessage(
                "Codice stanza:\n\n$code\n\n" +
                "Durata: $ttlDays giorni\n" +
                "${if (isPublicRoom) "Stanza pubblica (visibile in lista)" else "Stanza privata (solo codice)"}\n\n" +
                "Condividi il codice con i giocatori.\nPremi OK per iniziare la caccia come host."
            )
            .setPositiveButton("OK — Inizia!") { _, _ ->
                launchHuntActivity(sess.copy(roomCode = code), roomCode = code, hostId = hostId)
            }
            .setCancelable(false)
            .show()
    }

    private fun launchHuntActivity(sess: OutdoorSession, roomCode: String, hostId: String) {
        startActivity(Intent(this, OutdoorHuntActivity::class.java).apply {
            putExtra(EXTRA_SESSION_JSON, sess.toJson().toString())
            putExtra(OutdoorModeActivity.EXTRA_IS_MP,      isMultiplayer)
            putExtra(OutdoorModeActivity.EXTRA_IS_HOST,    isHost)
            putExtra(OutdoorModeActivity.EXTRA_ROOM_CODE,  roomCode)
            putExtra(OutdoorModeActivity.EXTRA_PLAYER_NAME, players.firstOrNull() ?: "Host")
            putExtra(OutdoorModeActivity.EXTRA_PLAYER_ID,  hostId)
            putExtra(OutdoorModeActivity.EXTRA_PENALTY_SECS, penaltySecs)
        })
    }

    // ── GPS helpers ───────────────────────────────────────────────

    private fun updateMyMarker(pt: GeoPoint) {
        if (myMarker == null) {
            myMarker = Marker(mapView).apply {
                icon = makeMarker(Color.parseColor("#2979FF"), "", 18)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
        }
        myMarker!!.position = pt; mapView.invalidate()
    }

    private fun checkGpsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION), LOC_PERM)
        } else startGps()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == LOC_PERM && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps()
    }

    private fun startGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 1f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, locationListener)
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                lastLocation = loc
                mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                mapView.controller.setZoom(18.0)
                updateMyMarker(GeoPoint(loc.latitude, loc.longitude))
                tvCoords.text = "${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
            }
        } catch (_: Exception) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onResume()  { super.onResume();  mapView.onResume() }
    override fun onPause()   {
        super.onPause(); mapView.onPause()
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
    }
    override fun onDestroy() { super.onDestroy(); mapView.onDetach() }

    // ── Helpers ───────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }

    private fun makeMarker(color: Int, label: String, sizeDp: Int): BitmapDrawable {
        val dp   = resources.displayMetrics.density
        val size = (sizeDp * dp).toInt().coerceAtLeast(1)
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)
        val p    = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x44000000
        c.drawCircle(size/2f, size/2f + dp, size/2f - dp, p)
        p.color = color; p.style = Paint.Style.FILL
        c.drawCircle(size/2f, size/2f, size/2f - 2*dp, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2*dp
        c.drawCircle(size/2f, size/2f, size/2f - 2*dp, p)
        if (label.isNotBlank()) {
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            p.textSize = sizeDp * dp * 0.38f; p.textAlign = Paint.Align.CENTER
            val fm = p.fontMetrics
            c.drawText(label, size/2f, size/2f - (fm.top + fm.bottom)/2, p)
        }
        return BitmapDrawable(resources, bmp)
    }
}
