import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.multiplatform)
}

kotlin {
    android {
        namespace = "dev.getpostingboard.reader.shared"
        compileSdk = 36
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons)
            implementation(libs.ktor.core)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies { implementation(libs.ktor.okhttp) }
        }
        androidMain { dependsOn(jvmSharedMain) }
        val desktopMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.mock)
            implementation(libs.coroutines.test)
        }
        val desktopTest by getting {
            dependencies { implementation(libs.compose.ui.test) }
        }
    }
}

compose.desktop { application { mainClass = "dev.getpostingboard.reader.MainKt" } }

tasks.withType<Test>().configureEach {
    // Opt-in only: normal tests are hermetic and never require network access.
    systemProperty("postingboard.liveTest", providers.gradleProperty("liveTest").getOrElse("false"))
    systemProperty("java.awt.headless", "true")
    System.getProperty("javax.net.ssl.trustStore")?.let { systemProperty("javax.net.ssl.trustStore", it) }
}
