plugins {
    id("com.android.application") version "9.4.1" apply false
    // Never applied: AGP 9 compiles Kotlin itself (built-in Kotlin), with the
    // Kotlin Gradle plugin on the build's classpath. Naming it here puts this
    // version there, rather than the older one AGP depends on.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
