package nl.deluxeweb.silentmode

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.deluxeweb.silentmode.data.AppDatabase
import java.util.Collections

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

        // Terugknop
        findViewById<View>(android.R.id.content).setOnClickListener { finish() }

        calculateAndLoad()
    }

    @SuppressLint("MissingPermission")
    private fun calculateAndLoad() {
        // Hebben we locatie permissie?
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            val client = LocationServices.getFusedLocationProviderClient(this)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { myLoc ->
                loadData(myLoc) // Laad data met huidige GPS positie
            }.addOnFailureListener {
                loadData(null) // Laad data zonder sortering als GPS faalt
            }
        } else {
            loadData(null) // Geen permissie, geen sortering
        }
    }

    private fun loadData(myLoc: Location?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val rawList = db.locationDao().getAllLocations()

            // Omzetten naar lijst met afstanden
            val processedList = rawList.map { loc ->
                var distMeters = Float.MAX_VALUE
                var distText = "Afstand onbekend"

                if (myLoc != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(myLoc.latitude, myLoc.longitude, loc.lat, loc.lon, results)
                    distMeters = results[0]

                    distText = if (distMeters >= 1000) {
                        String.format("%.1f km", distMeters / 1000)
                    } else {
                        "${distMeters.toInt()} meter"
                    }
                }
                LocationWithDistance(loc, distMeters, distText)
            }

            // Sorteren: Dichtbij eerst
            val sortedList = processedList.sortedBy { it.distanceMeters }

            withContext(Dispatchers.Main) {
                if (sortedList.isEmpty()) {
                    lblLoading.text = "Geen locaties gevonden in database."
                } else {
                    lblLoading.visibility = View.GONE
                    adapter.updateList(sortedList)
                }
            }
        }
    }
}