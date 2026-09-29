import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

kotlin {
    linuxX64()

    explicitApi()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations["main"].cinterops {
            create("libdatachannel") {
                definitionFile.set(file("src/cinterop/libdatachannel.def"))
                extraOpts("-libraryPath", file("src/cinterop/libs/${this@configureEach.name}").absolutePath)
                includeDirs(file("src/cinterop/include/libdatachannel/include"))
            }

            create("mbedtls") {
                definitionFile.set(file("src/cinterop/mbedtls.def"))
                extraOpts("-libraryPath", file("src/cinterop/libs/${this@configureEach.name}").absolutePath)
            }
        }
        binaries {
            executable {
                all {
                    linkerOpts.addAll(
                        listOf(
                            "-L/usr/lib/x86_64-linux-gnu",
                            "-lstdc++",
                            "--allow-shlib-undefined",
                            "--unresolved-symbols=ignore-all",
                            "--warn-unresolved-symbols",
                        )
                    )
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines)
        }

        nativeMain.dependencies {
        }

        commonTest.dependencies {

        }
    }
}