package com.modul.gymai.antarmuka

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
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

    /** Posisi halaman yang di-skip saat swipe (FAB Evaluasi placeholder) */
    private val SKIP_PAGE = 2

    /** Menyimpan posisi terakhir sebelum berpindah halaman, untuk menentukan arah swipe */
    private var lastPage = 0

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

                // Jika mendarat di halaman Evaluasi (FAB placeholder), skip sesuai arah swipe
                if (position == SKIP_PAGE) {
                    val targetPage = if (lastPage < SKIP_PAGE) {
                        // Swipe ke kanan (maju) → lewati ke Riwayat
                        SKIP_PAGE + 1
                    } else {
                        // Swipe ke kiri (mundur) → lewati ke Panduan
                        SKIP_PAGE - 1
                    }
                    binding.viewPager.setCurrentItem(targetPage, false)
                    return
                }

                lastPage = position

                val menuId = pageToMenuId(position)
                if (menuId == null) {
                    // Halaman Latihan (placeholder FAB) — tidak ada item yang aktif
                    // setGroupCheckable(false) agar bisa uncheck semua, lalu restore
                    binding.bottomNav.menu.setGroupCheckable(0, false, false)
                    for (i in 0 until binding.bottomNav.menu.size()) {
                        binding.bottomNav.menu.getItem(i).isChecked = false
                    }
                    binding.bottomNav.menu.setGroupCheckable(0, true, false)
                } else {
                    binding.bottomNav.menu.findItem(menuId)?.isChecked = true
                }
            }
        })

        // Sync BottomNavigationView clicks to ViewPager
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.berandaFragment -> openDashboardPage(0, true)
                R.id.panduanFragment -> openDashboardPage(1, true)
                R.id.placeholder    -> openDashboardPage(2, true)
                R.id.riwayatFragment -> openDashboardPage(3, true)
                R.id.infoFragment   -> openDashboardPage(4, true)
            }
            true
        }

        // FAB Click — navigate ke LatihanFragment via NavController
        // Muncul dari bawah + navbar otomatis hilang (full-screen experience)
        binding.fabEvaluasi.setOnClickListener {
            findNavController().navigate(
                R.id.action_dashboard_to_latihan,
                null,
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_bottom)
                    .setExitAnim(R.anim.scale_fade_out)
                    .setPopEnterAnim(R.anim.scale_fade_in)
                    .setPopExitAnim(R.anim.slide_out_bottom)
                    .build()
            )
        }

        observeHistoryOpenRequest()
    }

    /** Mapping posisi ViewPager → ID menu item (null jika tidak ada item menu untuk posisi itu) */
    private fun pageToMenuId(position: Int): Int? = when (position) {
        0 -> R.id.berandaFragment
        1 -> R.id.panduanFragment
        2 -> null                    // placeholder (FAB page, tidak ada item menu)
        3 -> R.id.riwayatFragment
        4 -> R.id.infoFragment
        else -> null
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
        // Tandai menu item yang sesuai (skip jika placeholder/null)
        pageToMenuId(index)?.let { menuId ->
            safeBinding.bottomNav.menu.findItem(menuId)?.isChecked = true
        }
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
