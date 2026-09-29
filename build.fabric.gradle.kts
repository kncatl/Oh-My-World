import groovy.json.JsonSlurper

fun prop(name: String): String = property(name).toString()

// Stonecutter 注入的项目名形如 "1.21.11-fabric"
val mcVersion = stonecutter.current.version
val loader = stonecutter.current.project.substringAfterLast('-')

// 版本映射来自仓库根目录的单一清单 versions.json（与 NeoForge 侧共用同一份清单）
@Suppress("UNCHECKED_CAST")
val nodeSpec: Map<String, Any?> = run {
    val nodes = (JsonSlurper().parse(rootProject.file("versions.json")) as Map<String, Any?>)["nodes"]
            as List<Map<String, Any?>>
    nodes.firstOrNull { it["mc"] == mcVersion && it["loader"] == loader }
        ?: throw GradleException("versions.json 中没有节点 $mcVersion-$loader —— 请先在那里登记")
}
val fabricLoaderVersion = nodeSpec["fabricLoader"] as String
val fabricApiVersion = nodeSpec["fabricApi"] as String
// 跨版本范围时，Fabric API 的下限取该范围覆盖的**最低** MC 版本对应的 API 版本
// （可选字段 fabricApiMin；缺省用本节点的构建版本）
val fabricApiMin = (nodeSpec["fabricApiMin"] as? String) ?: fabricApiVersion
val javaVersion = (nodeSpec["java"] as Number).toInt()

// MC 覆盖范围（与 NeoForge 侧同一份 versions.json 数据，语法换成 semver）：
// **只写经过「真 jar + 真服务器」验证过的版本**；省略 mcRange 表示只支持本节点版本。
@Suppress("UNCHECKED_CAST")
val mcRangeSpec = nodeSpec["mcRange"] as? Map<String, Any?> ?: emptyMap()
val mcRangeFrom = (mcRangeSpec["from"] as? String) ?: mcVersion
val mcRangeToExclusive = mcRangeSpec["toExclusive"] as? String
val minecraftRange = if (mcRangeToExclusive == null) mcRangeFrom else ">=$mcRangeFrom <$mcRangeToExclusive"
// Fabric API 依赖：单版本用精确版本；跨版本时放宽为 >=（API 版本号随 MC 版本递增，
// 用户会装上与其 MC 版本对应的那份）。
val fabricApiRange = if (mcRangeToExclusive == null) fabricApiVersion else ">=$fabricApiMin"

plugins {
    id("fabric-loom")
}

// 产物名: oh-my-world-<mc>-<loader>-<modver>.jar
version = prop("mod_version")
group = prop("mod_group_id")

base {
    archivesName = "oh-my-world-$mcVersion-$loader"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)

// Stonecutter 处理后的源码替换默认 src/main/java
sourceSets.getByName("main").java {
    setSrcDirs(listOf(layout.buildDirectory.dir("generated/stonecutter/main/java").get().asFile))
}

// Fabric 排除 NeoForge 模板（fabric.mod.json 由 src/main/resources 提供）
sourceSets.getByName("main").resources {
    exclude("META-INF/neoforge.mods.toml")
    exclude("ohmyworld.mixins.json")
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn("stonecutterGenerate")
}

// fabric.mod.json 占位符替换
tasks.processResources {
    from(rootProject.file("LICENSE"))
    val props = mapOf(
        "mod_version" to prop("mod_version"),
        "mod_id" to prop("mod_id"),
        "minecraft_range" to minecraftRange,
        "fabric_api_range" to fabricApiRange
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

// 构建结束后打印 jar 产物路径，避免“构建成功却找不到产物”
tasks.named("build") {
    doLast {
        val libsDir = layout.buildDirectory.dir("libs").get().asFile
        val jars = libsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.sortedBy { it.name } ?: emptyList()
        if (jars.isEmpty()) {
            println("Oh My World: [$mcVersion-$loader] no jar found in ${libsDir.absolutePath}")
        } else {
            jars.forEach { println("Oh My World: [$mcVersion-$loader] jar -> ${it.absolutePath}") }
        }
    }
}
