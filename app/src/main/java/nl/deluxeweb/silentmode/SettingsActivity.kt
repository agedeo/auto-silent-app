package nl.deluxeweb.silentmode

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.deluxeweb.silentmode.data.AppDatabase
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }

        val btnGeofences = findViewById<Button>(R.id.btnViewGeofences)
        btnGeofences.setOnClickListener { startActivity(Intent(this, GeofenceListActivity::class.java)) }

        val btnClear = findViewById<Button>(R.id.btnClearIgnored)
        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Lijst wissen?")
                .setMessage("Weet je zeker dat je alle geblokkeerde locaties weer wilt toestaan?")
                .setPositiveButton("Ja, wissen") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(applicationContext)
                        db.ignoredLocationDao().clearAll()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, "Lijst is leeggemaakt! ✅", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Annuleer", null)
                .show()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsUI()
        startLiveLocation()
    }

    @SuppressLint("SetTextI18n")
    private fun checkPermissionsUI() {
        val chkLoc = findViewById<TextView>(R.id.chkLoc)
        val chkDnd = findViewById<TextView>(R.id.chkDnd)
        val chkBat = findViewById<TextView>(R.id.chkBat)

        val colorOk = ContextCompat.getColor(this, R.color.color_success)
        val colorBad = ContextCompat.getColor(this, R.color.color_error)
        val colorWarn = ContextCompat.getColor(this, R.color.color_warning)

        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED else true

        if (fine && bg) {
            chkLoc.text = getString(R.string.check_location_ok)
            chkLoc.setTextColor(colorOk)
        } else {
            chkLoc.text = getString(R.string.check_location_bad)
            chkLoc.setTextColor(colorBad)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val dnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) nm.isNotificationPolicyAccessGranted else true
        if (dnd) {
            chkDnd.text = getString(R.string.check_dnd_ok)
            chkDnd.setTextColor(colorOk)
        } else {
            chkDnd.text = getString(R.string.check_dnd_bad)
            chkDnd.setTextColor(colorBad)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val bat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(packageName) else true
        if (bat) {
            chkBat.text = getString(R.string.check_bat_ok)
            chkBat.setTextColor(colorOk)
        } else {
            chkBat.text = getString(R.string.check_bat_bad)
            chkBat.setTextColor(colorWarn)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLiveLocation() {
        val txtCoords = findViewById<TextView>(R.id.txtCoords)
        val txtAddress = findViewById<TextView>(R.id.txtAddress)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            txtCoords.text = "Geen toestemming"
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(this)

        client.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                updateLocationUI(location, txtCoords, txtAddress)
            }
        }

        val cancellationTokenSource = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    updateLocationUI(location, txtCoords, txtAddress)
                }
            }
            .addOnFailureListener {
                if (txtCoords.text == getString(R.string.gps_searching)) {
                    txtCoords.text = "Locatie niet gevonden (Ga even naar buiten?)"
                }
            }
    }

    private fun updateLocationUI(location: Location, txtCoords: TextView, txtAddress: TextView) {
        txtCoords.text = "GPS: ${location.latitude}, ${location.longitude}"

        lifecycleScope.launch(Dispatchers.IO) {
            var addressText = getString(R.string.address_unknown)
            try {
                val geocoder = Geocoder(applicationContext, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)

                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val straat = addr.thoroughfare ?: ""
                    val nummer = addr.subThoroughfare ?: ""
                    val stad = addr.locality ?: ""

                    addressText = if (straat.isNotEmpty()) {
                        "📍 $straat $nummer, $stad"
                    } else {
                        "📍 $stad"
                    }
                }
            } catch (e: Exception) {
                addressText = "📍 Adres niet gevonden (Geen internet?)"
            }

            withContext(Dispatchers.Main) {
                txtAddress.text = addressText
            }
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            // 1. Wizard Herstarten
            findPreference<Preference>("restart_wizard")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), WizardActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                true
            }

            // 2. Database Update
            findPreference<Preference>("update_db")?.setOnPreferenceClickListener {
                Toast.makeText(requireContext(), "Controleren op updates...", Toast.LENGTH_SHORT).show()

                lifecycleScope.launch(Dispatchers.IO) {
                    val manager = UpdateManager(requireContext())
                    // AANGEPAST: We gebruiken nu 'result' (enum) in plaats van 'success' (boolean)
                    val result = manager.downloadUpdate(force = false)

                    withContext(Dispatchers.Main) {
                        when(result) {
                            UpdateResult.SUCCESS -> {
                                Toast.makeText(requireContext(), "Nieuwe locaties gedownload! ✅", Toast.LENGTH_LONG).show()
                            }
                            UpdateResult.UP_TO_DATE -> {
                                Toast.makeText(requireContext(), "Je bent al helemaal bij! 👍", Toast.LENGTH_SHORT).show()
                            }
                            UpdateResult.FAILED -> {
                                Toast.makeText(requireContext(), "Update mislukt. Check je internet. ❌", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                true
            }
        }
    }
}