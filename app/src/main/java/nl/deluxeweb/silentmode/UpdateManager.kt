package nl.deluxeweb.silentmode

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import nl.deluxeweb.silentmode.data.RemoteMetadata
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class UpdateManager(private val context: Context) {

    // --- VUL HIER JOUW GEGEVENS IN ---
    private val GITHUB_USER = "agedeo" // Bijv: jan-jansen
    private val REPO_NAME = "auto-silent"     // Bijv: silent-app
    // ---------------------------------

    private val client = OkHttpClient()
    private val gson = Gson()

    // De basis URL naar GitHub Pages
    private val baseUrl = "https://$GITHUB_USER.github.io/$REPO_NAME"

    fun downloadUpdate(): Boolean {
        Log.d("UpdateManager", "Start update check...")

        try {
            // Stap 1: Haal version.json op
            val jsonRequest = Request.Builder().url("$baseUrl/version.json").build()
            val jsonResponse = client.newCall(jsonRequest).execute()

            if (!jsonResponse.isSuccessful) {
                Log.e("UpdateManager", "Fout bij ophalen JSON: ${jsonResponse.code}")
                return false
            }

            val jsonString = jsonResponse.body?.string()
            val metadata = gson.fromJson(jsonString, RemoteMetadata::class.java)

            // We pakken voor nu even simpel de eerste regio (NL)
            val region = metadata.regions.firstOrNull() ?: return false
            Log.d("UpdateManager", "Versie op server gevonden: ${region.file} met ${region.count} locaties")

            // Stap 2: Download het database bestand (.db)
            val dbUrl = "$baseUrl/${region.file}"
            val dbRequest = Request.Builder().url(dbUrl).build()
            val dbResponse = client.newCall(dbRequest).execute()

            if (!dbResponse.isSuccessful) return false

            // Stap 3: Opslaan op de telefoon
            // We slaan het direct op waar Room (de database) het verwacht
            val dbFile = context.getDatabasePath("silent_locations.db")

            // Zorg dat het mapje bestaat
            dbFile.parentFile?.mkdirs()

            // Schrijf de bytes weg naar het bestand
            val fos = FileOutputStream(dbFile)
            fos.write(dbResponse.body!!.bytes())
            fos.close()

            Log.d("UpdateManager", "Succes! Database opgeslagen op: ${dbFile.absolutePath}")
            return true

        } catch (e: Exception) {
            Log.e("UpdateManager", "Fout tijdens update: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}