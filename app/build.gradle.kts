plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The cdylibs are cross-compiled by Nix (nix/native-libs.nix) and handed over as
// a directory, the same way sms-forwarder hands over its CA bundle. Gradle does
// not build Rust: keeping the two halves separate is what lets the Rust tests
// run on the host while the libraries are built for the phone.
val jniLibsDir = providers.environmentVariable("JNI_LIBS_DIR")

val signingStoreFile = providers.environmentVariable("SIGNING_STORE_FILE")
val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD")
val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD")

android {
    namespace = "com.example.irohbrowser"
    compileSdk = 34

    signingConfigs {
        create("release") {
            val storeFilePath = signingStoreFile.orNull
                ?: error("SIGNING_STORE_FILE must be set by the build environment")
            storeFile = file(storeFilePath)
            storePassword = signingStorePassword.orNull
                ?: error("SIGNING_STORE_PASSWORD must be set by the build environment")
            keyAlias = signingKeyAlias.orNull
                ?: error("SIGNING_KEY_ALIAS must be set by the build environment")
            keyPassword = signingKeyPassword.orNull
                ?: error("SIGNING_KEY_PASSWORD must be set by the build environment")
        }
    }

    defaultConfig {
        applicationId = "com.example.irohbrowser"
        // The only device this targets runs Android 17. Matching compileSdk
        // keeps one platform's behaviour to reason about, and buys two things
        // concretely: no WRITE_EXTERNAL_STORAGE for downloads (scoped storage
        // from 29), and one Robolectric runtime jar instead of two.
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            val robolectricDepsFile = System.getenv("ROBOLECTRIC_DEPS_PROPERTIES")
            if (!robolectricDepsFile.isNullOrBlank()) {
                it.systemProperty("robolectric-deps.properties", robolectricDepsFile)
            }
        }
    }

    sourceSets.named("main") {
        jniLibs.srcDirs(
            jniLibsDir.orNull ?: error("JNI_LIBS_DIR must be set by the Nix build environment"),
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // The library is opened by name at runtime, so it has to be a real
            // file in the APK rather than a compressed entry.
            useLegacyPackaging = false
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("com.google.android.material:material:1.11.0")

    testImplementation("androidx.test:core:1.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
}
