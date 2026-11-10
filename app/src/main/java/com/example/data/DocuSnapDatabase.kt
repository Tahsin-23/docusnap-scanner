package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FolderEntity::class, DocumentEntity::class, PageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DocuSnapDatabase : RoomDatabase() {
    abstract fun dao(): DocuSnapDao

    companion object {
        @Volatile
        private var instance: DocuSnapDatabase? = null

        fun getInstance(context: Context): DocuSnapDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DocuSnapDatabase::class.java,
                    "docusnap_local.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
