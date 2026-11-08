plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val useFakeSignals = (findProperty("useFakeSignals") as String?)?.toBoolean() ?: true

android {
    namespace = "com.mujimujimuji.odo"
    compileSdk = 34
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.mujimujimuji.odo"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("boolean", "USE_FAKE_SIGNALS", useFakeSignals.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Pleos Connect SDK. 호출은 platform/pleos/ 어댑터 안에서만 한다(README '구조').
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.fused.location)
    implementation(libs.pleos.navi.helper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    testImplementation(libs.junit)
    testImplementation(libs.org.json) // JVM 테스트에서 android.jar의 org.json 스텁 대신 실제 구현
    testImplementation(libs.kotlinx.coroutines.test)
}
