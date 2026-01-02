package nl.deluxeweb.silentmode

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import nl.deluxeweb.silentmode.data.RemoteMetadata
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class UpdateResult {
    SUCCESS,
    FAILED,
    UP_TO_DATE
}

class UpdateManager(private val context: Context) {

    private val GITHUB_USER = "agedeo"
    private val REPO_NAME = "auto-silent"
    private val baseUrl = "https://$GITHUB_USER.github.io/$REPO_NAME"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun downloadUpdate(force: Boolean = false): UpdateResult {
        Log.d("UpdateManager", "Check op updates...")

        try {
            val dbFile = context.getDatabasePath("silent_locations.db")

            // STAP 1: Haal de metadata op
            val jsonUrl = "$baseUrl/version.json?t=${System.currentTimeMillis()}"
            val jsonRequest = Request.Builder()
                .url(jsonUrl)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()

            val jsonResponse = client.newCall(jsonRequest).execute()
            if (!jsonResponse.isSuccessful) return UpdateResult.FAILED

            val jsonString = jsonResponse.body?.string()
            val metadata = gson.fromJson(jsonString, RemoteMetadata::class.java)

            // STAP 2: Vergelijk Timestamps OF check of bestand mist
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val localTimestamp = prefs.getLong("db_version_timestamp", 0L)
            val serverTimestamp = metadata.timestamp

            // CRUCIALE AANPASSING:
            // Download als:
            // 1. We het forceren (force = true)
            // 2. Het bestand niet bestaat (!dbFile.exists())
            // 3. De server nieuwer is
            val shouldDownload = force || !dbFile.exists() || serverTimestamp > localTimestamp

            if (!shouldDownload) {
                Log.d("UpdateManager", "Alles is up-to-date en bestand bestaat.")
                return UpdateResult.UP_TO_DATE
            }

            Log.i("UpdateManager", "Update nodig (Force: $force, Missing: ${!dbFile.exists()}, NewVer: ${serverTimestamp > localTimestamp})")

            // STAP 3: Downloaden
            val region = metadata.regions.firstOrNull() ?: return UpdateResult.FAILED
            val dbUrl = "$baseUrl/${region.file}?t=${System.currentTimeMillis()}"

            val dbRequest = Request.Builder().url(dbUrl).build()
            val dbResponse = client.newCall(dbRequest).execute()
            if (!dbResponse.isSuccessful) return UpdateResult.FAILED

            dbFile.parentFile?.mkdirs()

            val fos = FileOutputStream(dbFile)
            fos.write(dbResponse.body!!.bytes())
            fos.close()

            // STAP 4: Opslaan
            prefs.edit().putLong("db_version_timestamp", serverTimestamp).apply()

            Log.i("UpdateManager", "Succes! Database hersteld/geupdate.")
            return UpdateResult.SUCCESS

        } catch (e: Exception) {
            Log.e("UpdateManager", "Fout: ${e.message}")
            e.printStackTrace()
            return UpdateResult.FAILED
        }
    }
}