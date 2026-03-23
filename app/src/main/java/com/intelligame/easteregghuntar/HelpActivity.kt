package com.intelligame.easteregghuntar

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080E24"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 72, 32, 40)
        }
        scroll.addView(root)

        val title = TextView(this)
        title.text = "❓ Aiuto"
        title.textSize = 22f
        title.setTextColor(Color.parseColor("#FFD700"))
        title.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
        title.setPadding(0, 0, 0, 20)
        root.addView(title)

        val body = TextView(this)
        body.text = getString(R.string.help_text)
        body.textSize = 14f
        body.setTextColor(Color.WHITE)
        body.setLineSpacing(0f, 1.6f)
        root.addView(body)

        setContentView(scroll)
    }
}
