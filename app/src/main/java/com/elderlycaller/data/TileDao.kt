package com.elderlycaller.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TileDao {
    @Query("SELECT * FROM tiles ORDER BY sortOrder ASC, id ASC")
    fun getAllTiles(): Flow<List<Tile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTile(tile: Tile): Long

    @Update
    suspend fun updateTile(tile: Tile)

    @Delete
    suspend fun deleteTile(tile: Tile)
}
