# ── Moshi ────────────────────────────────────────────────────────────────────
# Keep all classes annotated with @JsonClass (codegen output)
-keep @com.squareup.moshi.JsonClass class * { *; }
# Keep KotlinJsonAdapterFactory reflective adapters
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
# Moshi uses Kotlin reflection; keep metadata
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ── Retrofit ─────────────────────────────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# ── OkHttp ───────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Room ─────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ────────────────────────────────────────────────────────────
-keepclassmembers,allowobfuscation class * {
    @javax.inject.* <fields>;
    @javax.inject.* <methods>;
    @dagger.* <fields>;
    @dagger.* <methods>;
}
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.** class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# ── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Kotlin ───────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ── App data models (Moshi serialization targets) ────────────────────────────
-keep class com.skyler.pokedexbinder.data.** { *; }
-keep class com.skyler.pokedexbinder.domain.** { *; }
-keep class com.skyler.pokedexbinder.repository.** { *; }
