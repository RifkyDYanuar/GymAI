package com.modul.gymai.antarmuka.riwayat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.modul.gymai.data.WorkoutSession
import com.modul.gymai.databinding.FragmentRiwayatBinding
import com.modul.gymai.engine.ExerciseType

class RiwayatFragment : Fragment() {

    private var _binding: FragmentRiwayatBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RiwayatViewModel by viewModels {
        RiwayatViewModelFactory(requireContext())
    }

    private lateinit var adapter: RiwayatAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRiwayatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupTabs()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = RiwayatAdapter()
        adapter.onDeleteClick = { session ->
            showDeleteConfirmation(session)
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter
    }

    private fun showDeleteConfirmation(session: WorkoutSession) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hapus Riwayat")
            .setMessage("Apakah Anda yakin ingin menghapus data latihan ini?")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                viewModel.deleteSession(session)
            }
            .show()
    }

    private fun setupTabs() {
        // Add Tabs
        binding.tabExercises.addTab(binding.tabExercises.newTab().setText("Semua"))
        binding.tabExercises.addTab(binding.tabExercises.newTab().setText("Squat"))
        binding.tabExercises.addTab(binding.tabExercises.newTab().setText("Bicep Curl"))
        binding.tabExercises.addTab(binding.tabExercises.newTab().setText("Lateral Raise"))
        binding.tabExercises.addTab(binding.tabExercises.newTab().setText("Shoulder Press"))

        binding.tabExercises.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val filter = when (tab?.position) {
                    0 -> null
                    1 -> ExerciseType.SQUAT.name
                    2 -> ExerciseType.BICEP_CURL.name
                    3 -> ExerciseType.LATERAL_RAISE.name
                    4 -> ExerciseType.SHOULDER_PRESS.name
                    else -> null
                }
                viewModel.setFilter(filter)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun observeViewModel() {
        viewModel.sessions.observe(viewLifecycleOwner) { sessions ->
            adapter.submitList(sessions)
            if (sessions.isEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.rvHistory.visibility = View.GONE
            } else {
                binding.layoutEmpty.visibility = View.GONE
                binding.rvHistory.visibility = View.VISIBLE
            }
        }

        viewModel.totalReps.observe(viewLifecycleOwner) { reps ->
            binding.tvTotalRepsAll.text = reps.toString()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
