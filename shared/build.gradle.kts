plugins {
    kotlin("multiplatform")
    kotlin("native.cocoapods")
    id("com.android.library")
    id("com.google.devtools.ksp")
}

kotlin {
    androidTarget {
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
            api(project(":KuiklyKLineChart"))
            implementation("com.tencent.kuikly-open:core:${providers.gradleProperty("KUIKLY_VERSION").get()}")
            implementation("com.tencent.kuikly-open:core-annotations:${providers.gradleProperty("KUIKLY_VERSION").get()}")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    cocoapods {
        summary = "Kuikly K-Line Chart Demo"
        homepage = "https://github.com/qingfeng19491001/KuiklyKLineChart"
        version = "1.0"
        ios.deploymentTarget = "14.1"
        framework {
            isStatic = true
            baseName = "shared"
            export(project(":KuiklyKLineChart"))
        }
        license = "MIT"
        extraSpecAttributes["resources"] = "['src/commonMain/assets/**']"
    }
}

ksp {
    arg("moduleId", "shared")
    arg("isMainModule", "true")
    arg("subModules", "KuiklyKLineChart")
    arg("enableMultiModule", "true")
}

dependencies {
    compileOnly("com.tencent.kuikly-open:core-ksp:${providers.gradleProperty("KUIKLY_VERSION").get()}") {
        add("kspIosArm64", this)
        add("kspIosX64", this)
        add("kspIosSimulatorArm64", this)
        add("kspAndroid", this)
        add("kspJs", this)
    }
}

android {
    namespace = "com.kuikly.kuiklyklinechart.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    sourceSets {
        named("main") {
            assets.srcDirs("src/commonMain/assets")
        }
    }
}
