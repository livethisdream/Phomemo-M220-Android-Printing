plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 moved the Compose compiler into a Kotlin plugin, versioned
    // with Kotlin itself rather than separately.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
