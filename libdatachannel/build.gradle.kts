import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

kotlin {
    linuxX64()
    linuxArm64()

    explicitApi()
    withSourcesJar()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations["main"].cinterops {
            create("libdatachannel") {
                definitionFile.set(file("src/cinterop/libdatachannel.def"))
                extraOpts("-libraryPath", file("src/cinterop/libs/${this@configureEach.name}").absolutePath)
                includeDirs(file("src/cinterop/include/libdatachannel/include"))
            }
        }
    }
}