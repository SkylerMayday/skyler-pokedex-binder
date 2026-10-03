package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.remote.TcgdexRef
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Extra image source for [SyncCardImagesUseCase], tried only when TCGdex's own card detail has no
 * image. Implementations contribute themselves with `@Binds @IntoSet`; the set may be empty.
 */
fun interface BlankImageResolver {
    /** An image URL for [ref], or null when this source has none. */
    suspend fun resolve(ref: TcgdexRef): String?
}

/** Declares the (possibly empty) resolver set so injection works before any resolver exists. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ImageResolverModule {
    @Multibinds
    abstract fun resolvers(): Set<BlankImageResolver>
}
