import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

group = "off.kys.amber_lang"
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
                includeDirs(project.file("libs/include/gc"))
            }
            val libtcc by cinterops.creating {
                defFile(project.file("src/nativeMain/interop/libtcc.def"))
                includeDirs(project.file("libs/include/tcc"))
            }
        }
        binaries {
            executable {
                entryPoint = "off.kys.amber_lang.main"
                baseName = "amber"
                linkerOpts("-L${project.projectDir}/libs", "-lgc", "-ltcc")
                linkerOpts("-ldl", "-lm", "-lpthread")
                linkerOpts("-Wl,-rpath,${project.projectDir}/libs")
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
