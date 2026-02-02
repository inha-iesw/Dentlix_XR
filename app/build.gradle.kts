plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.xr_lab1"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.xr_lab1"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3)
    // XR 라이브러리는 버전 호환성을 위해 직접 버전 지정 (아래 참고)
    // implementation(libs.androidx.compose)  // libs.versions.toml 버전 대신 아래 직접 버전 사용
    // implementation(libs.androidx.runtime)  // 버전 불일치로 인한 충돌 방지
    // implementation(libs.androidx.scenecore) // 버전 불일치로 인한 충돌 방지
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // CameraX가 사용하는 Guava 라이브러리 추가 (ListenableFuture 사용을 위해)
    implementation("com.google.guava:guava:31.1-android")
    // XR 라이브러리 버전 통일
    // 참고: compose.platform이 scenecore의 특정 API를 기대하므로 버전 호환성이 중요합니다
    // alpha10에서는 getSpatialCapabilities() 메서드 불일치 발생
    // alpha01 버전으로 통일 (compose.platform과 scenecore가 호환됨)
    implementation("androidx.xr.compose:compose:1.0.0-alpha10")
    implementation("androidx.xr.runtime:runtime:1.0.0-alpha10")
    implementation("androidx.xr.scenecore:scenecore:1.0.0-alpha10")
    implementation("androidx.xr.compose.material3:material3:1.0.0-alpha10")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    implementation(project(":whisperlib:lib"))
}
