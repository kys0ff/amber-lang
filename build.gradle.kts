import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

group = "amber"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    linuxX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main") {
            val libgc by cinterops.creating {
                defFile(project.file("src/nativeMain/interop/libgc.def"))
                includeDirs(project.file("runtime/gc/include"))
            }
            val libtcc by cinterops.creating {
                defFile(project.file("src/nativeMain/interop/libtcc.def"))
                includeDirs(project.file("runtime/tcc/include"))
            }
        }
        binaries {
            executable {
                entryPoint = "amber.cli.main"
                baseName = "amber"
                // Link against our own static libraries
                linkerOpts("${project.projectDir}/runtime/gc/libgc.a")
                linkerOpts("${project.projectDir}/runtime/tcc/libtcc.a")
                linkerOpts("-ldl", "-lm", "-lpthread")
                linkerOpts("-Wl,--allow-shlib-undefined")
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(libs.kotlinxSerializationJson)
        }
    }
}
