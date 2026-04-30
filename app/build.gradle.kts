plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.flexstudios.notes.plus"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.flexstudios.notes.plus"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "3.50"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}