plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ingreatsol.reconocimiento_manos_python"

    // Usamos el método nativo para setear la API 37 limpia sin subversiones decimales
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ingreatsol.reconocimiento_manos_python"
        minSdk = 26
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
        jniLibs {
            // Le dice a Android que no intente extraer ni forzar la alineación estricta
            // de librerías nativas de terceros desactualizadas
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // Retrofit y GSON
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // okhttp (Necesario para el manejo de archivos/Multipart)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Dependencias de CameraX
    val camerax_version = "1.3.4" // Versión estable y robusta
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")
    // Librería SweetAlert para Android Nativo
    implementation("com.github.f0ris.sweetalert:library:1.6.2")
}