package com.skyler.pokedexbinder.repository

import org.junit.Assert.assertTrue
import java.lang.reflect.Modifier

/**
 * Guards a hand-written, secret-masking `toString()` on a data class:
 *  1. every instance field is named in the output (a new field is not silently dropped);
 *  2. a String field only shows its value if listed in [allowedStringFields] (a new String field,
 *     possibly a secret, does not leak through a careless edit).
 * Assumes the primary constructor takes the instance fields in declaration order, String and
 * Boolean only; extend [argFor] if a class grows another type.
 */
internal fun assertToStringMasksSecrets(cls: Class<*>, allowedStringFields: Set<String>) {
    val fields = cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }

    val defaults = cls.getDeclaredConstructor().newInstance().toString()
    for (f in fields) {
        assertTrue("toString of ${cls.simpleName} omits field '${f.name}': $defaults", defaults.contains("${f.name}="))
    }

    val ctor = cls.declaredConstructors.single { it.parameterCount == fields.size }
    val sentinel = { name: String -> "SENTINEL_$name" }
    val text = ctor.newInstance(*fields.map { argFor(it.type, it.name, sentinel) }.toTypedArray()).toString()
    for (f in fields.filter { it.type == String::class.java }) {
        if (f.name in allowedStringFields) continue
        assertTrue(
            "new String field leaks into toString: ${cls.simpleName}.${f.name} -> $text",
            !text.contains(sentinel(f.name))
        )
    }
}

private fun argFor(type: Class<*>, name: String, sentinel: (String) -> String): Any = when (type) {
    String::class.java -> sentinel(name)
    java.lang.Boolean.TYPE -> false
    else -> error("ToStringGuard: unsupported field type ${type.name} for '$name'")
}
