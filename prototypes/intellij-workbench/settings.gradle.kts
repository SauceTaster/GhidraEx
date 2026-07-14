pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "ghidraex-intellij-workbench"

includeBuild("../../integration/view-state/java")
