plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "robotic.slam"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // Android 15+/16 KB page-size readiness:
    // NDK r28+ builds native libraries with 16 KB ELF LOAD alignment by default.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "robotic.slam"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
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
        }
    }
    packaging {
        jniLibs {
            // Keep native libraries uncompressed; AGP 8.5.1+ / 9.x then packages them
            // with 16 KB zip alignment for Android 15+ 16 KB page-size devices.
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
        prefab = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // OpenCV provides the camera preview and native computer-vision runtime.
    implementation(libs.opencv)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}