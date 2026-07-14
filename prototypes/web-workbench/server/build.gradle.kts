plugins {
    application
}

group = "ex.ghidra.prototype"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "ex.ghidra.web.WorkbenchServer"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
