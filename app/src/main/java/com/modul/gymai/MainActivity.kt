package com.modul.gymai

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.core.view.WindowCompat
import com.modul.gymai.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXIT_CONFIRMATION_WINDOW_MS = 2_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private var lastBackPressedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable Edge-to-Edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        registerBackPressHandler()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun registerBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentDestinationId = navController.currentDestination?.id
                val isAtAppRoot = currentDestinationId == R.id.dashboardFragment

                if (!isAtAppRoot && navController.navigateUp()) {
                    return
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPressedAt < EXIT_CONFIRMATION_WINDOW_MS) {
                    finish()
                } else {
                    lastBackPressedAt = now
                    Toast.makeText(
                        this@MainActivity,
                        "Tekan sekali lagi untuk keluar",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }
}
