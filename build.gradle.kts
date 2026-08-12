plugins {
    id("com.android.library").version("8.6.1").apply(false)
    id("com.android.application").version("8.6.1").apply(false)
    kotlin("multiplatform").version("2.1.21").apply(false)
    id("com.google.devtools.ksp").version("2.1.21-2.0.1").apply(false)
}

buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        mavenLocal()
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
    }
    dependencies {
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.1.21-2.0.1")
    }
}
