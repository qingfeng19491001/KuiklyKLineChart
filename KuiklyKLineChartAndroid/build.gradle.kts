plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

group = providers.gradleProperty("GROUP_ID").get()
version = providers.gradleProperty("MAVEN_VERSION").get()

android {
    namespace = "com.tencent.kuiklybase.kline.android"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    publishing {
        singleVariant("release")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

dependencies {
    api(project(":KuiklyKLineChart"))
    compileOnly("com.tencent.kuikly-open:core-render-android:${providers.gradleProperty("KUIKLY_VERSION").get()}")
}

afterEvaluate {
    publishing {
        repositories {
            mavenLocal()
            val repoUrl = (findProperty("mavenRepoUrl") as? String)?.takeIf { it.isNotBlank() }
                ?: (findProperty("MAVEN_REPO_URL") as? String)?.takeIf { it.isNotBlank() }
            if (repoUrl != null) {
                maven {
                    url = uri(repoUrl)
                    val user = (findProperty("mavenUsername") as? String)
                        ?: (findProperty("MAVEN_USERNAME") as? String)
                        ?: ""
                    val pass = (findProperty("mavenPassword") as? String)
                        ?: (findProperty("MAVEN_PASSWORD") as? String)
                        ?: ""
                    if (user.isNotBlank() && pass.isNotBlank()) {
                        credentials {
                            username = user
                            password = pass
                        }
                    }
                }
            }
        }
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = if (providers.gradleProperty("lowercaseMavenArtifacts").orNull == "true") {
                    "kuiklyklinechartandroid"
                } else {
                    "KuiklyKLineChartAndroid"
                }
                version = project.version.toString()
            }
        }
    }
}
