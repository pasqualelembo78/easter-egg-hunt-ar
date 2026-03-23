package com.intelligame.easteregghuntar

import android.animation.*
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * OutdoorModeActivity — portale spettacolare verso il Mondo Aperto.
 * Nient'altro. Solo questa soglia animata.
 */
class OutdoorModeActivity : AppCompatActivity() {

    companion object {
        // Queste costanti rimangono per compatibilità con le altre Activity
        const val EXTRA_PLACEMENT_MODE  = "outdoor_placement_mode"
        const val EXTRA_AUTO_EGG_COUNT  = "outdoor_auto_egg_count"
        const val EXTRA_AUTO_TRAP_COUNT = "outdoor_auto_trap_count"
        const val EXTRA_PENALTY_SECS    = "outdoor_penalty_secs"
        const val EXTRA_TTL_DAYS        = "outdoor_ttl_days"
        const val EXTRA_ROOM_NAME       = "outdoor_room_name"
        const val EXTRA_IS_PUBLIC       = "outdoor_is_public"
        const val EXTRA_IS_MP           = "outdoor_is_mp"
        const val EXTRA_IS_HOST         = "outdoor_is_host"
        const val EXTRA_ROOM_CODE       = "outdoor_room_code"
        const val EXTRA_PLAYER_NAME     = "outdoor_player_name"
        const val EXTRA_PLAYER_ID       = "outdoor_player_id"
    }

    private val eggEmojis = listOf("🥚","🐣","🐥","🐤","🪺","🌸","🌼","🌷","🦋","✨","🌈","🍀")
    private val floatingViews = mutableListOf<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var spawnRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        buildPortal(root)
        setContentView(root)
    }

    private fun buildPortal(root: FrameLayout) {
        // ── Sfondo gradiente animato ─────────────────────────────
        val bg = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#0A0022"),
                    Color.parseColor("#1A0040"),
                    Color.parseColor("#0D1B3F"),
                    Color.parseColor("#001820")
                )
            ).also { it.gradientType = GradientDrawable.LINEAR_GRADIENT }
        }
        root.addView(bg)

        // ── Canvas uova fluttuanti (layer) ───────────────────────
        val floatLayer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(floatLayer)

        // ── Cerchio portale pulsante ──────────────────────────────
        val portalGlow = View(this).apply {
            val size = dp(260)
            layoutParams = FrameLayout.LayoutParams(size, size).also { it.gravity = Gravity.CENTER }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#660080FF"), Color.parseColor("#00000000"))
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = size / 2f
            }
        }
        root.addView(portalGlow)

        // Pulsa
        ObjectAnimator.ofFloat(portalGlow, "alpha", 0.4f, 1f, 0.4f).apply {
            duration = 2400; repeatCount = ValueAnimator.INFINITE
            start()
        }
        ObjectAnimator.ofFloat(portalGlow, "scaleX", 0.85f, 1.15f, 0.85f).apply {
            duration = 3000; repeatCount = ValueAnimator.INFINITE; start()
        }
        ObjectAnimator.ofFloat(portalGlow, "scaleY", 0.85f, 1.15f, 0.85f).apply {
            duration = 3000; repeatCount = ValueAnimator.INFINITE; start()
        }

        // ── Cerchio portale bordo ─────────────────────────────────
        val portalRing = View(this).apply {
            val size = dp(220)
            layoutParams = FrameLayout.LayoutParams(size, size).also { it.gravity = Gravity.CENTER }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(4), Color.parseColor("#8844DDFF"))
            }
        }
        root.addView(portalRing)
        ObjectAnimator.ofFloat(portalRing, "rotation", 0f, 360f).apply {
            duration = 12000; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator(); start()
        }

        // ── Contenuto centrale ────────────────────────────────────
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.CENTER }
            setPadding(dp(32), 0, dp(32), dp(80))
        }

        // Emoji uovo principale
        val mainEgg = TextView(this).apply {
            text = "🌍"; textSize = 80f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        center.addView(mainEgg)

        // Bounce animazione uovo
        ObjectAnimator.ofFloat(mainEgg, "translationY", 0f, -dp(16).toFloat(), 0f).apply {
            duration = 2000; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator(); start()
        }

        // Titolo
        val tvTitle = TextView(this).apply {
            text = "MONDO APERTO"
            textSize = 30f; setTextColor(Color.parseColor("#FFD700"))
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        center.addView(tvTitle)

        // Sottotitolo
        val tvSub = TextView(this).apply {
            text = "Cattura uova nel mondo reale"
            textSize = 15f; setTextColor(Color.parseColor("#AAEEFFFF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }
        center.addView(tvSub)

        val tvSub2 = TextView(this).apply {
            text = "Rarità • Palestre • Livelli • Classifica"
            textSize = 12f; setTextColor(Color.parseColor("#66CCFFAA"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(40) }
        }
        center.addView(tvSub2)

        // Bottone ENTRA
        val btnEnter = Button(this).apply {
            text = "✨  ENTRA NEL MONDO  ✨"
            setTextColor(Color.parseColor("#001020"))
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textSize = 17f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(32).toFloat()
                colors = intArrayOf(Color.parseColor("#FFD700"), Color.parseColor("#FF8C00"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
            )
            elevation = dp(8).toFloat()
            setOnClickListener { enterWorld() }
        }
        center.addView(btnEnter)

        // Pulsa bottone
        ObjectAnimator.ofFloat(btnEnter, "scaleX", 1f, 1.04f, 1f).apply {
            duration = 1800; repeatCount = ValueAnimator.INFINITE; start()
        }
        ObjectAnimator.ofFloat(btnEnter, "scaleY", 1f, 1.04f, 1f).apply {
            duration = 1800; repeatCount = ValueAnimator.INFINITE; start()
        }

        root.addView(center)

        // Freccia back in alto
        val btnBack = TextView(this).apply {
            text = "‹ Torna alla casa"
            textSize = 14f; setTextColor(Color.parseColor("#88FFFFFF"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.TOP or Gravity.START; it.topMargin = dp(48); it.leftMargin = dp(20) }
            setOnClickListener { finish() }
        }
        root.addView(btnBack)

        // Avvia pioggia di uova fluttuanti
        startEggRain(floatLayer)
    }

    private fun enterWorld() {
        startActivity(Intent(this, OutdoorWorldActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ── Effetto pioggia di emoji uova ─────────────────────────────

    private fun startEggRain(layer: FrameLayout) {
        layer.post {
            val w = layer.width.takeIf { it > 0 } ?: dp(360)
            val h = layer.height.takeIf { it > 0 } ?: dp(800)
            spawnRunnable = object : Runnable {
                override fun run() {
                    spawnFloatingEgg(layer, w, h)
                    handler.postDelayed(this, (600L..1400L).random())
                }
            }
            handler.post(spawnRunnable!!)
        }
    }

    private fun spawnFloatingEgg(layer: FrameLayout, w: Int, h: Int) {
        val tv = TextView(this).apply {
            text = eggEmojis.random()
            textSize = (12f..28f).random()
            alpha = 0f
            x = (0 until w).random().toFloat()
            y = h.toFloat() + dp(40)
        }
        layer.addView(tv); floatingViews.add(tv)

        val duration = (4000L..7000L).random()
        val targetX = tv.x + (-dp(60)..dp(60)).random()

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(tv, "translationY", 0f, -h.toFloat() - dp(80)),
                ObjectAnimator.ofFloat(tv, "alpha", 0f, 0.7f, 0.7f, 0f),
                ObjectAnimator.ofFloat(tv, "translationX", 0f, targetX - tv.x),
                ObjectAnimator.ofFloat(tv, "rotation", 0f, (-30f..30f).random())
            )
            this.duration = duration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    layer.removeView(tv); floatingViews.remove(tv)
                }
            })
            start()
        }
    }

    private fun LongRange.random() = first + (Math.random() * (last - first)).toLong()
    private fun ClosedFloatingPointRange<Float>.random() =
        start + (Math.random() * (endInclusive - start)).toFloat()
    private fun IntRange.random() = first + (Math.random() * (last - first)).toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        spawnRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
    }
}
