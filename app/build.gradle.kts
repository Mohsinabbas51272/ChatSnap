plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.chatsnap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.chatsnap"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Optimization: Limit resources for debug builds
        resourceConfigurations += listOf("en", "xxhdpi")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Faster builds in debug mode
            isMinifyEnabled = false
            isTestCoverageEnabled = false
            // Disable PNG crunching for faster builds
            isCrunchPngs = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xbackend-threads=4" // Parallelize Kotlin compilation
        )
    }

    buildFeatures {
        viewBinding = true
        // Optimization: Disable features you don't use
        buildConfig = false
        aidl = false
        renderScript = false
        shaders = false
        resValues = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.swiperefreshlayout)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)

    // Media Handling
    implementation(libs.coil)
    implementation(libs.exoplayer)
    implementation(libs.compressor)

    // Architecture & Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // Local Storage (Room) - Migrated to KSP for faster builds
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Location
    implementation(libs.play.services.location)

    // QR Scanner
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Agora for Video/Audio Calls
    implementation(libs.agora.rtc)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
