package com.skyler.pokedexbinder.di

import com.skyler.pokedexbinder.data.remote.DiscordApi
import com.skyler.pokedexbinder.data.remote.GitHubApi
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.data.remote.TcgcsvApi
import com.skyler.pokedexbinder.data.remote.TcgdexApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import com.skyler.pokedexbinder.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.pokemontcg.io/v2/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun providePokemonTcgApi(retrofit: Retrofit): PokemonTcgApi =
        retrofit.create(PokemonTcgApi::class.java)

    @Provides
    @Singleton
    @Named("tcgdex")
    fun provideTcgdexRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.tcgdex.net/v2/en/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun provideTcgdexApi(@Named("tcgdex") retrofit: Retrofit): TcgdexApi =
        retrofit.create(TcgdexApi::class.java)

    @Provides
    @Singleton
    @Named("tcgcsv")
    fun provideTcgcsvRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit {
        // tcgcsv.com's bot protection returns 401 for OkHttp's default "okhttp/x.y.z"
        // User-Agent (and any other non-browser-looking UA) — verified live 2026-07-12.
        // A browser-like UA is required or every request is rejected before parsing.
        val tcgcsvClient = okHttp.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) PokedexBinder")
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://tcgcsv.com/")
            .client(tcgcsvClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideTcgcsvApi(@Named("tcgcsv") retrofit: Retrofit): TcgcsvApi =
        retrofit.create(TcgcsvApi::class.java)

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder().baseUrl("https://api.github.com/").client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build()

    @Provides
    @Singleton
    fun provideGitHubApi(@Named("github") retrofit: Retrofit): GitHubApi =
        retrofit.create(GitHubApi::class.java)

    @Provides
    @Singleton
    @Named("discord")
    fun provideDiscordRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder().baseUrl("https://discord.com/").client(okHttp)   // overridden by @Url
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build()

    @Provides
    @Singleton
    fun provideDiscordApi(@Named("discord") retrofit: Retrofit): DiscordApi =
        retrofit.create(DiscordApi::class.java)
}
