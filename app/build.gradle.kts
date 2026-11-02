import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing reads an untracked keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
val releaseProps: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().also { props -> file.inputStream().use(props::load) }
}

val debugKeystore = layout.projectDirectory.file(".debug/debug.keystore")
val generateLocalDebugKey by tasks.registering(Exec::class) {
    outputs.file(debugKeystore)
    onlyIf { !debugKeystore.asFile.exists() }
    doFirst { debugKeystore.asFile.parentFile.mkdirs() }
    val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
    commandLine(keytool.absolutePath, "-genkeypair", "-noprompt", "-keystore", debugKeystore.asFile.absolutePath,
        "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000", "-dname", "CN=Android Debug,O=Airsense,C=IN")
}
tasks.matching { it.name == "validateSigningDebug" }.configureEach { dependsOn(generateLocalDebugKey) }

android {
    namespace = "com.airsense.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.airsense.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs.getByName("debug") {
        storeFile = debugKeystore.asFile
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    if (releaseProps != null) signingConfigs.create("release") {
        storeFile = File(releaseProps.getProperty("storeFile"))
        storePassword = releaseProps.getProperty("storePassword")
        keyAlias = releaseProps.getProperty("keyAlias")
        keyPassword = releaseProps.getProperty("keyPassword")
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            // Unused BouncyCastle data: post-quantum tables and certificate-path message bundles.
            "org/bouncycastle/pqc/**", "org/bouncycastle/x509/CertPathReviewerMessages*", "org/bouncycastle/pkix/CertPathReviewerMessages*",
        )
    }
    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Normal builds resolve the upstream SSHJ artifact. This explicit developer
    // override supports local verification when Maven access is unavailable.
    val offlineSshJar = providers.gradleProperty("offlineSshJar").orNull
    if (offlineSshJar != null) {
        implementation(files(offlineSshJar))
        implementation("org.slf4j:slf4j-api:1.7.30")
    } else {
        implementation("com.hierynomus:sshj:0.40.0")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    testImplementation("junit:junit:4.13.2")
}
