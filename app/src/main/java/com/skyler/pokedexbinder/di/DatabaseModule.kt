package com.skyler.pokedexbinder.di

import android.content.Context
import androidx.room.Room
import com.skyler.pokedexbinder.data.local.*
import com.skyler.pokedexbinder.data.local.backup.DatabaseBackupManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DB_FILE_NAME = "pokedex_binder.db"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PokedexDatabase {
        // Safety net: if Room's destructive fallback is about to drop + recreate every table
        // (no migration path from the on-disk version to SCHEMA_VERSION), back up the current
        // DB file first. No-op on the normal path (matching version or a clean migration chain).
        DatabaseBackupManager().backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = DB_FILE_NAME,
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )
        return Room.databaseBuilder(context, PokedexDatabase::class.java, DB_FILE_NAME)
            .addMigrations(*PokedexDatabase.ALL_MIGRATIONS)
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    fun provideMainBinderDao(db: PokedexDatabase): MainBinderDao = db.mainBinderDao()

    @Provides
    fun provideSecondaryBinderDao(db: PokedexDatabase): SecondaryBinderDao = db.secondaryBinderDao()

    @Provides
    fun provideConnectingArtDao(db: PokedexDatabase): ConnectingArtDao = db.connectingArtDao()

    @Provides
    fun providePersonalCollectionDao(db: PokedexDatabase): PersonalCollectionDao = db.personalCollectionDao()

    @Provides
    fun provideUnownBinderDao(db: PokedexDatabase): UnownBinderDao = db.unownBinderDao()
}
