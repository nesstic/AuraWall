package com.aura.livewall.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

const val DEFAULT_PERSONA = "Дружелюбная, чуть дерзкая и заботливая. Говорит коротко и тепло."

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val modelPath: String,
    val persona: String,
    val chargingPrompt: String = "",
    val lowBatteryPrompt: String = "",
    val fallbackCharging: String = "",
    val fallbackLowBattery: String = "",
    val sizeBytes: Long = 0
)

@Entity(tableName = "history", indices = [Index("characterId"), Index("ts")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: Long,
    val role: String,            // "user" | "assistant"
    val text: String,
    val ts: Long = System.currentTimeMillis()
)

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY id") fun all(): Flow<List<CharacterEntity>>
    @Query("SELECT * FROM characters WHERE id = :id") suspend fun get(id: Long): CharacterEntity?
    @Query("SELECT * FROM characters ORDER BY id LIMIT 1") suspend fun first(): CharacterEntity?
    @Insert suspend fun insert(c: CharacterEntity): Long
    @Update suspend fun update(c: CharacterEntity)
    @Delete suspend fun delete(c: CharacterEntity)
}

@Dao
interface HistoryDao {
    @Insert suspend fun insert(h: HistoryEntity)
    @Query("SELECT * FROM history WHERE characterId = :cid ORDER BY ts DESC LIMIT :n")
    suspend fun recent(cid: Long, n: Int): List<HistoryEntity>
    @Query("DELETE FROM history WHERE ts < :before") suspend fun purgeBefore(before: Long)
    @Query("DELETE FROM history") suspend fun clear()
}

@Database(entities = [CharacterEntity::class, HistoryEntity::class], version = 1, exportSchema = false)
abstract class AuraDb : RoomDatabase() {
    abstract fun characters(): CharacterDao
    abstract fun history(): HistoryDao

    companion object {
        @Volatile private var inst: AuraDb? = null
        fun get(ctx: Context): AuraDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AuraDb::class.java, "aura.db").build().also { inst = it }
        }
    }
}

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Ежедневная очистка истории: всё, что старше сегодняшней полуночи, удаляется. */
suspend fun AuraDb.purgeOld() = history().purgeBefore(startOfToday())
