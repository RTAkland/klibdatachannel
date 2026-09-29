# klibdatachannel

A Kotlin native [libdatachannel](https://github.com/paullouisageneau/libdatachannel) wrapper,
only datachannel, supports `linuxX64` `mingwX64`

# Example

## Use as dependencies

```kotlin
repositories { 
    maven("https://repo.rtast.cn/packages/")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("cn.rtast.webrtc:datachannel:<version>")
        }
    }
}
```

> Latest version https://repo.rtast.cn/packages/-/cn.rtast.webrtc:datachannel/

[TestRTCNoACK](datachannel/src/nativeTest/kotlin/test/TestRTCNoACK.kt), it uses an in-process signaling server

# Open Source

klibdatachannel open source under [Apache-2.0](LICENSE)