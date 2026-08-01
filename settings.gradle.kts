pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.3"
}

stonecutter {
    create(rootProject) {
        fun match(version: String, vararg loaders: String) =
            loaders.forEach { version("$version-$it", version).buildscript = "build.$it.gradle.kts" }

        // 版本策略：4 个 rep 版本覆盖全部中间小版本。
        // 中间版本与相邻 rep 版本 API 一致时，源码零改动，仅通过
        // NeoForge minecraft_version_range + 发布平台多版本标注声明兼容。
        // 阶段启用顺序：
        //   阶段 0  → 1.21.1-neoforge（基线）
        //   阶段 2  → 26.2-neoforge（优先目标）
        //   阶段 3  → 26.2-fabric
        //   阶段 4  → 1.21.4 / 1.21.11 rep 版本
        match("1.21.1", "neoforge")
        // match("1.21.4", "neoforge", "fabric")
        // match("1.21.11", "neoforge", "fabric")
        // match("26.2", "neoforge", "fabric")

        vcsVersion = "1.21.1-neoforge"
    }
}