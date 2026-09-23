plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Values come from GitHub Actions secrets. Both can also be typed into the app.
val supabaseUrl = System.getenv("SUPABASE_URL")?.takeIf { it.isNotBlank() }
    ?: "https://gdrifmzygvzcolggwhwj.supabase.co"
val supabaseAnonKey = System.getenv("SUPABASE_ANON_KEY")?.takeIf { it.isNotBlank() } ?: ""
val siteUrl = System.getenv("SITE_URL")?.takeIf { it.isNotBlank() } ?: "https://www.sellcraz.com"

android {
    namespace = "com.sellcraz.livewidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sellcraz.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "SITE_URL", "\"$siteUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party libraries on purpose: plain Android + Kotlin only, so the
// cloud build has nothing to go wrong with.
dependencies {}
