plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.clearsms"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        // Match upstream Clear SMS so recategorize / diagnostics version checks stay meaningful.
        buildConfigField("int", "VERSION_CODE", "76")
        buildConfigField("String", "VERSION_NAME", "\"0.26.0\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val filteredUpstream = layout.buildDirectory.dir("upstream-kotlin")
val filterUpstreamSources = tasks.register<Sync>("filterUpstreamSources") {
    from(rootProject.file("third_party/clearsms/app/src/main/kotlin"))
    into(filteredUpstream)
    exclude("**/ClearSmsApplication.kt")
    exclude("**/MainActivity.kt")
    exclude("**/ui/inbox/**")
    exclude("**/ui/composemsg/**")
    exclude("**/ui/conversation/**")
    exclude("**/ui/finance/**")
    exclude("**/ui/navigation/**")
    exclude("**/ui/onboarding/**")
    exclude("**/ui/rules/**")
    exclude("**/ui/search/**")
    exclude("**/ui/settings/**")
    exclude("**/ui/theme/**")
    exclude("**/ui/diagnostics/**")
    exclude("**/ui/alerts/AlertsScreen.kt")
    exclude("**/ui/alerts/AlertsViewModel.kt")
    exclude("**/ui/alerts/OlderPaging.kt")
    exclude("**/ui/components/**")
    exclude("**/ui/common/ComposerAttachments.kt")
    exclude("**/ui/common/ComposerHandOff.kt")
    exclude("**/ui/common/ClockFormat.kt")
    exclude("**/ui/common/RelativeTime.kt")
    exclude("**/ui/common/RepliabilityText.kt")
    exclude("**/ui/common/ScheduleTipGate.kt")
}

val filteredUpstreamRes = layout.buildDirectory.dir("upstream-res")
val filterUpstreamRes = tasks.register<Sync>("filterUpstreamRes") {
    from(rootProject.file("third_party/clearsms/app/src/main/res"))
    into(filteredUpstreamRes)
    // Host app owns the launcher icon, app name, and theme. Vendor Theme.ClearSms
    // parents Theme.Material3.DayNight.NoActionBar, which is not on the app classpath.
    exclude("values/themes.xml")
    exclude("mipmap-anydpi/**")
    exclude("mipmap-anydpi-v26/**")
    exclude("drawable/ic_launcher_foreground.xml")
    exclude("drawable/ic_launcher_monochrome.xml")
    exclude("drawable-v26/**")
    exclude("xml/shortcuts.xml")
}

android {
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            kotlin.srcDirs("src/main/kotlin", filteredUpstream)
            res.srcDirs("src/main/res", filteredUpstreamRes)
            assets.srcDirs("../third_party/clearsms/app/src/main/assets")
        }
    }
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("ksp") || it.name == "preBuild" }.configureEach {
    dependsOn(filterUpstreamSources)
    dependsOn(filterUpstreamRes)
}

kotlin {

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "${project.projectDir}/../third_party/clearsms/app/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.datastore.preferences)
}
