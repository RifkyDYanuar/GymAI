package com.modul.gymai.antarmuka.riwayat

import android.content.Context
import androidx.lifecycle.*
import com.modul.gymai.data.GymDatabase
import com.modul.gymai.data.WorkoutRepository
import com.modul.gymai.data.WorkoutSession
import kotlinx.coroutines.launch

class RiwayatViewModel(private val repository: WorkoutRepository) : ViewModel() {

    private val _selectedType = MutableLiveData<String?>(null) // null means "All"
    val selectedType: LiveData<String?> = _selectedType

    val sessions: LiveData<List<WorkoutSession>> = _selectedType.switchMap { type ->
        if (type == null) {
            repository.allSessions
        } else {
            repository.getSessionsByType(type)
        }
    }

    private val _totalReps = MutableLiveData<Int>(0)
    val totalReps: LiveData<Int> = _totalReps

    private val _avgAccuracy = MutableLiveData<Float>(0f)
    val avgAccuracy: LiveData<Float> = _avgAccuracy

    init {
        loadStats()
    }

    fun setFilter(type: String?) {
        _selectedType.value = type
    }

    fun deleteSession(session: WorkoutSession) {
        viewModelScope.launch {
            repository.deleteSession(session)
            loadStats() // Refresh totals at the top
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            _totalReps.value = repository.getTotalRepsAllTime()
            _avgAccuracy.value = repository.getAverageAccuracy()
        }
    }
}

class RiwayatViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = GymDatabase.getInstance(context)
        val repo = WorkoutRepository(db.workoutSessionDao())
        @Suppress("UNCHECKED_CAST")
        return RiwayatViewModel(repo) as T
    }
}
