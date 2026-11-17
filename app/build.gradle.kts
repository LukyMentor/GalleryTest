import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.dsl.ApplicationDefaultConfig as Config

// -----------------------------------------------------------------------------
// PLUGINS
// -----------------------------------------------------------------------------
// 📦 Core plugins required for Android + Kotlin + Compose support.
plugins {
    alias(libs.plugins.android.application)   // Android application plugin
    alias(libs.plugins.jetbrains.kotlin.android) // Kotlin Android plugin
    alias(libs.plugins.compose.compiler)         // Compose compiler plugin
    // TODO - Find a way to apply these to only standard flavour
    // ⚠️ Currently Crashlytics + Google Services are applied globally.
}

///
//  -------------------------------------------------------------------------------------
//  APPLICATION CONFIGURATION
//  -------------------------------------------------------------------------------------
//  Defines the core identity and compatibility parameters of the application.
//  This lambda is applied to the [defaultConfig] block within the [android] section.
private val config: Config.() -> Unit = {
    versionCode = 85                                    // Internal version code
    versionName = "1.0.11"                               // User-facing version name
    applicationId = "com.googol.android.apps.photos" // Unique app ID
    minSdk = 29                                         // Minimum supported Android version
    targetSdk = 37                                      // Target SDK
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
}

// -----------------------------------------------------------------------------
// KOTLIN COMPILER OPTIONS
// -----------------------------------------------------------------------------
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17 // Target JVM bytecode version
        freeCompilerArgs.addAll(
            // "-XXLanguage:+ExplicitBackingFields", // Explicit backing fields (disabled)
            "-XXLanguage:+NestedTypeAliases",       // Nested type aliases
            "-Xopt-in=kotlin.RequiresOptIn",        // Opt-in to @RequiresOptIn APIs
            "-Xwhen-guards",                        // Experimental when-guards
            "-Xopt-in=androidx.compose.foundation.ExperimentalFoundationApi", // Compose foundation experimental
            "-Xopt-in=com.zs.compose.theme.ExperimentalThemeApi",             // Custom theme experimental
            "-Xnon-local-break-continue",           // Allow non-local break/continue
            "-Xcontext-sensitive-resolution",       // Context-sensitive overload resolution
            "-Xcontext-parameters"                  // Context parameters (experimental)
        )
    }
}

// -----------------------------------------------------------------------------
// COMPOSE COMPILER CONFIGURATION
// -----------------------------------------------------------------------------
// ⚙️ Controls advanced Compose compiler reporting and stability checks.
// Reports/metrics can be enabled for debugging but are usually disabled in release builds.
composeCompiler {
    // TODO - I guess disable these in release builds.reportsDestination =
    // layout.buildDirectory.dir("compose_compiler")
    // metricsDestination = layout.buildDirectory.dir("compose_compiler")
    stabilityConfigurationFiles = listOf(
        rootProject.layout.projectDirectory.file("stability_config.conf")
    )
}

// -----------------------------------------------------------------------------
// ANDROID CONFIGURATION
// -----------------------------------------------------------------------------
android {
    namespace = "com.zs.gallery"
    buildFeatures { compose = true }
    defaultConfig(config)
    compileSdk { version = release(37){  minorApiLevel = 2} }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }  // Exclude redundant license files
        jniLibs.keepDebugSymbols.add("**/*.so")
    }


    // -----------------------------
    // Java Compatibility Options
    // -----------------------------
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // -----------------------------------------------------------------------------
    // DEFAULT CONFIGURATION
    // -----------------------------------------------------------------------------
    // 📦 Core app settings: ID, SDK versions, versioning, and test runner.

    // -------------------------------------------------------------------------
    // PRODUCT FLAVORS
    // -------------------------------------------------------------------------
    // Personal build: only the FOSS (community) edition — no ads, analytics or billing.
    flavorDimensions += "edition"
    productFlavors {
        create("community") {
            dimension = "edition"
            // Own package name so it installs next to the original app.
            applicationId = "com.lukymentor.gallery"
            versionNameSuffix = "-lm"
        }
    }
    // Stable signing key so every CI build installs as an update over the previous one.
    // The keystore is provided by CI through environment variables (see .github/workflows/build.yml).
    val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) create("personal") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }
    // -----------------------------------------------------------------------------
    // Build Types
    // -----------------------------------------------------------------------------
    buildTypes {
        // -------------------------------------------------------------------------
        // RELEASE BUILD
        // -------------------------------------------------------------------------
        release {
            // ⚙️ Code shrinking/obfuscation (ProGuard/R8) and resource shrinking
            if (keystorePath != null) signingConfig = signingConfigs.getByName("personal")
            isMinifyEnabled = true          // Enable code shrinking/obfuscation
            isShrinkResources = true        // Remove unused resources to reduce APK size

            // 📜 ProGuard/R8 rules for release builds
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // 🔑 Signing configuration (currently using debug keys for convenience)
            // signingConfig = signingConfigs.getByName("debug")
        }

        // -------------------------------------------------------------------------
        // DEBUG BUILD
        // -------------------------------------------------------------------------
        debug {
            // 📛 Appends ".debug" to the application ID so debug and release can coexist
            // applicationIdSuffix = ".dev"
            resValue("string", "launcher_label", "Debug")
            versionNameSuffix = "-debug" // 🔖 Adds "-debug" suffix to version name for clarity
        }
    }
}

// -----------------------------------------------------------------------------
// APP DEPENDENCIES
// -----------------------------------------------------------------------------
dependencies {
    // Local project modules
    implementation(project(":common"))

    // Compose core + BOM
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Navigation + Toolkit
    implementation(libs.navigation.compose)
    implementation(libs.toolkit.theme)
    implementation(libs.toolkit.foundation)
    implementation(libs.toolkit.preferences)

    // AndroidX utilities
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.google.fonts)

    // Compose extensions
    implementation(libs.telephoto.zoomable)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.androidx.exifinterface) // sharp (sub-sampled) zoom
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.koin)
    implementation(libs.chrisbanes.haze)
    implementation(libs.lottie.compose)

    // Bundles
    implementation(libs.bundles.coil)   // Image loading
    implementation(libs.bundles.icons)  // Material icons

    // Debug-only tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}