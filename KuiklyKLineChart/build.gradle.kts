plugins {
    kotlin("multiplatform")
    id("com.android.library")
    `maven-publish`
}

group = providers.gradleProperty("GROUP_ID").get()
version = providers.gradleProperty("MAVEN_VERSION").get()

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
    js(IR) {
        browser()
        nodejs()
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.tencent.kuikly-open:core:${providers.gradleProperty("KUIKLY_VERSION").get()}")
            implementation("com.tencent.kuikly-open:core-annotations:${providers.gradleProperty("KUIKLY_VERSION").get()}")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.tencent.kuiklybase.kline"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
