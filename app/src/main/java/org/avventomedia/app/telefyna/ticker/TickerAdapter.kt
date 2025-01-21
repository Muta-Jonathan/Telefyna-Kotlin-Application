package org.avventomedia.app.telefyna.ticker

import android.annotation.SuppressLint
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView
import org.avventomedia.app.telefyna.R

class TickerAdapter(
    private val items: List<TickerItem>,
    private val displacement: Int, // Speed or displacement for scrolling
    private val backgroundColor: Int = TickerDefaults.DEFAULT_BACKGROUND_COLOR,
    private val textColor: Int = TickerDefaults.DEFAULT_TEXT_COLOR
) : RecyclerView.Adapter<TickerAdapter.TickerViewHolder>() {

    inner class TickerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tickerText: TextView = itemView.findViewById(R.id.tickerText)
        val tickerImage: ImageView = itemView.findViewById(R.id.tickerImage)
    }

    @SuppressLint("ResourceAsColor")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TickerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.ticker_scroll, parent, false)
        return TickerViewHolder(view).apply {
            itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onBindViewHolder(holder: TickerViewHolder, position: Int) {
        val item = items[position]

        if (item.isImage) {
            holder.tickerImage.visibility = View.VISIBLE
            holder.tickerText.visibility = View.GONE
            item.imageResId?.let { holder.tickerImage.setImageResource(it) }
        } else {
            holder.tickerImage.visibility = View.GONE
            holder.tickerText.visibility = View.VISIBLE
            holder.tickerText.text = item.text
            // Apply displacement for scrolling effect (if needed)
            holder.tickerText.translationX = (-displacement * position).toFloat()
            // Start the marquee animation
            holder.tickerText.isSelected = true // This triggers the marquee
        }
    }

    override fun getItemCount(): Int = items.size
}

data class TickerItem(
    val isImage: Boolean,       // True if this item is an image
    val text: String? = null,   // The text to display (if applicable)
    val imageResId: Int? = null // The resource ID of the image (if applicable)
)

object TickerDefaults {
    const val DEFAULT_BACKGROUND_COLOR = android.R.color.holo_blue_dark // Default background color
    const val DEFAULT_TEXT_COLOR = android.R.color.white
}

