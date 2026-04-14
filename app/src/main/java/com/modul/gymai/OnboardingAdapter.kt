package com.modul.gymai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class OnboardingSlide(
    val backgroundRes: Int,
    val title: String,
    val subtitle: String
)

class OnboardingAdapter(private val slides: List<OnboardingSlide>) :
    RecyclerView.Adapter<OnboardingAdapter.SlideViewHolder>() {

    inner class SlideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivBg: ImageView = view.findViewById(R.id.iv_onboarding_bg)
        val tvTitle: TextView = view.findViewById(R.id.tv_onboarding_title)
        val tvSubtitle: TextView = view.findViewById(R.id.tv_onboarding_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_onboarding_slide, parent, false)
        return SlideViewHolder(view)
    }

    override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
        val slide = slides[position]
        holder.ivBg.setImageResource(slide.backgroundRes)
        holder.tvTitle.text = slide.title
        holder.tvSubtitle.text = slide.subtitle
    }

    override fun getItemCount(): Int = slides.size
}
