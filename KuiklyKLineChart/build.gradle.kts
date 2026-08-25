plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
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
        nodejs {
            testTask {
                useMocha {
                    timeout = "10s"
                }
            }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.tencent.kuikly-open:core:${providers.gradleProperty("KUIKLY_VERSION").get()}")
                implementation("com.tencent.kuikly-open:core-annotations:${providers.gradleProperty("KUIKLY_VERSION").get()}")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }
        androidMain.dependencies {
            implementation("com.tencent.kuikly-open:core-render-android:${providers.gradleProperty("KUIKLY_VERSION").get()}")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        }
    }
}

ksp {
    arg("moduleId", "KuiklyKLineChart")
    arg("isMainModule", "false")
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

publishing {
    repositories {
        mavenLocal()
        val repoUrl = (findProperty("mavenRepoUrl") as? String)?.takeIf { it.isNotBlank() }
            ?: (findProperty("MAVEN_REPO_URL") as? String)?.takeIf { it.isNotBlank() }
        if (repoUrl != null) {
            maven {
                url = uri(repoUrl)
                credentials {
                    username = (findProperty("mavenUsername") as? String)
                        ?: (findProperty("MAVEN_USERNAME") as? String)
                        ?: ""
                    password = (findProperty("mavenPassword") as? String)
                        ?: (findProperty("MAVEN_PASSWORD") as? String)
                        ?: ""
                }
            }
        }
    }
}
