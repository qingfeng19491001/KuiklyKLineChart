plugins {
    id("com.android.application")
    kotlin("multiplatform")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":KuiklyKLineChart"))
            implementation(project(":KuiklyKLineChartAndroid"))
            implementation("com.tencent.kuikly-open:core:${providers.gradleProperty("KUIKLY_VERSION").get()}")
            implementation("com.tencent.kuikly-open:core-render-android:${providers.gradleProperty("KUIKLY_VERSION").get()}")
        }
    }
}

android {
    namespace = "com.kuikly.kuiklyklinechart"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kuikly.kuiklyklinechart"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
