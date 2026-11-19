plugins {
    id("com.android.application")
}
android {
    namespace = "com.chaiandtapri.counter"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.chaiandtapri.counter"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("test").resources.directories.add("$projectDir/../../specs/fixtures")
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
}
