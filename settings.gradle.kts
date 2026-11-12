/*
 * crforge-realistic - the game's own look for crforge's battle simulator
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "crforge-realistic"

// The simulator: crforge (https://github.com/voonhous/crforge), the git submodule crforge/, pinned to
// one commit. It is a composite build: crforge's own build runs as it is, with its own settings and
// conventions (the game tables, crforge.assetSource), and this build takes its battle core, the
// :core project, in place of the module org.crforge:core. Nothing else of crforge is used: its
// desktop visualizer, environment and Python package are never built here.
includeBuild("crforge") {
    dependencySubstitution {
        substitute(module("org.crforge:core")).using(project(":core"))
    }
}

// The realistic renderer, the 3D models, the phone server and the launcher.
include("realistic")
