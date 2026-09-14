plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.ramdos.keyboard_prototype"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.ramdos.keyboard_prototype"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources.merges += setOf("META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md")
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
