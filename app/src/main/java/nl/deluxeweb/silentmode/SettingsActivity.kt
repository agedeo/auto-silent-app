package nl.deluxeweb.silentmode

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.deluxeweb.silentmode.data.LocalDatabase // Let op: De nieuwe database!

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Instellingen"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // Deze functie toont de pop-up met de technische status
    fun showPermissionStatusDialog() {
        val sb = StringBuilder()

        // 1. Locatie Check
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        if (fine && bg) {
            sb.append(getString(R.string.check_location_ok)).append("\n\n")
        } else {
            sb.append(getString(R.string.check_location_bad)).append("\n\n")
        }

        // 2. Niet Storen Check
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val dnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) nm.isNotificationPolicyAccessGranted else true

        if (dnd) {
            sb.append(getString(R.string.check_dnd_ok)).append("\n\n")
        } else {
            sb.append(getString(R.string.check_dnd_bad)).append("\n\n")
        }

        // 3. Batterij Check
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val bat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(packageName) else true

        if (bat) {
            sb.append(getString(R.string.check_bat_ok))
        } else {
            sb.append(getString(R.string.check_bat_bad))
        }

        AlertDialog.Builder(this)
            .setTitle("Status Machtigingen")
            .setMessage(sb.toString())
            .setPositiveButton("Ok", null)
            .show()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            // 1. OPEN ANDROID DND INSTELLINGEN
            findPreference<Preference>("open_android_dnd")?.setOnPreferenceClickListener {
                try {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
                    } else {
                        Intent(Settings.ACTION_SOUND_SETTINGS)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e2: Exception) {
                        Toast.makeText(requireContext(), "Kan instellingen niet openen", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }

            // 2. Machtigingen Controleren
            findPreference<Preference>("check_permissions")?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.showPermissionStatusDialog()
                true
            }

            // 3. Bekijk Zones (Lijstweergave)
            findPreference<Preference>("view_geofences")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), GeofenceListActivity::class.java))
                true
            }

            // 4. Scan Safe Zone (Thuis/Werk toevoegen)
            findPreference<Preference>("scan_safe_zone")?.setOnPreferenceClickListener {
                val act = activity as? AppCompatActivity
                if (act != null) {
                    SafeZoneHelper(act).scanAndIgnoreCurrentLocation()
                }
                true
            }

            // 5. Lijst Wissen (Thuis/Werk verwijderen)
            findPreference<Preference>("clear_ignored")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Lijst wissen?")
                    .setMessage("Weet je zeker dat je alle geblokkeerde locaties weer wilt toestaan?")
                    .setPositiveButton("Ja, wissen") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            // Let op: Gebruik de NIEUWE LocalDatabase
                            val db = LocalDatabase.getDatabase(requireContext())
                            db.ignoredLocationDao().clearAll()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Lijst is leeggemaakt! ✅", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Annuleer", null)
                    .show()
                true
            }

            // 6. Handmatige Database Update
            findPreference<Preference>("update_db")?.setOnPreferenceClickListener {
                Toast.makeText(requireContext(), "Controleren op updates...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val manager = UpdateManager(requireContext())
                    val result = manager.downloadUpdate(force = false) // Gewone check
                    withContext(Dispatchers.Main) {
                        when(result) {
                            UpdateResult.SUCCESS -> Toast.makeText(requireContext(), "Gedownload! ✅", Toast.LENGTH_LONG).show()
                            UpdateResult.UP_TO_DATE -> Toast.makeText(requireContext(), "Je bent al bij! 👍", Toast.LENGTH_SHORT).show()
                            UpdateResult.FAILED -> Toast.makeText(requireContext(), "Mislukt ❌", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                true
            }

            // 7. BUGFIX: Direct updaten als categorieën wijzigen
            val catPref = findPreference<MultiSelectListPreference>("active_categories")
            catPref?.setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(requireContext(), "Wijzigingen toepassen...", Toast.LENGTH_SHORT).show()

                lifecycleScope.launch(Dispatchers.IO) {
                    // Forceer update omdat de filter-criteria zijn veranderd
                    val manager = UpdateManager(requireContext())
                    val result = manager.downloadUpdate(force = true)

                    withContext(Dispatchers.Main) {
                        if (result == UpdateResult.SUCCESS) {
                            Toast.makeText(requireContext(), "Database bijgewerkt! ✅", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "Kon nieuwe locaties niet laden ❌", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                true // True = sla de wijziging (de vinkjes) op
            }

            // 8. Privacy Scherm openen
            findPreference<Preference>("open_privacy")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), PrivacyActivity::class.java))
                true
            }

            // 9. Wizard Herstarten
            findPreference<Preference>("restart_wizard")?.setOnPreferenceClickListener {
                val intent = Intent(requireContext(), WizardActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                true
            }
        }
    }
}