package nl.deluxeweb.silentmode

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import nl.deluxeweb.silentmode.data.SilentLocation

data class LocationWithDistance(
    val location: SilentLocation,
    val distanceMeters: Float,
    val distanceText: String
)

class GeofenceListAdapter(private var items: List<LocationWithDistance>) :
    RecyclerView.Adapter<GeofenceListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtName)
        val txtDistance: TextView = view.findViewById(R.id.txtDistance)
        val txtRowIcon: TextView = view.findViewById(R.id.txtRowIcon) // <--- Aangepast naar TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_geofence_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.txtName.text = item.location.name ?: "Naamloos"
        holder.txtDistance.text = item.distanceText

        // EMOJI LOGICA
        val emoji = when (item.location.category) {
            "church" -> "⛪"
            "theater" -> "🎭"
            "library" -> "📚"
            "cinema" -> "🍿"
            else -> "📍"
        }
        holder.txtRowIcon.text = emoji
    }

    override fun getItemCount() = items.size

    fun updateList(newItems: List<LocationWithDistance>) {
        items = newItems
        notifyDataSetChanged()
    }
}