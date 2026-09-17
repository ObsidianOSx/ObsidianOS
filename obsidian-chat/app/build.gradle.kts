import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "obsidian.chat"
    // Tor and SQLCipher are built against API 37; targetSdk stays at 36. The SDK names the
    // platform "android-37.0", which AGP 9.0.1 only finds when given the full hash.
    compileSdkVersion("android-37.0")
    defaultConfig {
        applicationId = "obsidian.chat"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The server is reachable only as a Tor onion service, and the app pins its TLS public key
        // instead of trusting certificate authorities. Both come from local.properties, which is
        // never committed, so the published source does not name anyone's server.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        fun serverSetting(name: String, placeholder: String): String =
            providers.gradleProperty(name).orNull ?: localProps.getProperty(name) ?: placeholder.also {
                logger.warn("$name is not set in local.properties, so this build will not reach a server")
            }
        buildConfigField("String", "SERVER_DOMAIN", "\"${serverSetting("obsidian.serverDomain", "example.onion")}\"")
        buildConfigField("String", "SERVER_SPKI_SHA256", "\"${serverSetting("obsidian.serverSpkiSha256", "")}\"")
        // Shown on the security status screen
        buildConfigField("String", "TOR_VERSION", "\"${libs.versions.torAndroid.get()}\"")
        buildConfigField("String", "SMACK_VERSION", "\"${libs.versions.smack.get()}\"")

        // Phones (arm64) and emulators (x86_64) only, which keeps Tor's native libraries small
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions {
        // Smack's startup classes include an Android initializer that touches android.* stubs on the JVM
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += listOf(
          "/META-INF/{AL2.0,LGPL2.1}",
          "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
          "META-INF/DEPENDENCIES",
          "META-INF/INDEX.LIST",
          "META-INF/LICENSE*",
          "META-INF/NOTICE*",
        )
      }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.kotlinx.coroutines.android)

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
  // On the JVM there is no platform XmlPullParser, which Smack needs to start up
  testImplementation(libs.xpp3)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // XMPP: Smack 4.5 with OMEMO. Smack's published POMs wrongly list JUnit and Mockito as
  // compile dependencies, so they are excluded here. The XPP3 parser jars duplicate Android's
  // own org.xmlpull classes, which Smack reaches through XmlPullParserFactory.newInstance().
  for (smackModule in listOf(libs.smack.android.extensions, libs.smack.tcp, libs.smack.experimental, libs.smack.omemo.signal)) {
    implementation(smackModule) {
      exclude(group = "org.junit.jupiter")
      exclude(group = "org.mockito")
      exclude(group = "junit")
      exclude(group = "xpp3")
      exclude(group = "org.codelibs", module = "xpp3")
    }
  }
  // smack-omemo-signal only exposes this at runtime, but the OMEMO store setup references its types
  implementation(libs.signal.protocol.java)

  // OpenPGP identity keys
  implementation(libs.pgpainless.core)

  // Embedded Tor
  implementation(libs.tor.android)
  implementation(libs.jtorctl)

  // Uploads and downloads of encrypted media, over Tor
  implementation(libs.okhttp)

  // Encrypted local database
  implementation(libs.sqlcipher.android)
  implementation(libs.androidx.sqlite)

  // QR codes for fingerprint verification
  implementation(libs.zxing.core)
}
