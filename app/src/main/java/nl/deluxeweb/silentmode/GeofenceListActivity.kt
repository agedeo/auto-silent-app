package nl.deluxeweb.silentmode

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import nl.deluxeweb.silentmode.data.AppDatabase

class GeofenceListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var lblLoading: TextView
    private lateinit var adapter: GeofenceListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_geofence_list)

        recyclerView = findViewById(R.id.recyclerView)
        lblLoading = findViewById(R.id.lblLoading)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = GeofenceListAdapter(emptyList())
        recyclerView.adapter = adapter

        findViewById<View>(android.R.id.content).setOnClickListener { finish() }

        calculateAndLoad()
    }

    @SuppressLint("MissingPermission")
    private fun calculateAndLoad() {
        lifecycleScope.launch(Dispatchers.IO) {
            var myLoc: Location? = null

            // 1. Probeer GPS te pakken
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val client = LocationServices.getFusedLocationProviderClient(applicationContext)
                    myLoc = client.lastLocation.await()
                    if (myLoc == null) {
                        myLoc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            loadData(myLoc)
        }
    }

    private suspend fun loadData(myLoc: Location?) {
        val db = AppDatabase.getDatabase(applicationContext)
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        // Check of database wel gevuld is
        val totalCount = db.locationDao().getLocationCount() // Zorg dat deze query bestaat, of gebruik getAll().size

        if (totalCount == 0) {
            withContext(Dispatchers.Main) {
                lblLoading.text = "Database is leeg! \nGa naar Instellingen -> Database Updaten."
                Toast.makeText(applicationContext, "⚠️ Database is leeg. Update nodig.", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Haal categorieën op
        val activeCats = prefs.getStringSet("active_categories", null)
        if (activeCats.isNullOrEmpty()) {
            withContext(Dispatchers.Main) {
                lblLoading.text = "Geen categorieën geselecteerd in instellingen."
                Toast.makeText(applicationContext, "⚠️ Vink categorieën aan in instellingen.", Toast.LENGTH_LONG).show()
            }
            return
        }

        // STAP 1: Ophalen
        val rawList = if (myLoc != null) {
            // 0.1 graad ~ 10km
            db.locationDao().getNearby(
                myLoc.latitude - 0.1, myLoc.latitude + 0.1,
                myLoc.longitude - 0.1, myLoc.longitude + 0.1,
                activeCats.toList()
            )
        } else {
            // Geen GPS? Dan maar alles (binnen de geselecteerde categorieën)
            // Let op: als je database groot is, kan dit traag zijn, maar voor debug prima.
            db.locationDao().getNearby(0.0, 0.0, 0.0, 0.0, activeCats.toList()) // Dit werkt niet goed zonder coordinaten in getNearby
            // Fallback: Haal alles op en filter in memory (alleen als GPS stuk is)
            db.locationDao().getAllLocations().filter { activeCats.contains(it.category) }
        }

        // DEBUG INFO
        if (rawList.isEmpty()) {
            withContext(Dispatchers.Main) {
                val gpsText = if (myLoc == null) "Geen GPS" else "GPS OK"
                lblLoading.text = "0 locaties gevonden in de buurt ($gpsText).\nActieve categorieën: ${activeCats.size}"
            }
            return
        }

        // STAP 2: Filteren (Naamloos weg)
        val processedList = rawList.filter { loc ->
            !loc.name.isNullOrBlank() &&
                    !loc.name.equals("Locatie", ignoreCase = true) &&
                    !loc.name.equals("Naamloos", ignoreCase = true)
        }.map { loc ->
            var distMeters = Float.MAX_VALUE
            var distText = "?"

            if (myLoc != null) {
                val results = FloatArray(1)
                Location.distanceBetween(myLoc.latitude, myLoc.longitude, loc.lat, loc.lon, results)
                distMeters = results[0]

                distText = if (distMeters >= 1000) {
                    String.format("%.1f km", distMeters / 1000)
                } else {
                    "${distMeters.toInt()} m"
                }
            }
            Pair(distMeters, LocationItem(loc, distText))
        }

        // STAP 3: Sorteren
        val sortedList = processedList.sortedBy { it.first }.map { it.second }

        withContext(Dispatchers.Main) {
            if (sortedList.isEmpty()) {
                lblLoading.text = "Wel locaties gevonden, maar allemaal 'Naamloos' of 'Locatie'."
            } else {
                lblLoading.visibility = View.GONE
                adapter.updateList(sortedList)
                // Toast.makeText(applicationContext, "Geladen: ${sortedList.size} plekken", Toast.LENGTH_SHORT).show()
            }
        }
    }
}