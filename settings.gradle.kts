import groovy.json.JsonSlurper

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenLocal()
    }
    resolutionStrategy {
        eachPlugin {
            // Loom 的三个插件 ID 由同一个 artifact 提供（见其
            // META-INF/gradle-plugins/）：fabric-loom（旧）、
            // net.fabricmc.fabric-loom-remap（混淆版本）、
            // net.fabricmc.fabric-loom（26.1+ 非混淆版本）。
            if (requested.id.id in setOf(
                    "fabric-loom",
                    "net.fabricmc.fabric-loom",
                    "net.fabricmc.fabric-loom-remap",
                )) {
                useModule("net.fabricmc:fabric-loom:${requested.version}")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

// ---- 版本清单（单一来源）---------------------------------------------------
// 节点列表与每个节点的 loader 版本、Java 版本都来自仓库根目录的 versions.json；
// 构建脚本（build.neoforge/fabric.gradle.kts）与 CI 矩阵也从它读取，
// 避免「settings / 构建脚本 / workflow」三处版本信息互相漂移。
//
// 版本策略：rep 版本 + 实测后声明兼容范围（细节见工作区 VERSION-ADAPTATION-PLAN.md）。
// 中间小版本只在真实启动验证通过后才纳入声明范围。
@Suppress("UNCHECKED_CAST")
val versionNodes: List<Map<String, Any?>> =
    (JsonSlurper().parse(file("versions.json")) as Map<String, Any?>)
        .let { it["nodes"] as List<Map<String, Any?>> }

/** 版本号逐段数值比较（26.1.2 >= 26.1 → true）。 */
fun atLeast(version: String, floor: String): Boolean {
    val a = version.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
    val b = floor.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return true
}

stonecutter {
    create(rootProject) {
        versionNodes.forEach { node ->
            if (node["enabled"] == true) {
                val mc = node["mc"] as String
                val loader = node["loader"] as String
                // 26.1 起 MC 不再混淆：Fabric 侧换成非重映射的 Loom 插件与构建脚本
                val script = when {
                    loader != "fabric" -> "build.$loader.gradle.kts"
                    atLeast(mc, "26.1") -> "build.fabric26.gradle.kts"
                    else -> "build.fabric.gradle.kts"
                }
                version("$mc-$loader", mc).buildscript = script
            }
        }
        vcsVersion = "1.21.1-neoforge"
    }
}
