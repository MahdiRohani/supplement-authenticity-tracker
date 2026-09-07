package ir.aut.supplementtracker.core.data.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase private constructor(
    context: Context,
) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    fun verifyCacheDao(): VerifyCacheDao = SqliteVerifyCacheDao(this)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE verify_cache (
                productId TEXT PRIMARY KEY NOT NULL,
                chainProductId TEXT NOT NULL,
                status TEXT NOT NULL,
                authenticity TEXT NOT NULL,
                currentOwner TEXT NOT NULL,
                metadataCid TEXT,
                name TEXT,
                batch TEXT,
                source TEXT NOT NULL,
                message TEXT,
                cached_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS verify_cache")
        onCreate(db)
    }

    companion object {
        private const val DB_NAME = "supplement_verify_cache.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: AppDatabase(context).also { instance = it }
            }
    }
}

private class SqliteVerifyCacheDao(
    private val helper: AppDatabase,
) : VerifyCacheDao {
    override suspend fun upsert(entity: VerifyCacheEntity) {
        val values =
            ContentValues().apply {
                put("productId", entity.productId)
                put("chainProductId", entity.chainProductId)
                put("status", entity.status)
                put("authenticity", entity.authenticity)
                put("currentOwner", entity.currentOwner)
                put("metadataCid", entity.metadataCid)
                put("name", entity.name)
                put("batch", entity.batch)
                put("source", entity.source)
                put("message", entity.message)
                put("cached_at", entity.cachedAt)
            }
        helper.writableDatabase.insertWithOnConflict(
            "verify_cache",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override suspend fun latest(limit: Int): List<VerifyCacheEntity> {
        val cursor =
            helper.readableDatabase.query(
                "verify_cache",
                null,
                null,
                null,
                null,
                null,
                "cached_at DESC",
                limit.toString(),
            )
        return cursor.use { readAll(it) }
    }

    override suspend fun findByProductId(productId: String): VerifyCacheEntity? {
        val cursor =
            helper.readableDatabase.query(
                "verify_cache",
                null,
                "productId = ?",
                arrayOf(productId),
                null,
                null,
                null,
                "1",
            )
        return cursor.use { if (it.moveToFirst()) readOne(it) else null }
    }

    private fun readAll(cursor: android.database.Cursor): List<VerifyCacheEntity> {
        val items = mutableListOf<VerifyCacheEntity>()
        while (cursor.moveToNext()) {
            items += readOne(cursor)
        }
        return items
    }

    private fun readOne(cursor: android.database.Cursor): VerifyCacheEntity {
        fun col(name: String) = cursor.getColumnIndexOrThrow(name)
        fun optString(name: String): String? =
            cursor.getString(col(name))?.takeIf { it.isNotBlank() }
        return VerifyCacheEntity(
            productId = cursor.getString(col("productId")),
            chainProductId = cursor.getString(col("chainProductId")),
            status = cursor.getString(col("status")),
            authenticity = cursor.getString(col("authenticity")),
            currentOwner = cursor.getString(col("currentOwner")),
            metadataCid = optString("metadataCid"),
            name = optString("name"),
            batch = optString("batch"),
            source = cursor.getString(col("source")),
            message = optString("message"),
            cachedAt = cursor.getLong(col("cached_at")),
        )
    }
}
