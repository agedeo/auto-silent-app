package nl.deluxeweb.silentmode

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class WizardActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var btnNext: Button
    private lateinit var lblStepTitle: TextView
    private var currentStep = 0

    private lateinit var imgStateLoc: ImageView
    private lateinit var btnGrantLocation: Button
    private lateinit var imgStateDnd: ImageView
    private lateinit var btnGrantDND: Button
    private lateinit var imgStateBat: ImageView
    private lateinit var btnIgnoreBattery: Button

    private lateinit var chkChurch: CheckBox
    private lateinit var chkTheater: CheckBox
    private lateinit var chkLibrary: CheckBox
    private lateinit var chkCinema: CheckBox
    private lateinit var spinnerDelay: Spinner
    private lateinit var spinnerRadius: Spinner

    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkCurrentStep() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        viewFlipper = findViewById(R.id.viewFlipper)
        btnNext = findViewById(R.id.btnNext)
        lblStepTitle = findViewById(R.id.lblStepTitle)

        imgStateLoc = findViewById(R.id.imgStateLoc)
        btnGrantLocation = findViewById(R.id.btnGrantLocation)
        btnGrantLocation.setOnClickListener { askLocationSmart() }

        imgStateDnd = findViewById(R.id.imgStateDnd)
        btnGrantDND = findViewById(R.id.btnGrantDND)
        btnGrantDND.setOnClickListener { askDND() }

        imgStateBat = findViewById(R.id.imgStateBat)
        btnIgnoreBattery = findViewById(R.id.btnIgnoreBattery)
        btnIgnoreBattery.setOnClickListener { askBattery() }

        chkChurch = findViewById(R.id.chkChurch)
        chkTheater = findViewById(R.id.chkTheater)
        chkLibrary = findViewById(R.id.chkLibrary)
        chkCinema = findViewById(R.id.chkCinema)
        spinnerDelay = findViewById(R.id.spinnerDelay)
        spinnerRadius = findViewById(R.id.spinnerRadius)

        spinnerRadius.setSelection(1)

        btnNext.setOnClickListener { if (currentStep == 4) finishWizard() else nextStep() }
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        checkCurrentStep()
    }

    private fun updateUI() {
        viewFlipper.displayedChild = currentStep
        when(currentStep) {
            0 -> {
                lblStepTitle.text = "Welkom"
                btnNext.text = "Starten"
                btnNext.isEnabled = true
            }
            1 -> {
                lblStepTitle.text = "Stap 1/4"
                btnNext.text = "Volgende"
                checkLocationState()
            }
            2 -> {
                lblStepTitle.text = "Stap 2/4"
                checkDndState()
            }
            3 -> {
                lblStepTitle.text = "Stap 3/4"
                checkBatState()
            }
            4 -> {
                lblStepTitle.text = "Stap 4/4"
                btnNext.text = "Klaar!"
                btnNext.isEnabled = true
                btnNext.setBackgroundColor(Color.parseColor("#4CAF50"))
            }
        }
    }

    private fun nextStep() {
        if (currentStep == 1 && !isLocationComplete()) return
        if (currentStep == 2 && !hasDndPermission()) return
        if (currentStep < 4) {
            currentStep++
            updateUI()
        }
    }

    private fun checkCurrentStep() {
        when(currentStep) {
            1 -> checkLocationState()
            2 -> checkDndState()
            3 -> checkBatState()
        }
    }

    private fun isLocationComplete(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        return fine && bg
    }

    private fun checkLocationState() {
        if (isLocationComplete()) setSuccessState(imgStateLoc, btnGrantLocation) else setFailState(imgStateLoc, btnGrantLocation, "Geef Toegang")
    }

    private fun askLocationSmart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Let op!")
                .setMessage("Kies in het volgende scherm voor:\n\n'Altijd toestaan' (Allow all the time).")
                .setPositiveButton("Snap ik") { _, _ -> locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
                .show()
        }
    }

    private fun hasDndPermission(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) nm.isNotificationPolicyAccessGranted else true
    }

    private fun checkDndState() {
        if (hasDndPermission()) setSuccessState(imgStateDnd, btnGrantDND) else setFailState(imgStateDnd, btnGrantDND, "Open Instellingen")
    }

    private fun askDND() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private fun isBatteryOptimized(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return !pm.isIgnoringBatteryOptimizations(packageName)
        }
        return false
    }

    private fun checkBatState() {
        if (!isBatteryOptimized()) setSuccessState(imgStateBat, btnIgnoreBattery) else setFailState(imgStateBat, btnIgnoreBattery, "Voorkom Slapen")
        btnNext.isEnabled = true
    }

    private fun askBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun setSuccessState(img: ImageView, btn: Button) {
        img.setImageResource(R.drawable.ic_check_circle)
        img.setColorFilter(Color.parseColor("#4CAF50"))
        btn.text = "Gelukt! ✅"
        btn.isEnabled = false
        btn.setBackgroundColor(Color.LTGRAY)
    }

    private fun setFailState(img: ImageView, btn: Button, btnText: String) {
        img.setImageResource(android.R.drawable.ic_delete)
        img.setColorFilter(Color.parseColor("#E53935"))
        btn.text = btnText
        btn.isEnabled = true
        btn.setBackgroundColor(Color.parseColor("#2196F3"))
    }

    private fun finishWizard() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val editor = prefs.edit()

        val activeCats = mutableSetOf<String>()
        if (chkChurch.isChecked) activeCats.add("church")
        if (chkTheater.isChecked) activeCats.add("theater")
        if (chkLibrary.isChecked) activeCats.add("library")
        if (chkCinema.isChecked) activeCats.add("cinema")
        editor.putStringSet("active_categories", activeCats)

        val delayValues = resources.getStringArray(R.array.delay_values)
        editor.putString("loitering_delay", delayValues[spinnerDelay.selectedItemPosition])

        val radiusValues = resources.getStringArray(R.array.radius_values)
        editor.putString("geofence_radius", radiusValues[spinnerRadius.selectedItemPosition])

        editor.putBoolean("wizard_completed", true)
        editor.apply()

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}