package com.skyler.pokedexbinder.di

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import com.skyler.pokedexbinder.data.local.*
import com.skyler.pokedexbinder.data.local.backup.DatabaseBackupManager
import com.skyler.pokedexbinder.data.local.backup.FrameworkSqliteVersionReader
import com.skyler.pokedexbinder.data.local.backup.SqliteVersionReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PokedexDatabase =
        buildDatabase(context, PokedexDatabase.DB_FILE_NAME)

    /**
     * Extracted from [provideDatabase] so an instrumented test can exercise this exact production
     * code path (backup check, real migrations, both destructive-fallback flags) against a
     * test-only [dbFileName] — this project has no Hilt test infra (no `hilt-android-testing`
     * dependency, no `HiltTestApplication`), and calling [provideDatabase] directly in a test
     * would operate on the real on-device `pokedex_binder.db` file, which is unacceptable for a
     * feature whose entire purpose is protecting that file from data loss.
     */
    @VisibleForTesting
    internal fun buildDatabase(context: Context, dbFileName: String): PokedexDatabase {
        // Safety net: if Room's destructive fallback is about to drop + recreate every table
        // (no migration path from the on-disk version to SCHEMA_VERSION), back up the current
        // DB file first. No-op on the normal path (matching version or a clean migration chain).
        DatabaseBackupManager().backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = dbFileName,
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )
        return Room.databaseBuilder(context, PokedexDatabase::class.java, dbFileName)
            .addMigrations(*PokedexDatabase.ALL_MIGRATIONS)
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    /**
     * Interface binding rather than a constructor default, matching [com.skyler.pokedexbinder.di.NetworkModule]'s
     * `@Provides`-per-interface convention — it gives tests a seam without leaving a reassignable
     * field on a singleton that guards a destructive operation.
     */
    @Provides
    @Singleton
    fun provideSqliteVersionReader(): SqliteVersionReader = FrameworkSqliteVersionReader()

    @Provides
    @Singleton
    fun provideDatabaseBackupManager(versionReader: SqliteVersionReader): DatabaseBackupManager =
        DatabaseBackupManager(versionReader)

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
