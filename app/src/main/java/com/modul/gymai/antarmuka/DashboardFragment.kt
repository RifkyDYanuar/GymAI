package com.modul.gymai.antarmuka

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.modul.gymai.R
import com.modul.gymai.antarmuka.beranda.BerandaFragment
import com.modul.gymai.antarmuka.info.InfoFragment
import com.modul.gymai.antarmuka.latihan.LatihanFragment
import com.modul.gymai.antarmuka.panduan.PanduanFragment
import com.modul.gymai.antarmuka.riwayat.RiwayatFragment
import com.modul.gymai.databinding.FragmentDashboardBinding
import com.modul.gymai.ui.MaterialSymbols
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

        MaterialSymbols.applyToTree(binding.root)
        MaterialSymbols.applyMenuIcons(
            requireContext(),
            binding.bottomNav.menu,
            mapOf(
                R.id.berandaFragment to "home",
                R.id.panduanFragment to "menu_book",
                R.id.riwayatFragment to "history",
                R.id.infoFragment to "info"
            )
        )

        // Setup ViewPager
        val adapter = DashboardPagerAdapter(this)
        binding.viewPager.adapter = adapter
        
        // Add zoom out animation
        binding.viewPager.setPageTransformer(ZoomOutPageTransformer())

        // Sync ViewPager changes to BottomNavigationView
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Direct mapping 1:1 since ViewPager now has 5 items matching Menu
                binding.bottomNav.menu.getItem(position).isChecked = true
            }
        })

        // Sync BottomNavigationView clicks to ViewPager
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.berandaFragment -> openDashboardPage(0, true)
                R.id.panduanFragment -> openDashboardPage(1, true)
                R.id.placeholder -> openDashboardPage(2, true)
                R.id.riwayatFragment -> openDashboardPage(3, true)
                R.id.infoFragment -> openDashboardPage(4, true)
            }
            true
        }

        // FAB Click Listener - Open Latihan Selection (Page 2)
        binding.fabEvaluasi.setOnClickListener {
            openDashboardPage(2, true)
        }

        observeHistoryOpenRequest()
    }

    private fun observeHistoryOpenRequest() {
        findNavController()
            .currentBackStackEntry
            ?.savedStateHandle
            ?.getLiveData<Boolean>("openHistoryTab")
            ?.observe(viewLifecycleOwner) { shouldOpen ->
                if (shouldOpen == true) {
                    openDashboardPage(3, false)
                    findNavController()
                        .currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("openHistoryTab", false)
                }
            }
    }

    private fun openDashboardPage(index: Int, smoothScroll: Boolean) {
        val safeBinding = _binding ?: return
        safeBinding.bottomNav.menu.getItem(index).isChecked = true
        safeBinding.viewPager.post {
            val currentBinding = _binding ?: return@post
            if (currentBinding.viewPager.currentItem != index) {
                currentBinding.viewPager.setCurrentItem(index, smoothScroll)
            } else {
                currentBinding.viewPager.adapter?.notifyItemChanged(index)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class DashboardPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 5

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> BerandaFragment()
                1 -> PanduanFragment()
                2 -> LatihanFragment() // For Evaluasi
                3 -> RiwayatFragment()
                4 -> InfoFragment()
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
