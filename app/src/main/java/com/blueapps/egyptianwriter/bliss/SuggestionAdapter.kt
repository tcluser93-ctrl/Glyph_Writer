package com.blueapps.egyptianwriter.bliss

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blueapps.egyptianwriter.R
import com.google.android.material.chip.Chip

/**
 * SuggestionAdapter — RecyclerView adapter per chip suggerimenti predittivi.
 *
 * Ogni item mostra una parola suggerita come Material Chip.
 * Il click chiama [onSuggestionClick] con la parola suggerita.
 *
 * Accessibilità:
 *  - contentDescription da R.string.bliss_a11y_suggestion
 *  - minWidth e minHeight 48dp garantiti dal chip layout
 */
class SuggestionAdapter(
    private val onSuggestionClick: (String) -> Unit
) : ListAdapter<String, SuggestionAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val chip = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_suggestion, parent, false) as Chip
        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val word = getItem(position)
        holder.chip.text = word
        holder.chip.contentDescription = holder.chip.context.getString(
            R.string.bliss_a11y_suggestion, word
        )
        holder.chip.setOnClickListener { onSuggestionClick(word) }
    }

    class ViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}
