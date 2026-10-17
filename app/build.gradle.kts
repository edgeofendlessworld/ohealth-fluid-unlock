import java.util.Properties

plugins {
    id("com.android.application")
}

// 允许通过 keystore.properties 或环境变量提供签名信息；
// 两者都缺失时回退到 debug 签名，保证本地也能直接构建安装。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun prop(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val ksPath = prop("storeFile", "KEYSTORE_FILE")
android {
    namespace = "io.github.endlessworld.ohealthfluid"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.endlessworld.ohealthfluid"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = prop("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = prop("keyAlias", "KEY_ALIAS")
                keyPassword = prop("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 只有 keystore 存在时才使用 release 签名，否则沿用 debug 签名
            if (ksPath != null && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 说明：src/main/resources/META-INF/xposed/* 会作为 Java 资源原样进入 APK，
    // AGP 默认的 packaging 排除规则不涉及该目录，无需额外配置。
}

dependencies {
    // libxposed 现代 API（仅编译期，运行时由框架提供）
    compileOnly("io.github.libxposed:api:102.0.0")
}
