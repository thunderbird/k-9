plugins {
    id(ThunderbirdPlugins.Library.android)
}

android {
    namespace = "app.k9mail.legacy.preferences"
}
dependencies {
    implementation(projects.feature.navigation.drawer)
}
