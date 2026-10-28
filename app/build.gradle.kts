plugins {
    id("com.android.application")
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
    // The SDK nix/android.nix provides: platform 37.0, build tools 37.0.0.
    compileSdk = 37
    buildToolsVersion = "37.0.0"

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
        // The only device this targets runs Android 17. minSdk 34 keeps
        // downloads free of WRITE_EXTERNAL_STORAGE (scoped storage from 29).
        // targetSdk 37 is what Android 17's WebView asks of an app before it
        // gives pages the File System Access API, the only way they pick a
        // folder (ENABLE_FILE_SYSTEM_ACCESS); from 35 and 36 on, the window is
        // also drawn edge to edge and back goes through the dispatcher
        // (MainActivity). Robolectric runs the tests on targetSdk alone: one
        // runtime jar (nix/android.nix).
        minSdk = 34
        targetSdk = 37
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's Android 16 sets up shared memory through a JDK
            // internal, which JDK 21 keeps closed unless the test JVM opens it.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            val robolectricDepsFile = System.getenv("ROBOLECTRIC_DEPS_PROPERTIES")
            if (!robolectricDepsFile.isNullOrBlank()) {
                it.systemProperty("robolectric-deps.properties", robolectricDepsFile)
            }
        }
    }

    sourceSets.named("main") {
        jniLibs.directories += jniLibsDir.orNull ?: error("JNI_LIBS_DIR must be set by the Nix build environment")
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

    // AGP writes an "SDK dependency information" blob into the APK signing block
    // for Play Console, encrypted with a fresh random key every build. It is the
    // only thing that stops two builds of this APK being byte-identical, and
    // nothing here ships to Play. Verified with `nix-build --check`.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
    }
}

// Guava's -jre release (Robolectric's) files its Android jar under the -android
// release's folder, which its Gradle module metadata points to. The offline
// repository (mkM2Repository) looks under the version Gradle recorded, and finds
// nothing. Asking the unit tests for the -android release by name, which is what
// they get anyway, keeps the jar in its own folder.
configurations.configureEach {
    if ("UnitTest" in name) {
        resolutionStrategy.eachDependency {
            val version = requested.version
            if (requested.group == "com.google.guava" && requested.name == "guava" && version != null && version.endsWith("-jre")) {
                useVersion(version.removeSuffix("-jre") + "-android")
                because("the offline repository can't follow the -jre release's metadata to the -android jar")
            }
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("com.google.android.material:material:1.14.0")

    testImplementation("androidx.test:core:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    // Plays the server in PasskeyAuthenticatorTest: an independent WebAuthn
    // implementation, so the passkeys the app produces are checked by code
    // that did not produce them.
    testImplementation("com.webauthn4j:webauthn4j-core:0.31.11.RELEASE")
}
