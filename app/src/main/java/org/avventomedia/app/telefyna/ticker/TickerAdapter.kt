package org.avventomedia.app.telefyna.ticker

import android.annotation.SuppressLint
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import org.avventomedia.app.telefyna.R

class TickerAdapter(
    private val items: List<TickerItem>,
    private val displacement: Int, // Speed or displacement for scrolling
) : RecyclerView.Adapter<TickerAdapter.TickerViewHolder>() {

    inner class TickerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val whiteLabel: TextView = itemView.findViewById(R.id.whiteSection)
        val tickerText: TextView = itemView.findViewById(R.id.tickerText)
        val timeClock: TextView = itemView.findViewById(R.id.timeSection)
    }

    @SuppressLint("ResourceAsColor")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TickerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.ticker_scroll, parent, false)
        return TickerViewHolder(view).apply {
            itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onBindViewHolder(holder: TickerViewHolder, position: Int) {
        val tickerItem = items[position]
        val textView = holder.tickerText
        val timeClock = holder.timeClock
        val whiteSection = holder.whiteLabel

        val isTimeEnabled = tickerItem.time == true
        val hasText = !tickerItem.text.isNullOrBlank()

        textView.visibility = if (isTimeEnabled && hasText) View.VISIBLE else View.GONE
        timeClock.visibility = if (isTimeEnabled) View.VISIBLE else View.GONE
        whiteSection.visibility = if (isTimeEnabled && hasText) View.VISIBLE else View.GONE

        // Modify the ticker text here by adding space on Start scroll before setting it
        val modifiedText = "${StringUtils.spaces120}${tickerItem.text}" // Add space before the text

        holder.tickerText.text = modifiedText
        // Apply displacement for scrolling effect (if needed)
        holder.tickerText.translationX = (-displacement * position).toFloat()
        // Start the marquee animation
        holder.tickerText.isSelected = true // This triggers the marquee
    }

    override fun getItemCount(): Int = items.size
}

data class TickerItem(
    val text: String? = null,
    var time: Boolean? = true
)

object StringUtils {
    val spaces120 = "\u00A0".repeat(120) // Cached globally
}