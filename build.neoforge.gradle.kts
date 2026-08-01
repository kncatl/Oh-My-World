fun prop(name: String): String = property(name).toString()

// Stonecutter 注入的项目名形如 "1.21.1-neoforge"
val mcVersion = stonecutter.current.version
val loader = stonecutter.current.project.substringAfterLast('-')

plugins {
    id("java-library")
    id("net.neoforged.moddev")
}

// 产物名: oh-my-world-<mc>-<loader>-<modver>.jar
version = prop("mod_version")
group = prop("mod_group_id")

base {
    archivesName = "oh-my-world-$mcVersion-$loader"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

sourceSets.getByName("main").resources {
    srcDir("src/generated/resources")
    exclude("**/*.bbmodel")
    exclude("src/generated/**/.cache")
}

repositories {
}

neoForge {
    version = prop("neo_version")

    parchment {
        mappingsVersion = prop("parchment_mappings_version")
        minecraftVersion = prop("parchment_minecraft_version")
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
        "minecraft_version" to prop("minecraft_version"),
        "minecraft_version_range" to prop("minecraft_version_range"),
        "neo_version" to prop("neo_version"),
        "loader_version_range" to prop("loader_version_range"),
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
}