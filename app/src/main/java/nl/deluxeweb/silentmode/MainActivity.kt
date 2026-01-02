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
import android.os.CombinedVibration
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
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
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.deluxeweb.silentmode.data.AppDatabase
import nl.deluxeweb.silentmode.data.IgnoredLocation
import nl.deluxeweb.silentmode.data.LocalDatabase // <--- ONZE NIEUWE DATABASE
import nl.deluxeweb.silentmode.data.Promotion     // <--- VOOR TESTEN
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var lblMode: TextView
    private lateinit var lblLocation: TextView
    private lateinit var txtStatusIcon: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var toggleGroupMode: MaterialButtonToggleGroup
    private lateinit var btnOpenSettings: MaterialButton
    private lateinit var btnIgnoreLocation: TextView

    private lateinit var geofencingClient: GeofencingClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geofenceHelper: GeofenceHelper

    private lateinit var liveLocationCallback: LocationCallback
    private var updateJob: Job? = null

    private var currentMode = "AUTO"
    private var currentLocationId: Int = -1

    // Ontvangt updates van de GeofenceBroadcastReceiver (als de app in achtergrond draait)
    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && currentMode == "AUTO") {
                val mode = intent.getStringExtra("MODE")
                val locNaam = intent.getStringExtra("LOC_NAAM") ?: "Onbekend"
                val id = intent.getIntExtra("LOC_ID", -1)

                if (mode == "SILENT") {
                    currentLocationId = id
                    updateDashboard(true, locNaam, null)
                } else {
                    currentLocationId = -1
                    updateDashboard(false, getString(R.string.loc_none), null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("wizard_completed", false)) {
            startActivity(Intent(this, WizardActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Views koppelen
        rootLayout = findViewById(R.id.rootLayout)
        lblMode = findViewById(R.id.lblMode)
        lblLocation = findViewById(R.id.lblLocation)
        txtStatusIcon = findViewById(R.id.txtStatusIcon)
        progressBar = findViewById(R.id.progressBar)
        toggleGroupMode = findViewById(R.id.toggleGroupMode)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnIgnoreLocation = findViewById(R.id.btnIgnoreLocation)

        // Google Clients
        geofencingClient = LocationServices.getGeofencingClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geofenceHelper = GeofenceHelper(this)

        createLocationCallback()

        // Listeners
        btnOpenSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnIgnoreLocation.setOnClickListener { ignoreCurrentLocation() }

        toggleGroupMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnModeAuto -> setAppMode("AUTO")
                    R.id.btnModeSound -> setAppMode("SOUND")
                    R.id.btnModeSilent -> setAppMode("SILENT")
                }
            }
        }

        txtStatusIcon.setOnClickListener {
            Toast.makeText(this, "Gebruik de knoppen hieronder om te wisselen.", Toast.LENGTH_SHORT).show()
        }

        scheduleBackgroundWork()
        performAutomaticUpdate()

        // UI Receiver registreren
        ContextCompat.registerReceiver(
            this, uiUpdateReceiver, IntentFilter("UPDATE_UI_EVENT"), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        checkPermissions()

        // --- TIJDELIJKE TEST VOOR PROMOTIE ---
        // Zet dit AAN als je wilt testen. Vervang 12345 door een ID dat je in de buurt hebt.
        /*
        lifecycleScope.launch(Dispatchers.IO) {
            val db = LocalDatabase.getDatabase(applicationContext)
            db.promotionDao().addPromotion(
                Promotion(
                    locationId = 12345,
                    text = "🍿 Gratis Popcorn!",
                    url = "https://www.pathe.nl"
                )
            )
        }
        */
    }

    override fun onResume() {
        super.onResume()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedMode = prefs.getString("override_mode", "AUTO") ?: "AUTO"

        when (savedMode) {
            "AUTO" -> toggleGroupMode.check(R.id.btnModeAuto)
            "SOUND" -> toggleGroupMode.check(R.id.btnModeSound)
            "SILENT" -> toggleGroupMode.check(R.id.btnModeSilent)
        }
        setAppMode(savedMode)
        checkAndStartService()
    }

    private fun setAppMode(mode: String) {
        currentMode = mode
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putString("override_mode", mode).apply()
        prefs.edit().putBoolean("manual_override", mode != "AUTO").apply()

        val mgr = SilentManager(this)

        when (mode) {
            "AUTO" -> {
                startLiveTracking()
                if (currentLocationId == -1) {
                    updateDashboard(false, getString(R.string.loc_searching), null)
                    SilentService.updateNotification(this, getString(R.string.notification_standby), R.drawable.ic_sound_on)
                }
            }
            "SOUND" -> {
                stopLiveTracking()
                mgr.setNormalMode()
                updateDashboard(false, "Handmatig: Geluid", null)
                SilentService.updateNotification(this, "🔊 Geluid geforceerd AAN", R.drawable.ic_sound_on)
            }
            "SILENT" -> {
                stopLiveTracking()
                mgr.setSilentMode()
                updateDashboard(true, "Handmatig: Stil", null)
                SilentService.updateNotification(this, "🔕 Geluid geforceerd UIT", R.drawable.ic_sound_off)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopLiveTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(uiUpdateReceiver) } catch (e: Exception) { }
        stopLiveTracking()
    }

    private fun checkAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val serviceIntent = Intent(this, SilentService::class.java)
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent) } catch (e: Exception) { }
    }

    private fun createLocationCallback() {
        liveLocationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) processLocation(location)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLiveTracking() {
        if (currentMode != "AUTO") return
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

    // --- HIER ZIT DE NIEUWE LOGICA ---
    private fun processLocation(location: Location) {
        if (currentMode != "AUTO") return
        if (updateJob?.isActive == true) return

        updateJob = lifecycleScope.launch(Dispatchers.IO) {
            val appDb = AppDatabase.getDatabase(applicationContext)   // DB 1: OSM Data (Wekelijks nieuw)
            val localDb = LocalDatabase.getDatabase(applicationContext) // DB 2: User Data & Promos (Permanent)

            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val activeCats = prefs.getStringSet("active_categories", setOf("church", "theater"))?.toList() ?: emptyList()
            val radiusStr = prefs.getString("geofence_radius", "100") ?: "100"
            val radius = radiusStr.toFloatOrNull() ?: 100f

            // Haal genegeerde IDs uit LOKALE database
            val ignoredIds = localDb.ignoredLocationDao().getAllIgnoredIds()

            // Haal OSM locaties uit APP database
            val nearby = appDb.locationDao().getNearby(
                location.latitude - 0.005, location.latitude + 0.005,
                location.longitude - 0.005, location.longitude + 0.005,
                activeCats
            ).filter { loc ->
                !ignoredIds.contains(loc.id) &&
                        !loc.name.isNullOrBlank() &&
                        !loc.name.equals("Locatie", true)
            }

            var closestZoneName: String? = null
            var closestZoneId: Int = -1
            var closestZoneCategory: String? = null
            var minDistance = Float.MAX_VALUE

            for (zone in nearby) {
                val results = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, zone.lat, zone.lon, results)
                val distance = results[0]

                if (distance <= (radius + 10)) {
                    if (distance < minDistance) {
                        minDistance = distance
                        closestZoneName = zone.name ?: "Zone"
                        closestZoneId = zone.id
                        closestZoneCategory = zone.category
                    }
                }
            }

            // Nu checken we op promo's (nog steeds in IO thread)
            var promoText: String? = null
            var promoUrl: String? = null

            if (closestZoneId != -1) {
                val promo = localDb.promotionDao().getPromotion(closestZoneId)
                if (promo != null) {
                    promoText = promo.text
                    promoUrl = promo.url
                }
            }

            withContext(Dispatchers.Main) {
                if (currentMode != "AUTO") return@withContext

                val mgr = SilentManager(applicationContext)

                if (closestZoneName != null) {
                    // We zitten in een zone!
                    if (!mgr.isSilentActive() || currentLocationId != closestZoneId) {
                        vibrateModern(applicationContext, longArrayOf(0, 200, 100, 200))
                        mgr.setSilentMode()
                        prefs.edit().putBoolean("state_active_by_app", true).apply()

                        currentLocationId = closestZoneId
                        updateDashboard(true, closestZoneName, closestZoneCategory)

                        // Toon notificatie MET eventuele promo
                        SilentService.updateNotification(
                            applicationContext,
                            "🔴 Stil bij: $closestZoneName",
                            R.drawable.ic_sound_off,
                            promoText,
                            promoUrl
                        )
                    }
                } else {
                    // We zijn buiten bereik
                    if (currentLocationId != -1 || mgr.isSilentActive()) {
                        mgr.setNormalMode()
                        prefs.edit().putBoolean("state_active_by_app", false).apply()
                        vibrateModern(applicationContext, longArrayOf(0, 500))

                        currentLocationId = -1
                        updateDashboard(false, getString(R.string.loc_none), null)

                        // Reset notificatie (geen promo)
                        SilentService.updateNotification(
                            applicationContext,
                            getString(R.string.notification_standby),
                            R.drawable.ic_sound_on,
                            null, null
                        )
                    }
                }
            }
        }
    }

    private fun ignoreCurrentLocation() {
        if (currentLocationId == -1) return
        AlertDialog.Builder(this)
            .setTitle("Locatie Negeren?")
            .setMessage("Wil je dat 'AutoStil' hier nooit meer stil wordt?")
            .setPositiveButton("Ja, negeer") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    // OOK HIER DE LOKALE DATABASE GEBRUIKEN
                    val localDb = LocalDatabase.getDatabase(applicationContext)
                    localDb.ignoredLocationDao().ignore(IgnoredLocation(currentLocationId))

                    withContext(Dispatchers.Main) {
                        val silentManager = SilentManager(applicationContext)
                        silentManager.setNormalMode()
                        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit().putBoolean("state_active_by_app", false).apply()
                        Toast.makeText(applicationContext, "Locatie genegeerd ✅", Toast.LENGTH_SHORT).show()
                        currentLocationId = -1
                        updateDashboard(false, getString(R.string.loc_none), null)
                        SilentService.updateNotification(applicationContext, getString(R.string.notification_standby), R.drawable.ic_sound_on)
                        loadGeofences()
                    }
                }
            }
            .setNegativeButton("Annuleer", null).show()
    }

    private fun vibrateModern(context: Context, pattern: LongArray) {
        try {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val effect = VibrationEffect.createWaveform(pattern, -1)
            val combinedEffect = CombinedVibration.createParallel(effect)
            val attributes = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()
            vibratorManager.vibrate(combinedEffect, attributes)
        } catch (e: Exception) { }
    }

    private fun updateDashboard(isSilent: Boolean, locationName: String, category: String?) {
        val colorNormalBg = ContextCompat.getColor(this, R.color.status_normal_bg)
        val colorSilentBg = ContextCompat.getColor(this, R.color.status_silent_bg)
        val colorManualBg = ContextCompat.getColor(this, R.color.status_manual_bg)
        val colorNormalText = ContextCompat.getColor(this, R.color.status_normal_text)
        val colorSilentText = ContextCompat.getColor(this, R.color.status_silent_text)
        val colorManualText = ContextCompat.getColor(this, R.color.status_manual_text)

        if (currentMode != "AUTO") {
            btnIgnoreLocation.visibility = View.GONE
            rootLayout.setBackgroundColor(colorManualBg)
            lblLocation.setTextColor(colorManualText)

            if (currentMode == "SILENT") {
                lblMode.text = getString(R.string.status_forced_silent)
                lblMode.setTextColor(colorManualText)
                txtStatusIcon.text = "🔕"
                lblLocation.text = "Handmatig: Stil"
            } else {
                lblMode.text = getString(R.string.status_forced_sound)
                lblMode.setTextColor(colorManualText)
                txtStatusIcon.text = "🔊"
                lblLocation.text = "Handmatig: Geluid"
            }
        } else {
            if (isSilent) {
                btnIgnoreLocation.visibility = View.VISIBLE
                rootLayout.setBackgroundColor(colorSilentBg)
                lblMode.text = getString(R.string.status_sound_off)
                lblMode.setTextColor(colorSilentText)
                lblLocation.text = if (locationName == getString(R.string.loc_searching)) locationName else getString(R.string.loc_at, locationName)
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
        val appDb = AppDatabase.getDatabase(applicationContext)
        val localDb = LocalDatabase.getDatabase(applicationContext)

        // Check of database leeg is
        if (appDb.locationDao().getLocationCount() == 0) {
            val manager = UpdateManager(applicationContext)
            if (manager.downloadUpdate(force = true) != UpdateResult.SUCCESS) return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val activeCats = prefs.getStringSet("active_categories", setOf("church", "theater"))?.toList() ?: emptyList()

        // Gebruik LOKALE database voor genegeerde IDs
        val ignoredIds = localDb.ignoredLocationDao().getAllIgnoredIds()

        val targets = appDb.locationDao().getNearby(lat - 0.1, lat + 0.1, lon - 0.1, lon + 0.1, activeCats)
            .filter { !ignoredIds.contains(it.id) && !it.name.isNullOrBlank() }
            .take(99)

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

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkAndStartService(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) checkBackgroundLocation() else loadGeofences() }
    private val requestBackgroundLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) loadGeofences() }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissionsToRequest.isNotEmpty()) requestPermissionLauncher.launch(permissionsToRequest.toTypedArray()) else { checkAndStartService(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) checkBackgroundLocation() else loadGeofences() }
    }

    private fun checkBackgroundLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) loadGeofences() else requestBackgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
}