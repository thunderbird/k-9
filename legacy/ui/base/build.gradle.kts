plugins {
    id(ThunderbirdPlugins.Library.android)
}

dependencies {
    implementation(projects.legacy.core)

    api(libs.androidx.appcompat)
    api(libs.androidx.activity)
    api(libs.android.material)
    api(libs.androidx.navigation.fragment)
    api(libs.androidx.navigation.ui)
    api(libs.androidx.lifecycle.livedata.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.core)
}

android {
    namespace = "com.fsck.k9.ui.base"
}
