package com.modul.gymai.antarmuka.latihan

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.modul.gymai.R
import com.modul.gymai.databinding.ItemTechniqueBinding

class TechniqueAdapter(
    private val items: List<String>,
    private val isCorrect: Boolean
) : RecyclerView.Adapter<TechniqueAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemTechniqueBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTechniqueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        with(holder.binding) {
            tvTechnique.text = items[position]
            if (isCorrect) {
                ivIcon.setImageResource(R.drawable.ic_check_circle)
                ivIcon.setColorFilter(Color.parseColor("#22C55E"))
                root.setBackgroundResource(R.drawable.bg_technique_item_correct)
            } else {
                ivIcon.setImageResource(R.drawable.ic_x_circle)
                ivIcon.setColorFilter(Color.parseColor("#EF4444"))
                root.setBackgroundResource(R.drawable.bg_technique_item_wrong)
            }
        }
    }

    override fun getItemCount() = items.size
}
