kotlin {
    linuxX64()
    linuxArm64()

    explicitApi()
    withSourcesJar()

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines)
        }

        nativeMain.dependencies {
            implementation(project(":mbedtls"))
            implementation(project(":libdatachannel"))
        }

        commonTest.dependencies {}
    }

    compilerOptions.freeCompilerArgs.addAll("-Xexpect-actual-classes")
}