plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versão: 1.1.<número do build no GitHub Actions>. O app usa o versionCode para saber se há atualização.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
val versionBase = "1.1"

android {
    namespace = "com.vorticis.studio3d"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vorticis.studio3d"
        minSdk = 26
        targetSdk = 35
        versionCode = 100 + buildNumber
        versionName = "$versionBase.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"hellisonvidal/Vorticis-3D-Studio\"")
    }

    signingConfigs {
        // Chave de teste incluída no repositório para que todo APK gerado (local ou no GitHub)
        // tenha a mesma assinatura e possa ser atualizado sem desinstalar.
        // Para publicar na Play Store, troque por uma chave privada.
        create("vorticis") {
            storeFile = file("vorticis-test.keystore")
            storePassword = "vorticis3d"
            keyAlias = "vorticis"
            keyPassword = "vorticis3d"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("vorticis")
        }
        debug {
            signingConfig = signingConfigs.getByName("vorticis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Segmentação do objeto principal no aparelho (Google ML Kit, offline após baixar o módulo)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    // ModuleInstall: força o download do modelo do ML Kit na primeira abertura
    implementation("com.google.android.gms:play-services-base:18.5.0")
}
