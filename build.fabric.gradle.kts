fun prop(name: String): String = property(name).toString()

// Stonecutter 注入的项目名形如 "1.21.11-fabric"
val mcVersion = stonecutter.current.version
val loader = stonecutter.current.project.substringAfterLast('-')

// Fabric 版本映射
val fabricLoaderVersion = "0.18.1"
val fabricApiVersion = when (mcVersion) {
    "1.21.11" -> "0.141.6+1.21.11"
    else -> throw GradleException("Unsupported Minecraft version for Fabric: $mcVersion")
}

plugins {
    id("fabric-loom")
}

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

// Fabric 排除 NeoForge 模板（fabric.mod.json 由 src/main/resources 提供）
sourceSets.getByName("main").resources {
    exclude("META-INF/neoforge.mods.toml")
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

loom {
    runs {
        configureEach {
            runDir = "run/"
        }
        named("client") {
            client()
            programArgs("--username=Dev")
            configName = "Fabric Client ($mcVersion)"
        }
        named("server") {
            server()
            configName = "Fabric Server ($mcVersion)"
        }
    }

    mods {
        register(prop("mod_id")) {
            sourceSet(sourceSets.getByName("main"))
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    dependsOn("stonecutterGenerate")
}

// fabric.mod.json 占位符替换
tasks.processResources {
    val props = mapOf(
        "mod_version" to prop("mod_version"),
        "mod_id" to prop("mod_id")
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}