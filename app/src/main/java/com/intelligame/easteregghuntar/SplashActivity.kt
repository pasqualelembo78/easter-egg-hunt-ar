package com.intelligame.easteregghuntar

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.BounceInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.sin
import kotlin.random.Random

/**
 * Schermata introduttiva animata — 5 secondi:
 * 1. Cielo notturno con stelle twinkling
 * 2. Uova pasquali cadono dall'alto
 * 3. Titolo app con animazione bounce
 * 4. "Tocca per iniziare" pulsante
 */
class SplashActivity : AppCompatActivity() {

    // StarField definita qui come inner class della Activity (NON dentro onCreate)
    // — evita l'errore "Class is not allowed here"
    private inner class StarFieldView(context: android.content.Context) : View(context) {

        // data class a livello top della inner class (non dentro un'altra inner class) — OK
        private val stars: List<FloatArray> = (0 until 80).map {
            floatArrayOf(
                Random.nextFloat() * 1080f,   // x
                Random.nextFloat() * 1920f,   // y
                Random.nextFloat() * 2.5f + 0.5f,  // radius
                Random.nextFloat(),            // alpha
                Random.nextFloat() * 0.02f + 0.005f // speed
            )
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 60_000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
            start()
        }

        override fun onDraw(canvas: Canvas) {
            stars.forEach { s ->
                s[3] = (s[3] + s[4]).let { if (it > 1f) 0f else it }
                val pulse = 0.4f + 0.6f * s[3]
                paint.alpha = (pulse * 255).toInt()
                canvas.drawCircle(s[0], s[1], s[2], paint)
            }
        }

        fun stopAnim() {
            animator.cancel()
        }
    }

    private var starField: StarFieldView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#080E24"))
        }

        // ── 1. Canvas stelle ─────────────────────────────────────
        starField = StarFieldView(this)
        root.addView(starField, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // ── 2. Uova cadenti ───────────────────────────────────────
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val eggEmojis = listOf("🥚", "🐣", "🐰", "🌸", "🌷", "✨", "🎀", "🌺")
        val eggViews = (0 until 12).map { i ->
            TextView(this).apply {
                text = eggEmojis[i % eggEmojis.size]
                textSize = Random.nextFloat() * 18f + 14f
                alpha = 0f
                x = Random.nextFloat() * screenW.toFloat()
                y = -120f
            }
        }
        eggViews.forEach { root.addView(it) }

        // ── 3. Logo centrale ──────────────────────────────────────
        val logoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
            scaleX = 0.4f; scaleY = 0.4f
        }
        val tvBunny = TextView(this).apply {
            text = "🐰"; textSize = 72f; gravity = Gravity.CENTER
        }
        val tvTitle = TextView(this).apply {
            text = "Easter Egg\nHunt AR"
            textSize = 34f
            setTextColor(Color.parseColor("#FFD700"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        val tvSub = TextView(this).apply {
            text = "🌸  Caccia alle uova in realtà aumentata  🌸"
            textSize = 13f; setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER; setPadding(0, 16, 0, 0)
        }
        logoLayout.addView(tvBunny); logoLayout.addView(tvTitle); logoLayout.addView(tvSub)
        val logoLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        root.addView(logoLayout, logoLp)

        // ── 4. Testo "Tocca per iniziare" ─────────────────────────
        val tvTap = TextView(this).apply {
            text = "✨  Tocca per iniziare  ✨"
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; alpha = 0f
        }
        val tapLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (screenH * 0.12f).toInt()
        }
        tvTap.layoutParams = tapLp
        root.addView(tvTap)

        // ── Versione ──────────────────────────────────────────────
        val tvVersion = TextView(this).apply {
            text = "v1.0"; textSize = 11f; setTextColor(Color.parseColor("#66FFFFFF")); gravity = Gravity.CENTER
        }
        val verLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 24 }
        tvVersion.layoutParams = verLp
        root.addView(tvVersion)

        setContentView(root)

        // ── Sequenza animazioni ───────────────────────────────────

        // Uova cadenti in cascata
        eggViews.forEachIndexed { i, egg ->
            handler.postDelayed({
                egg.alpha = 1f
                egg.animate().translationY((screenH + 100).toFloat())
                    .setDuration(2800).start()
            }, i * 180L + 200L)
        }

        // Logo appare dopo 600ms
        handler.postDelayed({
            logoLayout.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(900).setInterpolator(OvershootInterpolator(1.2f)).start()
            // Bounce coniglio
            handler.postDelayed({
                tvBunny.animate().translationY(-30f).setDuration(300)
                    .withEndAction {
                        tvBunny.animate().translationY(0f).setDuration(400)
                            .setInterpolator(BounceInterpolator()).start()
                    }.start()
            }, 600)
        }, 600)

        // "Tocca per iniziare" + pulsazione dopo 2s
        handler.postDelayed({
            tvTap.animate().alpha(1f).setDuration(600).start()
            ObjectAnimator.ofFloat(tvTap, "alpha", 1f, 0.3f).apply {
                duration = 900; repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE; startDelay = 600
                start()
            }
        }, 2000)

        // Auto-avanzamento dopo 5s
        handler.postDelayed({ if (!isFinishing) goToHome() }, 5000)

        // Tap → avanza subito (dopo 1s)
        handler.postDelayed({ root.setOnClickListener { goToHome() } }, 1000)
    }

    private fun goToHome() {
        if (isFinishing) return
        // Controlla se già loggato; altrimenti mostra LoginActivity
        val prefs = getSharedPreferences("login_prefs", MODE_PRIVATE)
        val dest = if (prefs.getBoolean("logged_in", false)) HomeActivity::class.java
                   else LoginActivity::class.java
        startActivity(Intent(this, dest))
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in, android.R.anim.fade_out)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        starField?.stopAnim()
    }
}
