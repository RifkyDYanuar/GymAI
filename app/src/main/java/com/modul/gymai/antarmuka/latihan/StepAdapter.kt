package com.modul.gymai.antarmuka.latihan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.modul.gymai.databinding.ItemStepBinding

class StepAdapter(private val steps: List<String>) :
    RecyclerView.Adapter<StepAdapter.StepViewHolder>() {

    inner class StepViewHolder(val binding: ItemStepBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val binding = ItemStepBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StepViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        with(holder.binding) {
            tvStepNumber.text = (position + 1).toString()
            tvStepText.text = steps[position]
        }
    }

    override fun getItemCount() = steps.size
}
