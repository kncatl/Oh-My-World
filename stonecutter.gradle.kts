@file:OptIn(dev.kikugie.stonecutter.StonecutterExperimentalAPI::class)

plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.141" apply false
    id("fabric-loom") version "1.14.10" apply false
}

stonecutter active file(".sc_active_version")

tasks.register("runActiveClient") {
    group = "stonecutter"
    description = "Run client of the active Stonecutter version"
    dependsOn(stonecutter.current!!.project + ":runClient")
}

tasks.register("runActiveServer") {
    group = "stonecutter"
    description = "Run server of the active Stonecutter version"
    dependsOn(stonecutter.current!!.project + ":runServer")
}

tasks.register("buildActive") {
    group = "stonecutter"
    description = "Build the active Stonecutter version"
    dependsOn(stonecutter.current!!.project + ":build")
}

stonecutter parameters {
    constants.match(current.project.substringAfterLast('-'), "neoforge", "fabric")
    constants["NEOFORGE"] = current.project.substringAfterLast('-') == "neoforge"
    constants["FABRIC"] = current.project.substringAfterLast('-') == "fabric"
}
