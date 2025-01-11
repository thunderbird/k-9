plugins {
    id(ThunderbirdPlugins.Library.android)
    alias(libs.plugins.kotlin.parcelize)
}

dependencies {
    api(projects.mail.common)
    api(projects.backend.api)
    api(projects.library.htmlCleaner)
    api(projects.core.android.common)
    api(projects.core.mail.folder.api)

    api(projects.legacy.account)
    api(projects.legacy.di)
    api(projects.legacy.folder)
    api(projects.legacy.mailstore)
    api(projects.legacy.message)
    api(projects.legacy.notification)
    api(projects.legacy.preferences)
    api(projects.legacy.search)

    implementation(projects.plugins.openpgpApiLib.openpgpApi)
    implementation(projects.feature.telemetry.api)
    implementation(projects.core.featureflags)

    api(libs.androidx.annotation)

    implementation(libs.okio)
    implementation(libs.commons.io)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.jsoup)
    implementation(libs.moshi)
    implementation(libs.timber)
    implementation(libs.mime4j.core)
    implementation(libs.mime4j.dom)
    implementation(projects.feature.navigation.drawer)

    testApi(projects.core.testing)
    testApi(projects.core.android.testing)
    testImplementation(projects.feature.telemetry.noop)
    testImplementation(projects.mail.testing)
    testImplementation(projects.backend.imap)
    testImplementation(projects.mail.protocols.smtp)
    testImplementation(projects.legacy.storage)
    testImplementation(projects.legacy.testing)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.jdom2)
}

android {
    namespace = "com.fsck.k9.core"

    buildFeatures {
        buildConfig = true
    }
}
