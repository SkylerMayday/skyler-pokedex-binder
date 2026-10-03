package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.remote.TcgcsvJapanSource
import com.skyler.pokedexbinder.data.remote.TcgdexIds
import com.skyler.pokedexbinder.data.remote.TcgdexRef
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

/** Sync fallback for Japanese cards TCGdex has no image for: the cached TCGCSV Japan source. */
class JapanTcgcsvResolver @Inject constructor(
    private val source: TcgcsvJapanSource
) : BlankImageResolver {
    override suspend fun resolve(ref: TcgdexRef): String? {
        if (ref.lang != "ja") return null
        return source.imageUrl(TcgdexIds.setId(ref.id), TcgdexIds.localId(ref.id))
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class JapanTcgcsvResolverModule {
    @Binds
    @IntoSet
    abstract fun bindJapanTcgcsvResolver(resolver: JapanTcgcsvResolver): BlankImageResolver
}
