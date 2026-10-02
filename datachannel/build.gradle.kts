kotlin {
    linuxX64()
    linuxArm64()

    explicitApi()
    withSourcesJar()

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines)
            implementation(project(":mbedtls"))
            implementation(project(":libdatachannel"))
        }

        commonTest.dependencies {
        }
    }
}