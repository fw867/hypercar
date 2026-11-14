import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Local and CI builds read the same checked-in version; rebuilds never increment it.
val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName = releaseVersion.getProperty("versionName")?.trim()
    ?.takeIf { Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+").matches(it) }
    ?: throw GradleException("version.properties 的 versionName 必须为四段数字")
val appVersionCode = releaseVersion.getProperty("versionCode")?.trim()?.toIntOrNull()
    ?.takeIf { it in 1..2_100_000_000 }
    ?: throw GradleException("version.properties 的 versionCode 无效")
if (providers.gradleProperty("hypercar.buildNumber").isPresent) {
    throw GradleException("不再接受 hypercar.buildNumber；请统一修改仓库 version.properties")
}
val officialBuild = providers.gradleProperty("hypercar.officialBuild").orNull == "true"

android {
    namespace = "io.github.hypercar"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hypercar"
        // minSdk 31：静默连车机热点（cmd wifi connect-network）与 FGS connectedDevice
        // 豁免链路在 Android 12+ 才完整；HyperOS4（Android 17/SDK 37）向下兼容。
        minSdk = 31
        // targetSdk 34：与 cardeck 一致——Android 15+ 对 mediaProjection FGS 的
        // 额外 AppOps 校验、以及悬浮窗可见性补充条款都不触发（HyperOS4 实测宽容）。
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            // rust-core 通过 cargo-ndk 编译后放入 jniLibs（见 docs/BUILD.md），
            // 这里不挂 cmake/ndkBuild，避免 AGP 再拉一遍 NDK 工具链。
        }
    }

    sourceSets {
        getByName("main") {
            // libhypercar_core.so 由 `cargo ndk -o app/src/main/jniLibs` 产出
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore.properties")
            // CI 和本地正式包均要求签名材料；版本号只来自 version.properties。
            if (officialBuild && !ks.exists()) {
                throw GradleException(
                    "正式构建缺少 keystore.properties——" +
                        "正式签名是不可逆决定，不允许 debug 签名的「正式版」流出去（见 .github/workflows/android-ci.yml）",
                )
            }
            if (ks.exists()) {
                val props = Properties().apply { ks.inputStream().use { load(it) } }
                // 接到局部 val：storeFile 是可变属性，直接读会触发 smart cast 拒绝
                val sf = rootProject.file(props.getProperty("storeFile") ?: "hypercar.jks")
                storeFile = sf
                // 未配 keystore.properties 里显式声明时按扩展名推断（.p12/.pfx = PKCS12）
                storeType = props.getProperty("storeType")
                    ?: if (sf.name.endsWith(".p12", true) || sf.name.endsWith(".pfx", true)) "PKCS12" else "JKS"
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 压缩：不开的话 Compose/图标库/Shizuku 全量进 dex（实测 41.9MB、
            // dex 占 40.3MB），开完通常能砍到一半以下。JNI 回调接口必须配 keep 规则
            // （见 proguard-rules.pro，否则 Rust 侧按名回调会被 R8 改名/裁掉）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (rootProject.file("keystore.properties").exists()) {
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // SPIKE-P0：Shizuku UserService 用 AIDL 暴露「shell 身份建副屏」。
        // AGP 默认不开 aidl，这里显式打开（P0 结束若删 spike 包，这一行也可删）。
        aidl = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // 注：未引入 navigation-compose 与 datastore-preferences——
    // UI 用 state-based 切换、设置用 SharedPreferences，两者都是白占 dex。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku：静默连车机热点 / 真实蓝牙 MAC / shell 能力（cardeck 同款依赖）
    // provider 是 ShizukuProvider 的宿主（清单需声明，缺它 binder 收不到）。
    // 注意：只有 api + provider 两个构件；不存在 dev.rikka.shizuku:support。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // Exercise remote profile parsing with real JSON semantics in local JVM tests.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
