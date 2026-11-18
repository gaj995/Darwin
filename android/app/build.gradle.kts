import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val darwinSigning = Properties().apply {
  val source = rootProject.file("signing.properties")
  if (source.isFile) source.inputStream().use { load(it) }
}

android {
  namespace = "io.github.gajdarwin.cast"
  compileSdk = 36
  defaultConfig {
    applicationId = "io.github.gajdarwin.cast"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    minSdk = 26
    targetSdk = 36
    versionCode = 7
    versionName = "0.4.0"
  }

  signingConfigs {
    if (darwinSigning.isNotEmpty()) create("darwinRelease") {
      storeFile = rootProject.file(darwinSigning.getProperty("storeFile"))
      storePassword = darwinSigning.getProperty("storePassword")
      keyAlias = darwinSigning.getProperty("keyAlias")
      keyPassword = darwinSigning.getProperty("keyPassword")
    }
  }
  buildTypes {
    debug { versionNameSuffix = "-dev" }
    release {
      signingConfig = signingConfigs.findByName("darwinRelease")
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    jniLibs { useLegacyPackaging = true }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("androidx.webkit:webkit:1.14.0")
  implementation("com.journeyapps:zxing-android-embedded:4.3.0")
  implementation("org.nanohttpd:nanohttpd:2.3.1")
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
