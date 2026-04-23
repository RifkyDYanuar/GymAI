package com.modul.gymai.antarmuka.latihan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.modul.gymai.databinding.ItemTechniqueBinding
import com.modul.gymai.ui.MaterialSymbols

class TipAdapter(private val tips: List<String>) :
    RecyclerView.Adapter<TipAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemTechniqueBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTechniqueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        with(holder.binding) {
            tvTechnique.text = tips[position]
            MaterialSymbols.applyImageView(ivIcon, "lightbulb")
            ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#F59E0B")
            )
            root.setBackgroundResource(android.R.color.transparent)
        }
    }

    override fun getItemCount() = tips.size
}
