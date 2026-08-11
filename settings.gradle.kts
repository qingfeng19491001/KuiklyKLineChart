pluginManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "KuiklyKLineChart"
include(":KuiklyKLineChart")
