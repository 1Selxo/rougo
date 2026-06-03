plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = System.getenv("GROUP") ?: "de.manhhao.hoshi"
version = System.getenv("VERSION") ?: "local-SNAPSHOT"

android {
    namespace = "de.manhhao.hoshi"
    compileSdk = 36

    // Fixed: Removed explicit versions/paths entirely.
    // Gradle will now auto-detect and use your highest pre-installed local NDK version.

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++23"
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = System.getenv("ARTIFACT") ?: project.name
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
