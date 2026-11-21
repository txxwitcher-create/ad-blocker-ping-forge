package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.domain.model.BenchmarkResult
import com.example.domain.model.DnsQueryRecord
import com.example.domain.model.ProtectionCategory
import com.example.domain.model.QueryAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "dns_query_logs")
data class DnsQueryLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val categoryName: String?,
    val isCustomRule: Boolean,
    val actionName: String,
    val latencyMs: Long,
    val resolverName: String,
    val timestampMillis: Long
)

@Entity(tableName = "benchmark_results")
data class BenchmarkResultEntity(
    @PrimaryKey val resolverId: String,
    val resolverName: String,
    val resolverAddress: String,
    val medianMs: Double?,
    val avgMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val successCount: Int,
    val totalCount: Int,
    val reliabilityPercent: Int,
    val failedQueries: Int,
    val lastError: String?,
    val measuredAtMillis: Long,
    val transportLabel: String
)

@Dao
interface DnsQueryLogDao {
    @Insert
    suspend fun insert(entity: DnsQueryLogEntity)

    @Query("SELECT * FROM dns_query_logs ORDER BY timestampMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<DnsQueryLogEntity>>

    @Query("DELETE FROM dns_query_logs WHERE id NOT IN (SELECT id FROM dns_query_logs ORDER BY timestampMillis DESC, id DESC LIMIT :keepCount)")
    suspend fun trimOld(keepCount: Int = 400)

    @Query("DELETE FROM dns_query_logs")
    suspend fun clearAll()
}

@Dao
interface BenchmarkResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(results: List<BenchmarkResultEntity>)

    @Query("SELECT * FROM benchmark_results ORDER BY reliabilityPercent DESC, medianMs ASC")
    fun observeAll(): Flow<List<BenchmarkResultEntity>>

    @Query("DELETE FROM benchmark_results")
    suspend fun clearAll()
}

@Database(
    entities = [DnsQueryLogEntity::class, BenchmarkResultEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dnsQueryLogDao(): DnsQueryLogDao
    abstract fun benchmarkResultDao(): BenchmarkResultDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pingforge_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

fun DnsQueryLogEntity.toDomain(): DnsQueryRecord = DnsQueryRecord(
    id = id,
    domain = domain,
    category = categoryName?.let { runCatching { ProtectionCategory.valueOf(it) }.getOrNull() },
    isCustomRule = isCustomRule,
    action = runCatching { QueryAction.valueOf(actionName) }.getOrDefault(QueryAction.ALLOWED),
    latencyMs = latencyMs,
    resolverName = resolverName,
    timestampMillis = timestampMillis
)

fun DnsQueryRecord.toEntity(): DnsQueryLogEntity = DnsQueryLogEntity(
    id = id,
    domain = domain,
    categoryName = category?.name,
    isCustomRule = isCustomRule,
    actionName = action.name,
    latencyMs = latencyMs,
    resolverName = resolverName,
    timestampMillis = timestampMillis
)

fun BenchmarkResultEntity.toDomain(): BenchmarkResult = BenchmarkResult(
    resolverId = resolverId,
    resolverName = resolverName,
    resolverAddress = resolverAddress,
    medianMs = medianMs,
    avgMs = avgMs,
    minMs = minMs,
    maxMs = maxMs,
    successCount = successCount,
    totalCount = totalCount,
    reliabilityPercent = reliabilityPercent,
    failedQueries = failedQueries,
    lastError = lastError,
    measuredAtMillis = measuredAtMillis,
    transportLabel = transportLabel
)

fun BenchmarkResult.toEntity(): BenchmarkResultEntity = BenchmarkResultEntity(
    resolverId = resolverId,
    resolverName = resolverName,
    resolverAddress = resolverAddress,
    medianMs = medianMs,
    avgMs = avgMs,
    minMs = minMs,
    maxMs = maxMs,
    successCount = successCount,
    totalCount = totalCount,
    reliabilityPercent = reliabilityPercent,
    failedQueries = failedQueries,
    lastError = lastError,
    measuredAtMillis = measuredAtMillis,
    transportLabel = transportLabel
)

class TelemetryRepository(private val database: AppDatabase) {
    fun observeRecentLogs(limit: Int = 100): Flow<List<DnsQueryRecord>> =
        database.dnsQueryLogDao().observeRecent(limit).map { list -> list.map { it.toDomain() } }

    suspend fun recordQuery(record: DnsQueryRecord) {
        database.dnsQueryLogDao().insert(record.toEntity())
        // Periodically trim to keep storage bounded
        if (record.timestampMillis % 17L == 0L) {
            database.dnsQueryLogDao().trimOld(400)
        }
    }

    suspend fun clearLogs() {
        database.dnsQueryLogDao().clearAll()
    }

    fun observeBenchmarks(): Flow<List<BenchmarkResult>> =
        database.benchmarkResultDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun saveBenchmarks(results: List<BenchmarkResult>) {
        database.benchmarkResultDao().upsertAll(results.map { it.toEntity() })
    }

    suspend fun clearBenchmarks() {
        database.benchmarkResultDao().clearAll()
    }
}
