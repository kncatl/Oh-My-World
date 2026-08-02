fun prop(name: String): String = property(name).toString()

// Stonecutter 注入的项目名形如 "1.21.1-neoforge"
val mcVersion = stonecutter.current.version
val loader = stonecutter.current.project.substringAfterLast('-')

// MC 版本到 NeoForge / Parchment 的版本映射
val neoVersion = when (mcVersion) {
    "1.21.1" -> "21.1.234"
    "1.21.11" -> "21.11.45"
    else -> throw GradleException("Unsupported Minecraft version: $mcVersion (add it to build.neoforge.gradle.kts)")
}
// javafml 语言提供器版本范围（与 FancyModLoader 主版本对齐，与 MC/NeoForge 版本无关）
// 1.21.x 系列使用 [1,)；后续版本如需可调整
val loaderVersionRange = "[1,)"
val parchmentMc: String? = when (mcVersion) {
    "1.21.1" -> "1.21.1"
    else -> null
}
val parchmentVer: String? = when (mcVersion) {
    "1.21.1" -> "2024.11.17"
    else -> null
}

plugins {
    id("java-library")
    id("net.neoforged.moddev")
}

// 版本差异通过源码内的 Stonecutter 条件编译注释 /*? >=<ver> ... */ 处理

// 产物名: oh-my-world-<mc>-<loader>-<modver>.jar
version = prop("mod_version")
group = prop("mod_group_id")

base {
    archivesName = "oh-my-world-$mcVersion-$loader"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

// Stonecutter 处理后的源码替换默认 src/main/java
sourceSets.getByName("main").java {
    setSrcDirs(listOf(layout.buildDirectory.dir("generated/stonecutter/main/java").get().asFile))
}

sourceSets.getByName("main").resources {
    srcDir("src/generated/resources")
    exclude("**/*.bbmodel")
    exclude("src/generated/**/.cache")
}

repositories {
}

neoForge {
    version = neoVersion

    if (parchmentMc != null && parchmentVer != null) parchment {
        mappingsVersion = parchmentVer
        minecraftVersion = parchmentMc
    }

    runs {
        register("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", prop("mod_id"))
        }
        register("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", prop("mod_id"))
        }
        register("gameTestServer") {
            type = "gameTestServer"
            systemProperty("neoforge.enabledGameTestNamespaces", prop("mod_id"))
        }
        register("data") {
            data()
            programArguments.addAll(
                "--mod", prop("mod_id"), "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath
            )
        }
        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        register(prop("mod_id")) {
            sourceSet(sourceSets.getByName("main"))
        }
    }
}

val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version" to mcVersion,
        "minecraft_version_range" to "[$mcVersion]",
        "neo_version" to neoVersion,
        "loader_version_range" to loaderVersionRange,
        "mod_id" to prop("mod_id"),
        "mod_name" to prop("mod_name"),
        "mod_license" to prop("mod_license"),
        "mod_version" to prop("mod_version")
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from(rootProject.file("src/main/templates"))
    into(layout.buildDirectory.dir("generated/sources/modMetadata"))
}
sourceSets.getByName("main").resources.srcDir(generateModMetadata)
neoForge.ideSyncTask(generateModMetadata)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    dependsOn("stonecutterGenerate")
}

tasks.named("createMinecraftArtifacts") {
    dependsOn("stonecutterGenerate")
}