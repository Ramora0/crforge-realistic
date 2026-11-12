/*
 * crforge-realistic - the game's own look for crforge's battle simulator
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    application
    id("com.diffplug.spotless")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // crforge's battle core, from the submodule (settings.gradle.kts substitutes its :core project)
    implementation("org.crforge:core")
    // the core's test helpers: the configured game tables and their shipped rows
    testImplementation(testFixtures("org.crforge:core"))

    // JSON: the art cache's scenes and tables, replay files, the phone server's messages
    implementation(libs.jackson.databind)

    // LibGDX: the renderer, the offscreen recorder and the window
    implementation(libs.gdx)
    implementation(libs.gdx.backend.lwjgl3)
    implementation("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    implementation(libs.gdx.freetype)
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:${libs.versions.gdx.get()}:natives-desktop")

    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// The same formatter as crforge's Java (google-java-format 1.25.2), so code moves between the two
// unchanged. The license headers are not rewritten: the files that came from crforge keep crforge's.
spotless {
    java {
        googleJavaFormat("1.25.2")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val isMac = System.getProperty("os.name").lowercase().contains("mac")
val crforge = gradle.includedBuild("crforge")
val tablesRoot = rootProject.extra["crforge.builtTablesRoot"] as String
val dataVersion = rootProject.extra["crforge.dataVersion"] as String
val lockVersion = rootProject.extra["crforge.lockVersion"] as String
val gameTables = "$tablesRoot/$dataVersion"

// The example replay crforge ships (a scripted battle on the lock's data version).
val exampleReplay = rootProject.file("crforge/examples/replays/ladder.json")

application {
    mainClass.set("org.crforge.realistic.RealisticLauncher")
    // macOS requires this for LWJGL/GLFW
    if (isMac) applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
}

// The launcher reads the game tables crforge's build makes (see the root build for the settings):
// the folder they are built in (crforge.tablesRoot, one folder per data version), the version to
// open (crforge.dataVersion) and the lock's (crforge.lockVersion). Paths in --args are taken from
// the repository's root.
tasks.named<JavaExec>("run") {
    dependsOn(crforge.task(":tables:gameTables"))
    workingDir = rootProject.projectDir
    systemProperty("crforge.tablesRoot", tablesRoot)
    systemProperty("crforge.dataVersion", dataVersion)
    systemProperty("crforge.lockVersion", lockVersion)
    // -Pcrforge.art=<folder>: the art cache (default ~/.crforge/art)
    (findProperty("crforge.art") as String?)?.let { systemProperty("crforge.art", it) }
    // -Pcrforge.windowSmoke=<file.png>: the window's smoke check (RealisticWindow.SMOKE_PROPERTY)
    (findProperty("crforge.windowSmoke") as String?)?.let { systemProperty("crforge.windowSmoke", it) }
}

tasks.test {
    useJUnitPlatform()
    // the tests read the data version's tables (crforge.gameTables), as crforge's own tests do
    dependsOn(crforge.task(":tables:gameTables"))
    inputs.dir(gameTables).withPropertyName("gameTables")
    systemProperty("crforge.gameTables", gameTables)
    systemProperty("crforge.exampleReplay", exampleReplay.absolutePath)
    inputs.file(exampleReplay).withPropertyName("exampleReplay")
    // the tests never read the art cache: a machine with one runs them as one without
    systemProperty("crforge.art", layout.buildDirectory.dir("no-art-cache").get().asFile.absolutePath)
}

// Opt-in: the phone server's draw-list stream measured over a whole battle of two bots, with
// parity frames (docs/phone-server.md). Needs the art cache and a display; never part of check.
tasks.register<JavaExec>("phoneRenderBench") {
    group = "verification"
    description = "Measure the phone server's draw-list stream over a bot battle (art cache, display)."
    dependsOn(tasks.testClasses)
    dependsOn(crforge.task(":tables:gameTables"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.crforge.realistic.server.PhoneRenderBench")
    workingDir = rootProject.projectDir
    if (isMac) jvmArgs("-XstartOnFirstThread")
    systemProperty("crforge.gameTables", gameTables)
}
