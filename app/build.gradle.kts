import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.drinkwatch.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drinkwatch.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.9.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never fall back to the debug key. A debug-signed "release" is refused by
            // every installed app (INSTALL_FAILED_UPDATE_INCOMPATIBLE), and the uninstall
            // that follows wipes the token, watchlist and alerts (allowBackup is off).
            // Null here means an unsigned APK, which the preReleaseBuild check below and
            // scripts/publish.sh both reject.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// Fail a release before it compiles when the key it must be signed with is absent.
// Checked on the release lifecycle task rather than at configuration time so debug
// builds and the JVM tests still work on a machine that does not hold the key.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (!keystorePropsFile.exists()) {
            error("keystore.properties missing — a release APK must not fall back to the debug key")
        }
        val store = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
        if (store == null || !store.isFile) {
            error("release keystore not found (storeFile=${store ?: "unset"}) — a release APK must not fall back to the debug key")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.exifinterface)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.play.services.code.scanner)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(libs.mockwebserver)
    testImplementation(libs.org.json)
}
