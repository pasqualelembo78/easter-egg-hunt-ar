package com.intelligame.easteregghuntar

import android.animation.*
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.*

/**
 * OutdoorArCatchActivity — cattura in stile Pokémon GO.
 *
 * Schermata a pieno schermo con:
 *  - Sfondo "AR" (gradiente notte/erba — simula il mondo esterno)
 *  - Uovo gigante animato al centro
 *  - Cerchio target pulsante intorno all'uovo
 *  - Swipe verso l'alto per lanciare il cestello
 *  - Probabilità di successo inversamente proporzionale alla rarità
 *  - Il cestello può rompersi se la forza del giocatore è insufficiente
 */
class OutdoorArCatchActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val EXTRA_EGG_ID      = "ar_egg_id"
        const val EXTRA_EGG_NAME    = "ar_egg_name"
        const val EXTRA_EGG_RARITY  = "ar_egg_rarity"   // EggRarity.id
        const val EXTRA_EGG_XP      = "ar_egg_xp"
        const val EXTRA_EGG_POWER   = "ar_egg_power"
        const val EXTRA_PLAYER_LVL  = "ar_player_lvl"

        const val RESULT_CAUGHT     = "ar_caught"
        const val RESULT_EGG_ID     = "ar_caught_egg_id"
    }

    // ── Dati uovo ─────────────────────────────────────────────────
    private var eggId     = ""
    private var eggName   = "Uovo Misterioso"
    private var rarityId  = "common"
    private var eggXp     = 100
    private var eggPower  = 10
    private var playerLvl = 1

    // ── UI ────────────────────────────────────────────────────────
    private lateinit var rootFrame:     FrameLayout
    private lateinit var eggView:       TextView
    private lateinit var tvRarity:      TextView
    private lateinit var tvName:        TextView
    private lateinit var tvInstr:       TextView
    private lateinit var targetRing:    View
    private lateinit var basketView:    TextView
    private lateinit var tvResult:      TextView
    private lateinit var btnClose:      TextView

    // ── Gestione cestello ─────────────────────────────────────────
    private var throwStartY   = 0f
    private var throwStartX   = 0f
    private var isTracking    = false
    private var basketAnimSet: AnimatorSet? = null
    private var attempts      = 0
    private val maxAttempts   = 3
    private var caught        = false

    // ── Bussola (sensor) ─────────────────────────────────────────
    private lateinit var sensorMgr: SensorManager
    private val accV = FloatArray(3); private val magV = FloatArray(3)

    // ── Handler ───────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        eggId     = intent.getStringExtra(EXTRA_EGG_ID) ?: ""
        eggName   = intent.getStringExtra(EXTRA_EGG_NAME) ?: "Uovo Misterioso"
        rarityId  = intent.getStringExtra(EXTRA_EGG_RARITY) ?: "common"
        eggXp     = intent.getIntExtra(EXTRA_EGG_XP, 100)
        eggPower  = intent.getIntExtra(EXTRA_EGG_POWER, 10)
        playerLvl = intent.getIntExtra(EXTRA_PLAYER_LVL, 1)

        sensorMgr = getSystemService(SENSOR_SERVICE) as SensorManager

        buildUI()
    }

    // ── Build UI ──────────────────────────────────────────────────

    private fun buildUI() {
        val rarity = EggRarity.fromId(rarityId)

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A1A"))
        }

        // ── Sfondo finto AR (cielo + erba) ───────────────────────
        val sky = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#0A1A2E"),
                    Color.parseColor("#1A2840"),
                    Color.parseColor("#0E3020"),
                    Color.parseColor("#081808")
                )
            )
        }
        rootFrame.addView(sky)

        // Stelle di sfondo
        val starsLayer = StarfieldView(this)
        starsLayer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        rootFrame.addView(starsLayer)

        // ── HUD superiore ─────────────────────────────────────────
        val hudTop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#BB000010"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.TOP }
            setPadding(dp(20), dp(44), dp(20), dp(12))
        }
        tvRarity = TextView(this).apply {
            text = "${rarity.emoji}  ${rarity.displayName}"
            textSize = 14f; setTextColor(Color.parseColor(rarityColorHex(rarity)))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER
        }
        tvName = TextView(this).apply {
            text = eggName; textSize = 20f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(2) }
        }
        val tvReward = TextView(this).apply {
            text = "+$eggXp XP  ·  +$eggPower ⚡"
            textSize = 12f; setTextColor(Color.parseColor("#AAFFD700")); gravity = Gravity.CENTER
        }
        hudTop.addView(tvRarity); hudTop.addView(tvName); hudTop.addView(tvReward)
        rootFrame.addView(hudTop)

        // ── Ring target pulsante ──────────────────────────────────
        val ringSize = dp(200)
        targetRing = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(ringSize, ringSize).also {
                it.gravity = Gravity.CENTER
                it.bottomMargin = dp(60)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("20${rarityColorHex(rarity).replace("#","")}"))
                setStroke(dp(4), Color.parseColor(rarityColorHex(rarity)))
            }
        }
        rootFrame.addView(targetRing)

        // Anima ring
        ObjectAnimator.ofFloat(targetRing, "scaleX", 1f, 1.12f, 1f).apply {
            duration = 1200; repeatCount = ValueAnimator.INFINITE; start()
        }
        ObjectAnimator.ofFloat(targetRing, "scaleY", 1f, 1.12f, 1f).apply {
            duration = 1200; repeatCount = ValueAnimator.INFINITE; start()
        }
        ObjectAnimator.ofFloat(targetRing, "alpha", 0.5f, 1f, 0.5f).apply {
            duration = 1200; repeatCount = ValueAnimator.INFINITE; start()
        }

        // ── Uovo gigante ──────────────────────────────────────────
        eggView = TextView(this).apply {
            text = rarity.emoji.ifBlank { "🥚" }
            textSize = 96f; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(180), dp(180)).also {
                it.gravity = Gravity.CENTER
                it.bottomMargin = dp(60)
            }
        }
        rootFrame.addView(eggView)

        // Bob animazione uovo
        ObjectAnimator.ofFloat(eggView, "translationY", 0f, -dp(18).toFloat(), 0f).apply {
            duration = 2200; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator(); start()
        }

        // ── Cestello (parte dal basso e viene lanciato) ───────────
        basketView = TextView(this).apply {
            text = "🧺"; textSize = 44f; gravity = Gravity.CENTER
            alpha = 0f  // invisibile finché non si inizia lo swipe
        }
        rootFrame.addView(basketView)

        // ── Tentativi rimasti ─────────────────────────────────────
        val tvAttempts = TextView(this).apply {
            text = attemptsText()
            textSize = 13f; setTextColor(Color.parseColor("#88FFFFFF"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(120) }
            id = View.generateViewId()
        }
        rootFrame.addView(tvAttempts)

        // ── Istruzione ────────────────────────────────────────────
        tvInstr = TextView(this).apply {
            text = "↑  Scorri in su per lanciare il cestello  ↑"
            textSize = 14f; setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(72) }
        }
        rootFrame.addView(tvInstr)

        // ── Risultato (appare dopo tentativo) ─────────────────────
        tvResult = TextView(this).apply {
            text = ""; textSize = 26f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER; alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.CENTER; it.topMargin = dp(60) }
        }
        rootFrame.addView(tvResult)

        // ── Tasto chiudi ──────────────────────────────────────────
        btnClose = TextView(this).apply {
            text = "✕  Lascia andare"
            textSize = 13f; setTextColor(Color.parseColor("#77FFFFFF"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(24) }
            setOnClickListener { finishNotCaught() }
        }
        rootFrame.addView(btnClose)

        // ── Touch per swipe ───────────────────────────────────────
        rootFrame.setOnTouchListener { _, event ->
            if (!caught && attempts < maxAttempts) handleTouch(event, tvAttempts)
            true
        }

        setContentView(rootFrame)
    }

    // ── Gestione touch/swipe ──────────────────────────────────────

    private fun handleTouch(event: MotionEvent, tvAttempts: TextView) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (basketAnimSet?.isRunning == true) return
                throwStartX = event.x; throwStartY = event.y; isTracking = true
                // Mostra cestello nella posizione del dito
                basketView.apply {
                    alpha = 1f
                    x = throwStartX - dp(22).toFloat()
                    y = throwStartY - dp(22).toFloat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTracking) return
                basketView.x = event.x - dp(22).toFloat()
                basketView.y = event.y - dp(22).toFloat()
            }
            MotionEvent.ACTION_UP -> {
                if (!isTracking) return
                isTracking = false
                val dy = throwStartY - event.y
                val dx = event.x - throwStartX
                if (dy > dp(60)) {
                    // Lancio valido
                    throwBasket(dx, dy, tvAttempts)
                } else {
                    // Swipe troppo corto — rimetti via
                    basketView.alpha = 0f
                }
            }
        }
    }

    private fun throwBasket(dx: Float, dy: Float, tvAttempts: TextView) {
        val rarity = EggRarity.fromId(rarityId)
        attempts++
        tvAttempts.text = attemptsText()

        // Posizione uovo (centro schermo)
        val screenW = rootFrame.width.toFloat()
        val screenH = rootFrame.height.toFloat()
        val eggCX   = screenW / 2f
        val eggCY   = screenH / 2f - dp(60)

        // Traiettoria: da posizione attuale del cestello verso l'uovo
        val startX = basketView.x
        val startY = basketView.y

        // Forza lancio: normalizzata
        val force = (dy / screenH).coerceIn(0.1f, 1f)

        // Calcolo probabilità: rarità vs forza giocatore
        val catchProb = calculateCatchProbability(rarity, force, playerLvl)
        val breakProb = calculateBreakProbability(rarity, force)

        val animX = ObjectAnimator.ofFloat(basketView, "x", startX, eggCX - dp(22)).apply { duration = 600 }
        val animY = ObjectAnimator.ofFloat(basketView, "y", startY, eggCY - dp(22)).apply {
            duration = 600
            interpolator = android.view.animation.DecelerateInterpolator()
        }
        val animScale = ObjectAnimator.ofFloat(basketView, "scaleX", 1f, 0.6f).apply { duration = 600 }
        val animScaleY = ObjectAnimator.ofFloat(basketView, "scaleY", 1f, 0.6f).apply { duration = 600 }

        basketAnimSet = AnimatorSet().apply {
            playTogether(animX, animY, animScale, animScaleY)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    when {
                        Math.random() < breakProb -> onBasketBreak(tvAttempts)
                        Math.random() < catchProb -> onCatchSuccess()
                        else                      -> onCatchFail(tvAttempts)
                    }
                }
            })
            start()
        }
    }

    private fun onBasketBreak(tvAttempts: TextView) {
        // Cestello rotto: shake e effetto rosso
        showResult("💥 Il cestello si è rotto!", Color.parseColor("#FF4444"))
        basketView.text = "💥"
        ObjectAnimator.ofFloat(basketView, "rotation", -20f, 20f, -15f, 15f, 0f).apply {
            duration = 400; start()
        }
        handler.postDelayed({
            basketView.alpha = 0f; basketView.text = "🧺"
            basketView.scaleX = 1f; basketView.scaleY = 1f
            tvResult.alpha = 0f
            if (attempts >= maxAttempts) {
                onAllAttemptsFailed()
            } else {
                tvInstr.text = "Cestello rotto! Ritenta (${maxAttempts - attempts} rimasti)"
            }
        }, 2000L)
    }

    private fun onCatchFail(tvAttempts: TextView) {
        // Uovo scappa
        showResult("🐣 Ha schivato!", Color.parseColor("#FFAA44"))
        ObjectAnimator.ofFloat(eggView, "translationX", 0f, dp(30).toFloat(), -dp(30).toFloat(), 0f).apply {
            duration = 500; start()
        }
        handler.postDelayed({
            basketView.alpha = 0f
            basketView.scaleX = 1f; basketView.scaleY = 1f
            tvResult.alpha = 0f
            if (attempts >= maxAttempts) {
                onAllAttemptsFailed()
            } else {
                tvInstr.text = "Mancato! (${maxAttempts - attempts} tentativi rimasti)"
            }
        }, 2000L)
    }

    private fun onCatchSuccess() {
        caught = true
        // Uovo entra nel cestello
        showResult("✅ Preso! +$eggXp XP  +$eggPower ⚡", Color.parseColor("#44FF88"))
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(eggView, "scaleX", 1f, 0.3f),
                ObjectAnimator.ofFloat(eggView, "scaleY", 1f, 0.3f),
                ObjectAnimator.ofFloat(eggView, "alpha", 1f, 0f),
                ObjectAnimator.ofFloat(basketView, "scaleX", 0.6f, 1.3f, 1f),
                ObjectAnimator.ofFloat(basketView, "scaleY", 0.6f, 1.3f, 1f)
            )
            duration = 600; start()
        }
        SoundManager.playEggFound()
        handler.postDelayed({
            val data = Intent().apply {
                putExtra(RESULT_CAUGHT, true)
                putExtra(RESULT_EGG_ID, eggId)
            }
            setResult(RESULT_OK, data)
            finish()
        }, 2200L)
    }

    private fun onAllAttemptsFailed() {
        showResult("😢 L'uovo è scappato...", Color.parseColor("#FF6666"))
        tvInstr.text = ""
        ObjectAnimator.ofFloat(eggView, "translationY", eggView.translationY, -dp(400).toFloat()).apply {
            duration = 1200; start()
        }
        handler.postDelayed({ finishNotCaught() }, 2500L)
    }

    private fun finishNotCaught() {
        setResult(RESULT_CANCELED)
        finish()
    }

    // ── Probabilità ───────────────────────────────────────────────

    private fun calculateCatchProbability(rarity: EggRarity, force: Float, lvl: Int): Float {
        val base = rarity.catchRadius   // già normalizzato 0..1 (più raro = più basso)
        val bonus = (lvl - 1) * 0.03f  // +3% per livello
        val forceMult = (0.7f + force * 0.6f).coerceIn(0.5f, 1.3f)
        return ((base + bonus) * forceMult).coerceIn(0.05f, 0.92f)
    }

    private fun calculateBreakProbability(rarity: EggRarity, force: Float): Float {
        // Il cestello si rompe più facilmente se la forza è troppo bassa e l'uovo è forte
        val eggPowerNorm = 1f - rarity.catchRadius  // rarità alta = potere alto
        val forceDeficit = (0.4f - force).coerceAtLeast(0f)
        return (eggPowerNorm * forceDeficit * 2f).coerceIn(0f, 0.4f)
    }

    private fun rarityColorHex(r: EggRarity): String = when(r) {
        EggRarity.COMMON     -> "#AAAAAA"
        EggRarity.UNCOMMON   -> "#44CC44"
        EggRarity.RARE       -> "#4488FF"
        EggRarity.EPIC       -> "#AA44FF"
        EggRarity.LEGENDARY  -> "#FFAA00"
    }

    private fun attemptsText(): String {
        val left = maxAttempts - attempts
        return "🧺 ".repeat(left.coerceAtLeast(0)) + "☒ ".repeat(attempts.coerceAtMost(maxAttempts))
    }

    private fun showResult(msg: String, color: Int) {
        tvResult.text = msg; tvResult.setTextColor(color)
        ObjectAnimator.ofFloat(tvResult, "alpha", 0f, 1f).apply { duration = 300; start() }
    }

    // ── Sensor ───────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorMgr.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Piccola oscillazione dell'uovo basata sull'accelerometro
            val tiltX = e.values[0] * 2f
            eggView.translationX = -tiltX * dp(1)
        }
    }

    override fun onAccuracyChanged(s: Sensor, a: Int) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ── Vista stelle di sfondo ────────────────────────────────────

    inner class StarfieldView(context: android.content.Context) : View(context) {
        private val stars = mutableListOf<Triple<Float, Float, Float>>() // x, y, radius
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var initialized = false

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (initialized) return; initialized = true
            repeat(80) {
                stars.add(Triple(
                    (Math.random() * w).toFloat(),
                    (Math.random() * h * 0.7f).toFloat(),
                    (1f + Math.random() * 2.5f).toFloat()
                ))
            }
        }

        override fun onDraw(canvas: Canvas) {
            stars.forEach { (x, y, r) ->
                paint.alpha = (80 + Math.random() * 100).toInt()
                canvas.drawCircle(x, y, r, paint)
            }
        }
    }
}
