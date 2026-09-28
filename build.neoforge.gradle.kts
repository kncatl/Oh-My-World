import groovy.json.JsonSlurper

fun prop(name: String): String = property(name).toString()

// Stonecutter 注入的项目名形如 "1.21.1-neoforge"
val mcVersion = stonecutter.current.version
val loader = stonecutter.current.project.substringAfterLast('-')

// 版本映射来自仓库根目录的单一清单 versions.json（新增 rep 版本只改那一个文件；
// CI 构建矩阵也从它生成）。字段含义：
//   neoforgeVersion = 构建所用的 NeoForge 版本（取该 MC 版本线的最新版）
//   neoforgeRange   = mods.toml 中声明的最低兼容区间（Maven 区间语法）
//   java            = 该节点的工具链版本（1.21.x → 21，26.x → 25）
//
// 注意区间语法：[x] 表示"精确等于 x"，[x,) 才是"x 及以上"。
// 必须写成 [x,) —— 写成 [x] 会让新版加载器因依赖不满足而拒绝加载本模组
// （表现为"加载器版本过新，需要回退版本"）。
@Suppress("UNCHECKED_CAST")
val nodeSpec: Map<String, Any?> = run {
    val nodes = (JsonSlurper().parse(rootProject.file("versions.json")) as Map<String, Any?>)["nodes"]
            as List<Map<String, Any?>>
    nodes.firstOrNull { it["mc"] == mcVersion && it["loader"] == loader }
        ?: throw GradleException("versions.json 中没有节点 $mcVersion-$loader —— 请先在那里登记")
}
val neoVersion = nodeSpec["neoforgeVersion"] as String
// 下限取该版本线中本模组实际验证过的版本，好让用户不必被迫升级加载器。
val neoVersionRange = nodeSpec["neoforgeRange"] as String
val javaVersion = (nodeSpec["java"] as Number).toInt()
// javafml 语言提供器版本范围（与 FancyModLoader 主版本对齐，与 MC/NeoForge 版本无关）
val loaderVersionRange = "[1,)"
val parchmentMc: String? = nodeSpec["parchmentMc"] as String?
val parchmentVer: String? = nodeSpec["parchmentVersion"] as String?

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

java.toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)

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

    // 测试源集需要 BlockState 等类：求值器在比较分支里会做 instanceof 检查。
    // 注意类加载本身不触发注册表引导，因此不触碰 Blocks.* 的测试可以正常运行；
    // 需要真实方块的测试请改用 NeoForge 的 testframework。
    addModdingDependenciesTo(sourceSets["test"])

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
