// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Nixie: Drive-in, one of Nixie's themes. It implements the contract in core/designsystem
// (package io.github.mbustos27.nixie.tv.designsystem: NixieTokens, NixieSlots, NixieThemeSpec)
// and never sees a repository or a ViewModel.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mbustos27.nixie.tv.theme.drivein"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":core:designsystem"))

    testImplementation(libs.junit)
}
