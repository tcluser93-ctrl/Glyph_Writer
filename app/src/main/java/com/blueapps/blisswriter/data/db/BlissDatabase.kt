package com.blueapps.blisswriter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.blueapps.blisswriter.data.model.BlissEntry

@Database(
    entities = [BlissEntry::class],
    version = 1,
    exportSchema = true
)
abstract class BlissDatabase : RoomDatabase() {

    abstract fun blissDao(): BlissDao

    companion object {
        @Volatile private var INSTANCE: BlissDatabase? = null

        fun getInstance(context: Context): BlissDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlissDatabase::class.java,
                    "bliss_lexicon.db"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                .also { INSTANCE = it }
            }
    }
}
