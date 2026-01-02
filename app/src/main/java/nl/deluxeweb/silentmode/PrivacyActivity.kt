package nl.deluxeweb.silentmode

import android.os.Bundle
import android.text.Html
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)

        // Terugknop in titelbalk (optioneel, als je een Action Bar hebt)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.privacy_title)

        // Als je HTML formatting wilt gebruiken (dikgedrukte koppen):
        val txtContent = findViewById<TextView>(R.id.txtPrivacyContent) // Zorg dat ID in XML staat!
        val text = getString(R.string.privacy_full_text)
        txtContent.text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}