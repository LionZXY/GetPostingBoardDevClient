plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "dev.getpostingboard.reader"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.getpostingboard.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("appVersionName").getOrElse("1.1.0")
    }
    buildFeatures { compose = true }
    signingConfigs {
        providers.environmentVariable("ANDROID_SIGNING_KEYSTORE").orNull?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.coroutines.core)
}
