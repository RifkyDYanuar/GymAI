package com.modul.gymai.antarmuka.beranda

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.modul.gymai.R
import com.modul.gymai.antarmuka.latihan.ExerciseRepository
import com.modul.gymai.databinding.FragmentBerandaBinding
import com.modul.gymai.ui.MaterialSymbols
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.*

class BerandaFragment : Fragment() {

    private var _binding: FragmentBerandaBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BerandaViewModel by viewModels {
        BerandaViewModelFactory(requireContext())
    }

    private lateinit var recentActivityAdapter: RecentActivityAdapter
    private lateinit var exerciseInfoAdapter: ExerciseInfoAdapter
    
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBerandaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        MaterialSymbols.applyToTree(binding.root)
        setupDate()
        setupRecyclerViews()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupDate() {
        val sdfDate = SimpleDateFormat("EEEE, dd MMM yyyy", Locale("id", "ID"))
        binding.tvDate.text = sdfDate.format(Date())
        updateClock()
    }

    private fun updateClock() {
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.tvRealtimeClock.text = sdfTime.format(Date())
    }

    private fun setupRecyclerViews() {
        // 1. Exercise Info (Horizontal)
        val exercises = ExerciseRepository.getAllExercises()
        exerciseInfoAdapter = ExerciseInfoAdapter(exercises) { exercise ->
            val args = Bundle().apply {
                putString("exercise_id", exercise.id)
            }
            findNavController().navigate(R.id.detailLatihanFragment, args)
        }
        binding.rvExerciseInfo.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = exerciseInfoAdapter
        }

        // 2. Recent Activity (Vertical)
        recentActivityAdapter = RecentActivityAdapter()
        binding.rvRecentActivity.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentActivityAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnStartDetection.setOnClickListener {
            findNavController().navigate(R.id.latihanFragment)
        }
        
        binding.btnSeeAllExercises.setOnClickListener {
            findNavController().navigate(R.id.latihanFragment)
        }
    }

    private fun observeViewModel() {
        viewModel.todayReps.observe(viewLifecycleOwner) { reps ->
            binding.tvTodayReps.text = reps.toString()
        }

        viewModel.totalSessions.observe(viewLifecycleOwner) { _ ->
            // Optionally calculate avg accuracy across all sessions if not using repository method
            // For now, let's keep it simple or use another observed field
        }
        
        // We might want an actual average accuracy LiveData in VM
        // For this demo, let's just make it look good if data is missing
        
        viewModel.recentSessions.observe(viewLifecycleOwner) { sessions ->
            recentActivityAdapter.submitList(sessions)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
