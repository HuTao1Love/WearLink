pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sing-box libbox.aar built by scripts/build-libbox.sh
        flatDir { dirs("core/libs") }
    }
}

rootProject.name = "WearLink"

include(":shared", ":core", ":wear", ":mobile")
