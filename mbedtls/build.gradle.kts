import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

repositories {
    mavenLocal()
}

kotlin {
    linuxX64()
    linuxArm64()

    explicitApi()
    withSourcesJar()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations["main"].cinterops {
            create("mbedtls") {
                definitionFile.set(file("src/cinterop/mbedtls.def"))
                extraOpts("-libraryPath", file("src/cinterop/libs/${this@configureEach.name}").absolutePath)
            }
        }
    }
}