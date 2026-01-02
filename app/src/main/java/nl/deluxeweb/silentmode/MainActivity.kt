package nl.deluxeweb.silentmode

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.deluxeweb.silentmode.data.AppDatabase
import nl.deluxeweb.silentmode.data.IgnoredLocation
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var lblMode: TextView
    private lateinit var lblLocation: TextView
    private lateinit var txtStatusIcon: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnOverride: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnIgnoreLocation: TextView

    private lateinit var geofencingClient: GeofencingClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geofenceHelper: GeofenceHelper

    // Voor Live Tracking
    private lateinit var liveLocationCallback: LocationCallback
    private var updateJob: Job? = null

    private var isManualMode = false
    private var currentLocationId: Int = -1

    // Receiver voor updates vanuit de achtergrond (als app dicht is)
    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Alleen updaten als we NIET in manual mode zitten
            if (intent != null && !isManualMode) {
                // We laten de Live Tracking (in onResume) leidend zijn als het scherm aan staat.
                // Deze receiver is vooral backup.
                val mode = intent.getStringExtra("MODE")
                val locNaam = intent.getStringExtra("LOC_NAAM") ?: "Onbekend"
                val id = intent.getIntExtra("LOC_ID", -1)

                if (mode == "SILENT") {
                    currentLocationId = id
                    updateDashboard(true, locNaam, null) // Categorie laden we later wel via live tracking
                } else {
                    currentLocationId = -1
                    updateDashboard(false, getString(R.string.loc_none), null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Scherm aanhouden zolang de app open is (optioneel, handig voor testen)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("wizard_completed", false)) {
            startActivity(Intent(this, WizardActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Views Koppelen
        rootLayout = findViewById(R.id.rootLayout)
        lblMode = findViewById(R.id.lblMode)
        lblLocation = findViewById(R.id.lblLocation)
        txtStatusIcon = findViewById(R.id.txtStatusIcon)
        progressBar = findViewById(R.id.progressBar)
        btnOverride = findViewById(R.id.btnOverride)
        btnSettings = findViewById(R.id.btnSettings)
        btnIgnoreLocation = findViewById(R.id.btnIgnoreLocation)

        geofencingClient = LocationServices.getGeofencingClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geofenceHelper = GeofenceHelper(this)

        // Initialiseer de Live Callback (nog niet starten)
        createLocationCallback()

        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnOverride.setOnClickListener { toggleManualMode() }

        txtStatusIcon.setOnClickListener {
            if (isManualMode) toggleSilentStateManual()
            else Toast.makeText(this, getString(R.string.loc_manual), Toast.LENGTH_SHORT).show()
        }

        btnIgnoreLocation.setOnClickListener { ignoreCurrentLocation() }

        // Service Starten
        val serviceIntent = Intent(this, SilentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        scheduleBackgroundWork()
        performAutomaticUpdate()

        LocalBroadcastManager.getInstance(this).registerReceiver(uiUpdateReceiver, IntentFilter("UPDATE_UI_EVENT"))
        checkPermissions()
    }

    // --- LEVENSVERLOOP APP (Cruciaal voor Batterij vs Snelheid) ---

    override fun onResume() {
        super.onResume()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        isManualMode = prefs.getBoolean("manual_override", false)
        checkRealPhoneStatus()

        // APP IS OPEN: Start "Aggressive" Live Tracking
        if (!isManualMode) {
            startLiveTracking()
        }
    }

    override fun onPause() {
        super.onPause()
        // APP GAAT DICHT/ACHTERGROND: Stop Live Tracking direct (Batterij sparen)
        // De Geofences en Foreground Service nemen het nu over.
        stopLiveTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(uiUpdateReceiver) } catch (e: Exception) { }
        stopLiveTracking()
    }

    // --- LIVE TRACKING LOGICA ---

    private fun createLocationCallback() {
        liveLocationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // Elke keer als de GPS beweegt, rekenen we opnieuw
                    processLocation(location)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLiveTracking() {
        // High Accuracy, elke 3 seconden checken
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000)
            .setWaitForAccurateLocation(false)
            .build()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, liveLocationCallback, Looper.getMainLooper())
        }
    }

    private fun stopLiveTracking() {
        if (::liveLocationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(liveLocationCallback)
        }
    }

    private fun processLocation(location: Location) {
        // Voorkom dubbele berekeningen als de vorige nog bezig is
        if (updateJob?.isActive == true) return

        updateJob = lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

            // Instellingen ophalen
            val activeCats = prefs.getStringSet("active_categories", setOf("church"))?.toList() ?: emptyList()
            val radiusStr = prefs.getString("geofence_radius", "80") ?: "80"
            val radius = radiusStr.toFloatOrNull() ?: 80f
            val ignoredIds = db.ignoredLocationDao().getAllIgnoredIds()

            // Haal locaties in de buurt (kleine box om huidige GPS)
            // We pakken iets meer marge (0.01 graad is ong 1km)
            val nearby = db.locationDao().getNearby(
                location.latitude - 0.005, location.latitude + 0.005,
                location.longitude - 0.005, location.longitude + 0.005,
                activeCats
            ).filter { loc ->
                !ignoredIds.contains(loc.id) &&
                        !loc.name.equals("Locatie", ignoreCase = true) &&
                        !loc.name.equals("Naamloos", ignoreCase = true) &&
                        loc.name.isNotBlank()
            }

            var closestZoneName: String? = null
            var closestZoneId: Int = -1
            var closestZoneCategory: String? = null
            var minDistance = Float.MAX_VALUE

            // Zoek de aller-dichtstbijzijnde binnen de radius
            for (zone in nearby) {
                val results = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, zone.lat, zone.lon, results)
                val distance = results[0]

                // Is dit binnen bereik? (Radius + 10m buffer)
                if (distance <= (radius + 10)) {
                    if (distance < minDistance) {
                        minDistance = distance
                        closestZoneName = zone.name ?: "Zone"
                        closestZoneId = zone.id
                        closestZoneCategory = zone.category
                    }
                }
            }

            // UI Updaten op Main Thread
            withContext(Dispatchers.Main) {
                val mgr = SilentManager(applicationContext)

                if (closestZoneName != null) {
                    // We zitten in een zone
                    if (!mgr.isSilentActive() || currentLocationId != closestZoneId) {
                        mgr.setSilentMode()
                        currentLocationId = closestZoneId
                        updateDashboard(true, closestZoneName, closestZoneCategory)
                    }
                } else {
                    // We zijn buiten bereik
                    // Alleen uitzetten als we NIET handmatig bezig zijn
                    if (!isManualMode) {
                        // Checken of we 'net' uit een zone komen
                        if (currentLocationId != -1 || mgr.isSilentActive()) {
                            mgr.setNormalMode()
                            currentLocationId = -1
                            updateDashboard(false, getString(R.string.loc_none), null)
                        }
                    }
                }
            }
        }
    }

    // --- OVERIGE FUNCTIES (Ongewijzigd) ---

    private fun ignoreCurrentLocation() {
        if (currentLocationId == -1) return

        AlertDialog.Builder(this)
            .setTitle("Locatie Negeren?")
            .setMessage("Wil je dat 'AutoStil' hier nooit meer stil wordt?")
            .setPositiveButton("Ja, negeer") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.ignoredLocationDao().ignore(IgnoredLocation(currentLocationId))

                    withContext(Dispatchers.Main) {
                        val silentManager = SilentManager(applicationContext)
                        silentManager.setNormalMode()
                        Toast.makeText(applicationContext, "Locatie genegeerd ✅", Toast.LENGTH_SHORT).show()
                        currentLocationId = -1
                        updateDashboard(false, getString(R.string.loc_none), null)
                        loadGeofences() // Refresh background monitors
                    }
                }
            }
            .setNegativeButton("Annuleer", null)
            .show()
    }

    private fun toggleManualMode() {
        isManualMode = !isManualMode
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("manual_override", isManualMode).apply()

        val silentManager = SilentManager(this)

        if (isManualMode) {
            stopLiveTracking() // Geen GPS nodig bij handmatig
            if (silentManager.isSilentActive()) {
                silentManager.setNormalMode()
            } else {
                silentManager.setSilentMode()
            }
            checkRealPhoneStatus()
        } else {
            startLiveTracking() // Direct weer scannen
            Toast.makeText(this, getString(R.string.loading), Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSilentStateManual() {
        val silentManager = SilentManager(this)
        if (silentManager.isSilentActive()) silentManager.setNormalMode() else silentManager.setSilentMode()
        checkRealPhoneStatus()
    }

    private fun checkRealPhoneStatus() {
        val silentManager = SilentManager(this)
        val label = if(isManualMode) getString(R.string.loc_manual) else getString(R.string.loc_searching)
        updateDashboard(silentManager.isSilentActive(), label, null)
    }

    private fun updateDashboard(isSilent: Boolean, locationName: String, category: String?) {
        val colorNormalBg = ContextCompat.getColor(this, R.color.status_normal_bg)
        val colorSilentBg = ContextCompat.getColor(this, R.color.status_silent_bg)
        val colorManualBg = ContextCompat.getColor(this, R.color.status_manual_bg)

        val colorNormalText = ContextCompat.getColor(this, R.color.status_normal_text)
        val colorSilentText = ContextCompat.getColor(this, R.color.status_silent_text)
        val colorManualText = ContextCompat.getColor(this, R.color.status_manual_text)
        val colorButtonDark = ContextCompat.getColor(this, R.color.button_dark_bg)

        if (isManualMode) {
            btnIgnoreLocation.visibility = View.GONE
            btnOverride.text = getString(R.string.btn_manual_stop)
            btnOverride.setBackgroundColor(colorManualText)
            lblLocation.text = getString(R.string.loc_manual)
            lblLocation.setTextColor(colorManualText)
            rootLayout.setBackgroundColor(colorManualBg)

            if (isSilent) {
                lblMode.text = getString(R.string.status_forced_silent)
                lblMode.setTextColor(colorManualText)
                txtStatusIcon.text = "🔕"
            } else {
                lblMode.text = getString(R.string.status_forced_sound)
                lblMode.setTextColor(colorManualText)
                txtStatusIcon.text = "🔊"
            }
        } else {
            btnOverride.text = getString(R.string.btn_manual_start)
            btnOverride.setBackgroundColor(colorButtonDark)

            if (isSilent) {
                if (currentLocationId != -1) {
                    btnIgnoreLocation.visibility = View.VISIBLE
                } else {
                    btnIgnoreLocation.visibility = View.GONE
                }

                rootLayout.setBackgroundColor(colorSilentBg)
                lblMode.text = getString(R.string.status_sound_off)
                lblMode.setTextColor(colorSilentText)

                if (locationName == getString(R.string.loc_searching) || locationName == getString(R.string.loc_manual)) {
                    lblLocation.text = locationName
                } else {
                    lblLocation.text = getString(R.string.loc_at, locationName)
                }
                lblLocation.setTextColor(colorSilentText)

                val emoji = when(category) {
                    "church" -> "⛪"
                    "theater" -> "🎭"
                    "library" -> "📚"
                    "cinema" -> "🍿"
                    else -> "🔕"
                }
                txtStatusIcon.text = emoji

            } else {
                btnIgnoreLocation.visibility = View.GONE
                currentLocationId = -1

                rootLayout.setBackgroundColor(colorNormalBg)
                lblMode.text = getString(R.string.status_sound_on)
                lblMode.setTextColor(colorNormalText)
                lblLocation.text = getString(R.string.loc_none)
                lblLocation.setTextColor(colorNormalText)

                txtStatusIcon.text = "🔊"
            }
        }
    }

    private fun scheduleBackgroundWork() {
        val workRequest = PeriodicWorkRequestBuilder<BackgroundWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("SilentModeWorker", ExistingPeriodicWorkPolicy.KEEP, workRequest)
    }

    private fun performAutomaticUpdate() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val manager = UpdateManager(applicationContext)
            manager.downloadUpdate()
            withContext(Dispatchers.Main) {
                loadGeofences()
                progressBar.visibility = View.GONE
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadGeofences() {
        // We gebruiken de live tracking al, maar voor de achtergrond
        // is het nog steeds nuttig om Geofences te registreren.
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    addGeofencesAround(location.latitude, location.longitude)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun addGeofencesAround(lat: Double, lon: Double) {
        val dbFile = getDatabasePath("silent_locations.db")
        if (!dbFile.exists()) return

        val db = AppDatabase.getDatabase(applicationContext)
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val activeCats = prefs.getStringSet("active_categories", setOf("church", "theater"))?.toList() ?: emptyList()
        val ignoredIds = db.ignoredLocationDao().getAllIgnoredIds()

        val targets = db.locationDao().getNearby(
            lat - 0.1, lat + 0.1,
            lon - 0.1, lon + 0.1,
            activeCats
        ).filter { loc ->
            !ignoredIds.contains(loc.id) &&
                    !loc.name.equals("Locatie", ignoreCase = true) &&
                    !loc.name.equals("Naamloos", ignoreCase = true) &&
                    loc.name.isNotBlank()
        }.take(99)

        if (targets.isEmpty()) return

        val geofenceList = targets.map { loc ->
            val idMetNaam = "${loc.id}|${loc.name ?: "Locatie"}"
            geofenceHelper.getGeofence(idMetNaam, loc.lat, loc.lon)
        }.toMutableList()

        geofenceList.add(geofenceHelper.getUpdateGeofence(lat, lon))

        val request = geofenceHelper.getGeofencingRequest(geofenceList)
        val pendingIntent = geofenceHelper.geofencePendingIntent

        geofencingClient.removeGeofences(pendingIntent).addOnCompleteListener {
            geofencingClient.addGeofences(request, pendingIntent)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkPermissions() }
    private val requestBackgroundLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) loadGeofences() }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    loadGeofences()
                } else {
                    requestBackgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            } else loadGeofences()
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}