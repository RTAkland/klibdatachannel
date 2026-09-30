plugins {
    alias(libs.plugins.kotlin) apply false
    id("maven-publish")
}

allprojects {
    group = "cn.rtast.webrtc"
    version = project.property("libVersion").toString()

    repositories {
        mavenCentral()
    }
}

subprojects {
    pluginManager.apply("org.jetbrains.kotlin.multiplatform")
    pluginManager.apply("maven-publish")

    publishing {
        repositories {
            maven("https://repo.rtast.cn/packages") {
                credentials(HttpHeaderCredentials::class) {
                    name = "Authorization"
                    value = "Bearer ${System.getenv("PUBLISH_TOKEN")}"
                }
                authentication {
                    create<HttpHeaderAuthentication>("header")
                }
            }
        }
        publications.withType<MavenPublication> {
            pom {
                name = "kotlin-webrtc"
                description = "Kotlin Native WebRTC libdatachannel wrapper"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "rtakland"
                        name = "RTAkland"
                    }
                }
            }
        }
    }
}