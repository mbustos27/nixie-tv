pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "glacier-androidtv"

include(
    ":app",
    ":core:designsystem",
    ":core:jellyfin",
    ":core:data",
    ":core:player",
    ":core:ffmpeg",
    ":core:updater",
    ":core:log",
    ":detekt-rules",
    ":fuzz",
)

// Nixie: the themes. Their contract is in core:designsystem (io.github.mbustos27.nixie.tv.designsystem).
include(":theme:drivein", ":theme:cinematic", ":theme:editorial")
// Nixie: the baseline profile generator (phase 4).
include(":baselineprofile")
// Nixie: the basics gate's on-device checks (rule 12; tools/gate/Run-Gate.ps1 runs them).
include(":gate")
