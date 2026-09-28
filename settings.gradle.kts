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
            if (requested.id.id == "fabric-loom") {
                useModule("net.fabricmc:fabric-loom:${requested.version}")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.3"
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

stonecutter {
    create(rootProject) {
        versionNodes.forEach { node ->
            if (node["enabled"] == true) {
                val mc = node["mc"] as String
                val loader = node["loader"] as String
                version("$mc-$loader", mc).buildscript = "build.$loader.gradle.kts"
            }
        }
        vcsVersion = "1.21.1-neoforge"
    }
}
