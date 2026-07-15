package com.skyler.pokedexbinder.di

import android.content.Context
import androidx.room.Room
import com.skyler.pokedexbinder.data.local.*
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
        Room.databaseBuilder(context, PokedexDatabase::class.java, "pokedex_binder.db")
            .addMigrations(
                PokedexDatabase.MIGRATION_4_5,
                PokedexDatabase.MIGRATION_5_6,
                PokedexDatabase.MIGRATION_6_7,
                PokedexDatabase.MIGRATION_7_8,
                PokedexDatabase.MIGRATION_8_9
            )
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

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
