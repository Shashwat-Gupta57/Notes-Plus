plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

android {
    namespace = "org.flexstudios.notes.plus"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.flexstudios.notes.plus"
        minSdk = 24
        targetSdk = 36
        // Keep in sync with version.json at the repo root and the release tag. The in-app
        // updater compares version.json's versionCode against this one, so a stale value
        // here silently tells every user they are up to date.
        versionCode = 36
        versionName = "16.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("D:/User/Data/Notes-Plus-Keystore")
            storePassword = "Sha#95801"
            keyAlias = "Key-Notes-Plus"
            keyPassword = "Sha#95801"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/ASL2.0"
        }
    }
    testOptions {
        unitTests {
            // android.util.Log and friends return defaults instead of throwing in JVM tests.
            isReturnDefaultValues = true
            all {
                // Small on purpose: VaultCryptoTest pushes 256 MB through this heap to prove
                // the backup format streams instead of buffering.
                it.maxHeapSize = "128m"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // RecyclerView and CardView
    implementation(libs.recyclerview)
    implementation(libs.cardview)

    // Room database
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    // Jetpack Security
    implementation(libs.security.crypto)
    implementation(libs.tink.android)

    // Glide for image loading
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)
    implementation(libs.glide.transformations)

    // ExoPlayer (Media3)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Biometric library
    implementation(libs.biometric)

    // AndroidX Preferences
    implementation(libs.preference)

    // PhotoView for Zoom
    implementation(libs.photoview)

    // Lifecycle Process for Updates
    implementation(libs.lifecycle.process)

    // Google Drive and Auth
    implementation(libs.google.auth)
    implementation(libs.google.drive) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.api.client)
    implementation(libs.google.http.client)
    implementation(libs.work.runtime)
    implementation(libs.exifinterface)

    // Notes accounts and sync (Firebase Auth + Realtime Database, Credential Manager)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}