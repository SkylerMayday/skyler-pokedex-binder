package com.skyler.pokedexbinder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MainBinderEntry::class, SecondaryBinderEntry::class],
    version = 3,
    exportSchema = false
)
abstract class PokedexDatabase : RoomDatabase() {
    abstract fun mainBinderDao(): MainBinderDao
    abstract fun secondaryBinderDao(): SecondaryBinderDao
}
