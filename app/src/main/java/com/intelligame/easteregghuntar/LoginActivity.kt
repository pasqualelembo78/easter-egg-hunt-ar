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
 * LoginActivity — Login con Google o Facebook (o continua come ospite).
 *
 * Nota implementativa: Google Sign-In richiede la configurazione di
 * google-services.json con SHA-1 e l'abilitazione di Google Sign-In
 * in Firebase Console. Facebook Login richiede l'aggiunta di
 * facebook_app_id in strings.xml e l'SDK Facebook.
 *
 * Questa schermata è pronta per l'integrazione; per ora mostra
 * l'UI completa e gestisce il flusso "Ospite" funzionante.
 */
class LoginActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        // Se già loggato, vai diretto a HomeActivity
        val prefs = getSharedPreferences("login_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("logged_in", false)) {
            goHome(); return
        }

        buildUI()
    }

    private fun buildUI() {
        val root = FrameLayout(this)

        // ── Sfondo gradiente ─────────────────────────────────────
        root.setBackgroundColor(Color.parseColor("#080E24"))
        val gradBg = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#0A0022"),
                    Color.parseColor("#0D1B3F"),
                    Color.parseColor("#080E24")
                )
            )
        }
        root.addView(gradBg)

        // ── Uova fluttuanti di sfondo ─────────────────────────────
        val floatLayer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(floatLayer)
        floatLayer.post { startFloatingEggs(floatLayer) }

        // ── Contenuto centrale ────────────────────────────────────
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(80), dp(32), dp(48))
        }
        scroll.addView(content)

        // Logo + titolo
        val tvLogo = TextView(this).apply {
            text = "🐣"; textSize = 80f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
        }
        content.addView(tvLogo)
        ObjectAnimator.ofFloat(tvLogo, "translationY", 0f, -dp(12).toFloat(), 0f).apply {
            duration = 2200; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator(); start()
        }

        val tvTitle = makeText("Easter Egg Huntar", 28f, Color.parseColor("#FFD700"), bold = true, center = true)
        tvTitle.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(4) }
        content.addView(tvTitle)

        val tvSub = makeText("Caccia alle uova — reale, aumentata, leggendaria",
            13f, Color.parseColor("#88CCFFCC"), center = true)
        tvSub.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(48) }
        content.addView(tvSub)

        // ── Divisore ─────────────────────────────────────────────
        val tvAccess = makeText("Accedi o registrati", 15f, Color.WHITE, center = true)
        tvAccess.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(20) }
        content.addView(tvAccess)

        // ── Bottone Google ────────────────────────────────────────
        val btnGoogle = makeLoginButton(
            label    = "  Continua con Google",
            emoji    = "🔵",
            bgColor  = Color.WHITE,
            txtColor = Color.parseColor("#1A1A1A")
        ) { onGoogleLogin() }
        content.addView(btnGoogle)
        (btnGoogle.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)

        // ── Bottone Facebook ──────────────────────────────────────
        val btnFacebook = makeLoginButton(
            label    = "  Continua con Facebook",
            emoji    = "🔷",
            bgColor  = Color.parseColor("#1877F2"),
            txtColor = Color.WHITE
        ) { onFacebookLogin() }
        content.addView(btnFacebook)
        (btnFacebook.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(28)

        // ── Divider ───────────────────────────────────────────────
        val divRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)
            ).also { it.bottomMargin = dp(20) }
        }
        val divL = View(this).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val divTv = makeText("oppure", 12f, Color.parseColor("#66FFFFFF"), center = true)
        divTv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.marginStart = dp(12); it.marginEnd = dp(12) }
        val divR = View(this).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        divRow.addView(divL); divRow.addView(divTv); divRow.addView(divR)
        content.addView(divRow)

        // ── Ospite ────────────────────────────────────────────────
        val btnGuest = TextView(this).apply {
            text = "Continua come ospite →"
            textSize = 14f; setTextColor(Color.parseColor("#88FFFFFF"))
            gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(24) }
            setOnClickListener { onGuestLogin() }
        }
        content.addView(btnGuest)

        // Note legali
        val tvLegal = makeText(
            "Accedendo accetti i Termini di Servizio e la Privacy Policy",
            10f, Color.parseColor("#44FFFFFF"), center = true
        )
        content.addView(tvLegal)

        root.addView(scroll)
        setContentView(root)
    }

    // ── Login handlers ────────────────────────────────────────────

    private fun onGoogleLogin() {
        // TODO: implementare Google Sign-In SDK
        // Per ora mostra un toast e procede come ospite (placeholder)
        Toast.makeText(this,
            "Google Sign-In: aggiungi SHA-1 in Firebase Console e abilita Google Sign-In",
            Toast.LENGTH_LONG).show()
        // Quando l'integrazione è pronta, sostituire con:
        // val signInIntent = googleSignInClient.signInIntent
        // startActivityForResult(signInIntent, RC_SIGN_IN)
        proceedLogin(provider = "google", displayName = "Giocatore Google")
    }

    private fun onFacebookLogin() {
        // TODO: implementare Facebook Login SDK
        Toast.makeText(this,
            "Facebook Login: aggiungi facebook_app_id in strings.xml e configura l'SDK Facebook",
            Toast.LENGTH_LONG).show()
        proceedLogin(provider = "facebook", displayName = "Giocatore Facebook")
    }

    private fun onGuestLogin() {
        proceedLogin(provider = "guest", displayName = "Ospite")
    }

    private fun proceedLogin(provider: String, displayName: String) {
        getSharedPreferences("login_prefs", MODE_PRIVATE).edit().apply {
            putBoolean("logged_in", true)
            putString("provider", provider)
            putString("display_name", displayName)
            apply()
        }
        goHome()
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    // ── UI helpers ────────────────────────────────────────────────

    private fun makeLoginButton(
        label: String, emoji: String,
        bgColor: Int, txtColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = "$emoji$label"
            textSize = 15f; setTextColor(txtColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(bgColor)
            }
            elevation = dp(4).toFloat()
            setOnClickListener { onClick() }
        }
    }

    private fun makeText(text: String, size: Float, color: Int,
                         bold: Boolean = false, center: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            if (center) gravity = Gravity.CENTER
        }

    private fun startFloatingEggs(layer: FrameLayout) {
        val eggs = listOf("🥚","🐣","🐥","🌸","🌼","✨","🦋","🍀")
        val w = layer.width.takeIf { it > 0 } ?: dp(360)
        val h = layer.height.takeIf { it > 0 } ?: dp(800)
        fun spawn() {
            val tv = TextView(this).apply {
                this.text = eggs.random(); textSize = (10f..22f).random(); alpha = 0f
                x = (0 until w).random().toFloat(); y = h.toFloat() + dp(30)
            }
            layer.addView(tv)
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(tv, "translationY", 0f, -h.toFloat() - dp(60)),
                    ObjectAnimator.ofFloat(tv, "alpha", 0f, 0.5f, 0.5f, 0f)
                )
                duration = (5000L..8000L).random()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) { layer.removeView(tv) }
                })
                start()
            }
            handler.postDelayed({ if (!isDestroyed) spawn() }, (800L..1800L).random())
        }
        spawn()
    }

    private fun LongRange.random() = first + (Math.random() * (last - first)).toLong()
    private fun ClosedFloatingPointRange<Float>.random() =
        start + (Math.random() * (endInclusive - start)).toFloat()
    private fun IntRange.random() = first + (Math.random() * (last - first)).toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
