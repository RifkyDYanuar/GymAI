package com.modul.gymai.antarmuka

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.modul.gymai.R
import com.modul.gymai.antarmuka.beranda.BerandaFragment
import com.modul.gymai.antarmuka.latihan.LatihanFragment
import com.modul.gymai.antarmuka.panduan.PanduanFragment // actually it's riwayat and profil
import com.modul.gymai.antarmuka.info.InfoFragment
import com.modul.gymai.antarmuka.riwayat.RiwayatFragment
import com.modul.gymai.databinding.FragmentDashboardBinding
import kotlin.math.abs

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup ViewPager
        val adapter = DashboardPagerAdapter(this)
        binding.viewPager.adapter = adapter
        
        // Add zoom out animation
        binding.viewPager.setPageTransformer(ZoomOutPageTransformer())

        // Disable swipe if needed? No, user explicitly wants swipe.

        // Sync ViewPager changes to BottomNavigationView
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.bottomNav.menu.getItem(position).isChecked = true
            }
        })

        // Sync BottomNavigationView clicks to ViewPager
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.berandaFragment -> binding.viewPager.setCurrentItem(0, true)
                R.id.latihanFragment -> binding.viewPager.setCurrentItem(1, true)
                R.id.riwayatFragment -> binding.viewPager.setCurrentItem(2, true)
                R.id.infoFragment -> binding.viewPager.setCurrentItem(3, true)
            }
            true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class DashboardPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> BerandaFragment()
                1 -> LatihanFragment()
                2 -> RiwayatFragment()
                3 -> InfoFragment()
                else -> throw IllegalArgumentException("Invalid position $position")
            }
        }
    }

    private inner class ZoomOutPageTransformer : ViewPager2.PageTransformer {
        private val MIN_SCALE = 0.85f
        private val MIN_ALPHA = 0.5f

        override fun transformPage(view: View, position: Float) {
            view.apply {
                val pageWidth = width
                val pageHeight = height
                when {
                    position < -1 -> {
                        alpha = 0f
                    }
                    position <= 1 -> {
                        val scaleFactor = Math.max(MIN_SCALE, 1 - Math.abs(position))
                        val vertMargin = pageHeight * (1 - scaleFactor) / 2
                        val horzMargin = pageWidth * (1 - scaleFactor) / 2
                        translationX = if (position < 0) {
                            horzMargin - vertMargin / 2
                        } else {
                            horzMargin + vertMargin / 2
                        }

                        scaleX = scaleFactor
                        scaleY = scaleFactor

                        alpha = (MIN_ALPHA +
                                (((scaleFactor - MIN_SCALE) / (1 - MIN_SCALE)) * (1 - MIN_ALPHA)))
                    }
                    else -> {
                        alpha = 0f
                    }
                }
            }
        }
    }
}
