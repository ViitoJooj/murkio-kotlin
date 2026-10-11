plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

group = "com.murkio"
version = "0.1.0"

android {
    namespace = "com.murkio.identity.android"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // The tests read the vectors and fixtures every Murkio SDK shares.
                test.systemProperty("murkio.spec", rootProject.file("../../spec").absolutePath)
                // ScreenshotTest renders the canvas to a PNG when this is set.
                System.getProperty("murkio.screenshots")?.let { test.systemProperty("murkio.screenshots", it) }
                for (name in listOf("MURKIO_LIVE_API", "MURKIO_LIVE_PK", "MURKIO_LIVE_EMAIL", "MURKIO_LIVE_PASSWORD")) {
                    test.environment(name, System.getenv(name) ?: "")
                }
            }
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    // Every public declaration of the library states its visibility and its type.
    explicitApi()
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.coroutines.android)

    testImplementation(composeBom)
    testImplementation(libs.junit)
    // android.jar only has stubs of org.json; the tests run on the real one.
    testImplementation(libs.json)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(composeBom)
    debugImplementation(libs.compose.ui.test.manifest)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "murkio-identity-android"
            afterEvaluate { from(components["release"]) }
        }
    }
}
