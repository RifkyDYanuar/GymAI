package com.modul.gymai.antarmuka.beranda

import android.content.Context
import androidx.lifecycle.*
import com.modul.gymai.data.GymDatabase
import com.modul.gymai.data.WorkoutRepository
import com.modul.gymai.data.WorkoutSession
import kotlinx.coroutines.launch

class BerandaViewModel(private val repository: WorkoutRepository) : ViewModel() {

    private val _todayReps = MutableLiveData<Int>(0)
    val todayReps: LiveData<Int> = _todayReps

    private val _totalSessions = MutableLiveData<Int>(0)
    val totalSessions: LiveData<Int> = _totalSessions

    private val _recentSessions = MutableLiveData<List<WorkoutSession>>(emptyList())
    val recentSessions: LiveData<List<WorkoutSession>> = _recentSessions

    init {
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            _todayReps.value = repository.getTodayReps()
            _totalSessions.value = repository.getTotalSessions()
            _recentSessions.value = repository.getRecentSessions(5)
        }
    }

    fun refresh() {
        loadStats()
    }
}

class BerandaViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = GymDatabase.getInstance(context)
        val repo = WorkoutRepository(db.workoutSessionDao())
        @Suppress("UNCHECKED_CAST")
        return BerandaViewModel(repo) as T
    }
}
