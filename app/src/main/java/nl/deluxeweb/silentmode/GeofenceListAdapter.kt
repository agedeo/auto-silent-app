package nl.deluxeweb.silentmode

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// Hulpklasse voor de lijst
data class LocationItem(
    val location: SilentLocation,
    val distanceText: String
)

class GeofenceListAdapter(private var locations: List<LocationItem>) :
    RecyclerView.Adapter<GeofenceListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.txtName)
        val cat: TextView = view.findViewById(R.id.txtCat)
        val dist: TextView = view.findViewById(R.id.txtDist)
        val icon: TextView = view.findViewById(R.id.txtIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_geofence_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = locations[position]
        val loc = item.location

        holder.name.text = loc.name
        holder.cat.text = loc.category
        holder.dist.text = item.distanceText

        val emoji = when(loc.category) {
            "church" -> "⛪"
            "theater" -> "🎭"
            "library" -> "📚"
            "cinema" -> "🍿"
            "museum" -> "🖼️"
            "community" -> "🤝"
            "cemetery" -> "🕯️"
            "hospital" -> "🏥"
            "government" -> "⚖️"
            else -> "📍"
        }
        holder.icon.text = emoji
    }

    override fun getItemCount() = locations.size

    fun updateList(newList: List<LocationItem>) {
        locations = newList
        notifyDataSetChanged()
    }
}