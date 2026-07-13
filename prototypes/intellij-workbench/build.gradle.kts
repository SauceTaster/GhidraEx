import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.gradle.process.CommandLineArgumentProvider

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "dev.ghidraex"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1.5")
    }

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

val prepareRunIdeDemoProject by tasks.registering(Sync::class) {
    description = "Creates a disposable project opened by the IntelliJ workbench prototype"
    from(layout.projectDirectory.dir("demo-project"))
    into(layout.buildDirectory.dir("runIde-demo-project"))
}

tasks.named<RunIdeTask>("runIde") {
    dependsOn(prepareRunIdeDemoProject)
    args(layout.buildDirectory.dir("runIde-demo-project").get().asFile.absolutePath)
    // This is an isolated development sandbox opening a repository-owned fixture.
    // Avoid a first-run trust dialog obscuring the workbench we are evaluating.
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Didea.trust.all.projects=true")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "GhidraEx IntelliJ Workbench Prototype"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "251"
            untilBuild = "253.*"
        }
    }
}
