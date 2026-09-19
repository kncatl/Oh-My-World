fun prop(name: String): String = property(name).toString()

// Stonecutter 注入的项目名形如 "1.21.1-neoforge"
val mcVersion = stonecutter.current.version
val loader = stonecutter.current.project.substringAfterLast('-')

// MC 版本到 NeoForge / Parchment 的版本映射
// neoVersion      = 构建所用的 NeoForge 版本（取该 MC 版本线的最新版）
// neoVersionRange = mods.toml 中声明的最低兼容区间（Maven 区间语法）
//
// 注意区间语法：[x] 表示"精确等于 x"，[x,) 才是"x 及以上"。
// 必须写成 [x,) —— 写成 [x] 会让新版加载器因依赖不满足而拒绝加载本模组
// （表现为"加载器版本过新，需要回退版本"）。
val neoVersion = when (mcVersion) {
    "1.21.1" -> "21.1.251"
    "1.21.11" -> "21.11.45"
    else -> throw GradleException("Unsupported Minecraft version: $mcVersion (add it to build.neoforge.gradle.kts)")
}
// 21.1.x / 21.11.x 各自是同一 MC 版本线，线内向后兼容；
// 下限取该线中本模组实际验证过的版本，好让用户不必被迫升级加载器。
val neoVersionRange = when (mcVersion) {
    "1.21.1" -> "[21.1.234,)"
    "1.21.11" -> "[21.11.45,)"
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
    // NeoForge 排除 Fabric 描述文件
    exclude("fabric.mod.json")
    exclude("ohmyworld.fabric.mixins.json")
}

repositories {
    mavenCentral()
}

neoForge {
    // MDG 的默认行为是：`CI` 环境变量为 "true" 时跳过反编译/重编译（见
    // ModdingVersionSettings 的默认值），本地则完整跑一遍 NeoForm 流水线。
    // 本地跑这一步既慢（数分钟）又容易 OOM——NeoForge 21.11.45 的反编译产物会
    // 让 Vineflower 耗尽堆内存，报 "Vineflower ran out of memory during
    // decompilation"。CI 从不受影响，因为它在源头就跳过了。
    //
    // 这里显式指定，让本地与 CI 使用同一条流水线：更快的构建、不再 OOM，且
    // 依赖仍取自 NeoForge 发布的 artifacts（含 sources），调试体验不变。
    // 若确需本地完整重编译以排查 NeoForge 补丁相关问题，把下面一行改为 false。
    enable {
        version = neoVersion
        setDisableRecompilation(true)
    }

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
        "neo_version_range" to neoVersionRange,
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

tasks.processResources {
    from(rootProject.file("LICENSE"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    dependsOn("stonecutterGenerate")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn("stonecutterGenerate")
}

tasks.named("createMinecraftArtifacts") {
    dependsOn("stonecutterGenerate")
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
