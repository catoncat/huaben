import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        compilations {
            val main by getting
            create("e2eTest") {
                associateWith(main)
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("com.github.houbb:pinyin:0.4.0")
                implementation("com.huaban:jieba-analysis:1.0.2")
                compileOnly("org.json:json:20240303")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("org.json:json:20240303")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
                implementation("org.json:json:20240303")
            }
        }
        val desktopE2eTest by getting {
            kotlin.srcDir("src/e2eTest/kotlin")
            resources.srcDir("src/e2eTest/resources")
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
                implementation("org.json:json:20240303")
                implementation(compose.desktop.currentOs)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
    }
}

android {
    namespace = "com.luna.storynote"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.luna.storynote"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Runs StoryNote opaque-box E2E tests in app/src/e2eTest/kotlin."
    dependsOn("desktopProcessResources")
    val desktopTarget = kotlin.jvm("desktop")
    val mainComp = desktopTarget.compilations.getByName("main")
    val e2e = desktopTarget.compilations.getByName("e2eTest")
    testClassesDirs = e2e.output.classesDirs
    classpath = e2e.runtimeDependencyFiles + e2e.output.allOutputs + mainComp.output.allOutputs
    jvmArgs("-Djava.awt.headless=true")
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "com.luna.storynote.MainDesktopKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "StoryNote"
            packageVersion = "1.0.0"
        }
    }
}
