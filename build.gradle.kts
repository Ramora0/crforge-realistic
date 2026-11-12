/*
 * crforge-realistic - the game's own look for crforge's battle simulator
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    java
    id("com.diffplug.spotless") version "8.11.0"
}

/** A Gradle property's value, or null when it is not set or blank. */
fun setting(name: String): String? = (findProperty(name) as String?)?.takeIf { it.isNotBlank() }

// The game tables the simulator runs on are not in this repository, nor in crforge's: crforge's build
// makes them from the game's files into a cache (crforge/docs/game-tables.md), and this build asks it
// to (the included build's :tables:gameTables) before every task that needs them. The settings are
// crforge's own, Gradle properties (-P<name>=<value>, or a line in ~/.gradle/gradle.properties):
// - crforge.assetSource, required: where the game's files are read from, "cdn" for the game's asset
//   CDN, or a file: URI of a copy laid out as the CDN. Without it crforge's table build stops,
//   naming it;
// - crforge.dataVersion: the data version to use, by default crforge-data.lock's version= (in the
//   submodule);
// - crforge.cache: the cache the tables are built in, by default ~/.crforge.
// Gradle hands -P properties given on the command line to the included build too, so one
// -Pcrforge.assetSource=cdn serves both.
val lockLines = file("crforge/crforge-data.lock").readLines()

fun lockValue(key: String): String? =
    lockLines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")?.trim()

val lockVersion = lockValue("version") ?: error("crforge/crforge-data.lock has no version= (is the submodule checked out?)")
val dataVersion = setting("crforge.dataVersion") ?: lockVersion
val cacheRoot = setting("crforge.cache") ?: "${System.getProperty("user.home")}/.crforge"

extra["crforge.dataVersion"] = dataVersion
extra["crforge.lockVersion"] = lockVersion
extra["crforge.builtTablesRoot"] = "$cacheRoot/tables"

spotless {
    kotlinGradle {
        target("*.gradle.kts", "realistic/*.gradle.kts")
        ktlint().editorConfigOverride(mapOf("ktlint_standard_max-line-length" to "disabled"))
    }
}

allprojects {
    group = "org.crforge.realistic"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
