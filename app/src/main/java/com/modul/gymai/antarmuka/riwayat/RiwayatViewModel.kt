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

    val totalReps: LiveData<Int> = repository.allSessions.map { sessions ->
        sessions.sumOf { it.totalReps }
    }

    fun setFilter(type: String?) {
        _selectedType.value = type
    }

    fun deleteSession(session: WorkoutSession) {
        viewModelScope.launch {
            repository.deleteSession(session)
        }
    }
}

class RiwayatViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = GymDatabase.getInstance(context)
        val repo = WorkoutRepository(db.workoutSessionDao(), context.applicationContext)
        @Suppress("UNCHECKED_CAST")
        return RiwayatViewModel(repo) as T
    }
}
