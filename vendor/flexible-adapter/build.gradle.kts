plugins {
    id("mihon.library")
}

android {
    namespace = "eu.davidea.flexibleadapter"
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "VERSION_NAME", "\"5.1.0-c8013533\"")
    }
}

dependencies {
    api(androidx.recyclerview)
}
