package com.elderlycaller.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elderlycaller.data.AppDatabase
import com.elderlycaller.data.Tile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val PREFS_NAME = "admin_prefs"
private const val KEY_PASSWORD = "admin_password"
private const val DEFAULT_PASSWORD = "1234"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val dao = db.tileDao()
    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val tiles: StateFlow<List<Tile>> = dao.getAllTiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun checkPassword(input: String): Boolean =
        input == prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD)

    fun changePassword(newPassword: String) {
        prefs.edit().putString(KEY_PASSWORD, newPassword).apply()
    }

    fun addTile(imagePath: String, label: String, phoneNumber: String) {
        viewModelScope.launch {
            val order = tiles.value.size
            dao.insertTile(Tile(imagePath = imagePath, label = label, phoneNumber = phoneNumber, sortOrder = order))
        }
    }

    fun updateTile(tile: Tile) {
        viewModelScope.launch { dao.updateTile(tile) }
    }

    fun deleteTile(tile: Tile) {
        viewModelScope.launch { dao.deleteTile(tile) }
    }
}
