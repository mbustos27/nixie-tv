// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Nixie: the basics gate (docs/DESIGN-RULES.md, rule 12). Its checks are in src/androidTest: one test APK that
// instruments itself and drives whatever Nixie build is installed with the remote's keys (UiAutomator), so the same
// checks run on a new build and on an older APK. Run them with tools/gate/Run-Gate.ps1, never by hand on a box:
// .\gradlew.bat :gate:assembleDebugAndroidTest builds the APK (gate/build/outputs/apk/androidTest/debug/).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.mbustos27.nixie.tv.gate"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.1")
}

// The gate as a build step: .\gradlew.bat :gate:basicsGate "-Pnixie.stagingAsDebug=true" builds the release-like staging
// APK and the checks, then runs every check in every theme on the emulator (tools/gate/Run-Gate.ps1 -SkipBuild: the
// APKs are this build's) and fails the build when one fails. -PgateArgs="-Themes DriveIn -Only AccountsGate" passes
// options on to the runner.
tasks.register<Exec>("basicsGate") {
    group = "verification"
    description = "Runs the basics gate (docs/DESIGN-RULES.md, rule 12) on the emulator; fails on any failed check."
    dependsOn(":app:assembleNixieStaging", "assembleDebugAndroidTest")
    val runner = rootProject.layout.projectDirectory.file("tools/gate/Run-Gate.ps1").asFile.absolutePath
    val extra = providers.gradleProperty("gateArgs").orElse("")
    commandLine(
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
        extra.map { "& '$runner' -SkipBuild $it; exit \$LASTEXITCODE" }.get(),
    )
}