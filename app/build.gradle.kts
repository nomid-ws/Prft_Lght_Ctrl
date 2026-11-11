import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) {
        propsFile.inputStream().use { load(it) }
    }
}

val ksFile = System.getenv("PRFT_KEYSTORE_FILE") ?: keystoreProps.getProperty("storeFile")
val ksPassword = System.getenv("PRFT_KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
val ksKeyAlias = System.getenv("PRFT_KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
val ksKeyPassword = System.getenv("PRFT_KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")
val resolvedKsFile = ksFile?.takeIf { it.isNotBlank() }?.let { path ->
    if (File(path).isAbsolute) File(path) else rootProject.file(path)
}
val hasReleaseKeystore = resolvedKsFile != null &&
    resolvedKsFile.isFile &&
    !ksPassword.isNullOrBlank() &&
    !ksKeyAlias.isNullOrBlank() &&
    !ksKeyPassword.isNullOrBlank()

android {
    namespace = "app.prft.lightctrl"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "app.prft.lightctrl"
        minSdk = 31
        targetSdk = 34
        versionCode = 5
        versionName = "1.4.0"
    }

    signingConfigs {
        if (hasReleaseKeystore && resolvedKsFile != null) {
            create("release") {
                storeFile = resolvedKsFile
                storePassword = ksPassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    dependenciesInfo {
        // Disable Google Play encrypted dependency metadata block for F-Droid reproducible builds
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            vcsInfo.include = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:ble"))
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.window)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
