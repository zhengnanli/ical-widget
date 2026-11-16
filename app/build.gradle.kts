import java.util.Properties

plugins {
    id("com.android.application")
}

// Optional default feed URL, kept out of git: add `ical.url=https://…/calendar.ics` to local.properties.
// Without it, the app starts empty and you paste the URL in its settings screen.
val defaultIcsUrl: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("ical.url", "")

android {
    namespace = "com.paulli.icalwidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paulli.icalwidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "DEFAULT_ICS_URL", "\"${defaultIcsUrl.replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    // Lets IcsRealFeedTest print the expansion of a real feed: ICS_FILE=/path/to/cal.ics
    System.getenv("ICS_FILE")?.let { environment("ICS_FILE", it) }
    testLogging { showStandardStreams = true }
}
