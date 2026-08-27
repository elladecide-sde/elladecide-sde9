import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Instrumentación del Compose Compiler (opcional): reporte de composables
// skippable/restartable y parámetros inestables. Activar con:
//   ./gradlew :app:compileDebugKotlin -PcomposeCompilerMetrics
// El reporte queda en app/build/compose_metrics/.
val composeMetricsEnabled = project.hasProperty("composeCompilerMetrics")
val composeMetricsDir = layout.buildDirectory.dir("compose_metrics")

android {
    namespace = "com.elladecide.lola"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.elladecide.lola"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        if (composeMetricsEnabled) {
            val metricsDir = composeMetricsDir.get().asFile.absolutePath
            freeCompilerArgs.addAll(
                listOf(
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$metricsDir",
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$metricsDir",
                ),
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // org.json real para los unit tests: en la JVM (sin emulador) el stub de
    // android.jar lanza "not mocked" al usar JSONArray (Product/preciosList).
    testImplementation(libs.org.json)

    // Instrumentación (recomposición/accesibilidad) — corre en androidTest.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.androidx.ui.test.manifest)
}
