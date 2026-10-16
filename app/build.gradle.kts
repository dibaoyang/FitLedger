import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "com.fitledger"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fitledger"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.8.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("personalUpdate") {
            storeFile = file(providers.gradleProperty("fitledgerKeystore").orElse(rootProject.file("../private-signing/fitledger-update.keystore").absolutePath).get())
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("personalUpdate") }
        getByName("release") { signingConfig = signingConfigs.getByName("personalUpdate") }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
tasks.matching { it.name.startsWith("validateSigning") }.configureEach {
    doFirst {
        val originalKey = android.signingConfigs.getByName("personalUpdate").storeFile!!
        check(originalKey.isFile) {
            "Original FitLedger signing key missing. Restore the private key or set -PfitledgerKeystore=...; NEVER generate a replacement key for an installed app."
        }
        val keyStore = KeyStore.getInstance("JKS")
        originalKey.inputStream().use { keyStore.load(it,"android".toCharArray()) }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(keyStore.getCertificate("androiddebugkey").encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(fingerprint == "45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0") {
            "Signing certificate differs from FitLedger v1.0. Refusing an incompatible update."
        }
    }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}



