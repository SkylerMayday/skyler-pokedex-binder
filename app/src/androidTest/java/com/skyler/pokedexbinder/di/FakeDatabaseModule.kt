package com.skyler.pokedexbinder.di

import android.content.Context
import androidx.room.Room
import com.skyler.pokedexbinder.data.local.ConnectingArtDao
import com.skyler.pokedexbinder.data.local.MainBinderDao
import com.skyler.pokedexbinder.data.local.PersonalCollectionDao
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.UnownBinderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces [DatabaseModule] for every `@HiltAndroidTest`, per review-verdict.md P1-1: an
 * instrumented test running against the real on-device `pokedex_binder.db` reversed an explicit
 * prior decision (documented in [DatabaseModule] and [DatabaseModuleWiringTest]) that this is
 * unacceptable for a feature whose entire purpose is protecting that file from data loss.
 *
 * Provides an in-memory Room database (wiped when the test process ends, never touches the
 * on-device file) plus the same 5 DAOs [DatabaseModule] provides — every `@Provides` here must
 * mirror that module's surface, since `@TestInstallIn(replaces = [DatabaseModule::class])`
 * replaces the whole module, not just [DatabaseModule.provideDatabase].
 *
 * This module itself seeds nothing: [com.skyler.pokedexbinder.ui.mainbinder.MainBinderViewModel]'s
 * own `init` block seeds the main binder from the bundled `pokemon_slots` resource the same way
 * it would for a real first launch, and every other destination screen (Connecting Art, Secondary
 * Binder) already renders a valid empty state on a fresh install today — an in-memory DB starting
 * empty is exactly that state, not a special test-only condition. Personal Collection is the one
 * exception: its screen's `init` eagerly refreshes over the network when its cache is empty, so
 * `AppNavigationScreenTest`'s own `@Before` seeds that one table directly (via the DAOs this
 * module provides) to keep the reachability test hermetic — see that file for detail.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object FakeDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PokedexDatabase =
        Room.inMemoryDatabaseBuilder(context, PokedexDatabase::class.java).build()

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
