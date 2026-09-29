@file:OptIn(dev.kikugie.stonecutter.StonecutterExperimentalAPI::class)

plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.147" apply false
    // 26.1 起 MC 不再混淆 → 用非重映射插件；1.21.11 及更早用重映射插件。
    // 选择逻辑在 settings.gradle.kts（按节点的 MC 版本决定用哪个 build 脚本）。
    id("net.fabricmc.fabric-loom") version "1.18.2" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.18.2" apply false
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
