plugins {
    kotlin("multiplatform")
    id("com.android.library")
    `maven-publish`
}

val mavenVersion: String = findProperty("mavenVersion") as? String
    ?: findProperty("MAVEN_VERSION") as? String
    ?: "1.2.1"
val groupId: String = findProperty("groupId") as? String
    ?: findProperty("GROUP_ID") as? String
    ?: "com.tencent.kuiklybase"
val mavenRepoUrl: String = (findProperty("mavenRepoUrl") as? String)?.takeIf { it.isNotBlank() }
    ?: (findProperty("MAVEN_REPO_URL") as? String)?.takeIf { it.isNotBlank() }
    ?: "https://mirrors.tencent.com/repository/maven/kuikly-open/"
val mavenUsername: String = findProperty("mavenUsername") as? String
    ?: findProperty("MAVEN_USERNAME") as? String
    ?: ""
val mavenPassword: String = findProperty("mavenPassword") as? String
    ?: findProperty("MAVEN_PASSWORD") as? String
    ?: ""

group = groupId
version = if (mavenVersion.contains("KBA-010")) {
    mavenVersion
} else {
    val snapshot = mavenVersion.endsWith("-SNAPSHOT")
    val base = mavenVersion.removeSuffix("-SNAPSHOT").substringBeforeLast('-')
    "$base-2.0.21-KBA-010${if (snapshot) "-SNAPSHOT" else ""}"
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
        publishLibraryVariants("release")
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    ohosArm64 {
        binaries.sharedLib {
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyOhosVersion()}")
                implementation("com.tencent.kuikly-open:core-annotations:${Version.getKuiklyOhosVersion()}")
                // OHOS Kotlin 2.0.21-KBA 工具链专用协程分支（含 ohosArm64 klib），
                // 官方 kotlinx-coroutines 未发布 ohos 目标。
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:2.0.21-coroutines-KBA-001")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
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

publishing {
    repositories {
        maven {
            url = uri(mavenRepoUrl)
            credentials {
                username = mavenUsername
                password = mavenPassword
            }
        }
    }
    publications.withType<MavenPublication>().configureEach {
        if (providers.gradleProperty("lowercaseMavenArtifacts").orNull == "true") {
            artifactId = artifactId.lowercase()
        }
    }
}
