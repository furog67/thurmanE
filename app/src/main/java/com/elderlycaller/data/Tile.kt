package com.elderlycaller.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tiles")
data class Tile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val imagePath: String,
    val label: String,
    val phoneNumber: String,
    val sortOrder: Int = 0
)
