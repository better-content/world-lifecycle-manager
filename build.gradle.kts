plugins {
    idea
    `maven-publish`
    jacoco
    id("net.minecraftforge.gradle") version "[6.0.24,6.2)"
}

group = property("mod_group_id") as String
version = property("mod_version") as String

base { archivesName.set("world-lifecycle-manager") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }

minecraft {
    mappings("official", property("minecraft_version") as String)
    copyIdeResources = true
    runs {
        configureEach {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "debug")
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", file("build/createSrgToMcp/output.srg").absolutePath)
            mods { create(property("mod_id") as String) { source(sourceSets.main.get()) } }
        }
        create("client")
        create("server") { arg("--nogui") }
        create("gameTestServer") {
            workingDirectory(project.file("run-gametest"))
            property("forge.enableGameTest", "true")
            property("forge.gameTestServer", "true")
            property("forge.enabledGameTestNamespaces", property("mod_id") as String)
            arg("--nogui")
        }
    }
}

repositories {
    maven("https://maven.minecraftforge.net")
    maven("https://libraries.minecraft.net")
    maven("https://maven.createmod.net")
    maven("https://maven.ithundxr.dev/mirror")
    maven("https://maven.tterrag.com/")
    maven("https://www.cursemaven.com") { content { includeGroup("curse.maven") } }
    mavenCentral()
}

fun deobf(notation: String): Any =
    requireNotNull(extensions.getByName("fg").withGroovyBuilder { "deobf"(notation) })

dependencies {
    minecraft("net.minecraftforge:forge:${property("minecraft_version")}-${property("forge_version")}")
    implementation(deobf("com.simibubi.create:create-${property("minecraft_version")}:${property("create_version")}:slim"))
    implementation(deobf("net.createmod.ponder:Ponder-Forge-${property("minecraft_version")}:${property("ponder_version")}"))
    compileOnly(deobf("dev.engine-room.flywheel:flywheel-forge-api-${property("minecraft_version")}:${property("flywheel_version")}"))
    runtimeOnly(deobf("dev.engine-room.flywheel:flywheel-forge-${property("minecraft_version")}:${property("flywheel_version")}"))
    implementation(deobf("com.tterrag.registrate:Registrate:${property("registrate_version")}"))
    implementation(deobf("io.github.llamalad7:mixinextras-forge:0.3.6"))
    compileOnly(deobf("curse.maven:blood-magic-224791:${property("bloodmagic_file_id")}"))
    runtimeOnly(deobf("curse.maven:blood-magic-224791:${property("bloodmagic_file_id")}"))
    runtimeOnly(deobf("curse.maven:patchouli-306770:${property("patchouli_file_id")}"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.named<Jar>("jar") { finalizedBy("reobfJar") }

val stageRuntimeJar by tasks.registering(Copy::class) {
    dependsOn(tasks.named("reobfJar"))
    from(layout.buildDirectory.file("reobfJar/output.jar"))
    into(layout.buildDirectory.dir("libs"))
    rename { "${base.archivesName.get()}-$version.jar" }
}

tasks.named("assemble") { dependsOn(stageRuntimeJar) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
}
tasks.register("headlessGameTest") { dependsOn(tasks.named("runGameTestServer")) }
tasks.register("verifyFast") { dependsOn(tasks.named("check"), tasks.named("jacocoTestReport")) }
tasks.register("verifyFull") { dependsOn(tasks.named("verifyFast"), tasks.named("headlessGameTest")) }

val syncGameTestStructures = tasks.register<Sync>("syncGameTestStructures") {
    from(layout.projectDirectory.dir("src/main/resources/gameteststructures"))
    into(layout.projectDirectory.dir("run-gametest/gameteststructures"))
}

tasks.matching { it.name.startsWith("prepareRunGameTestServer") }.configureEach {
    dependsOn(syncGameTestStructures)
}

tasks.processResources {
    val props = mapOf(
        "minecraft_version" to project.property("minecraft_version"),
        "forge_version" to project.property("forge_version"),
        "mod_id" to project.property("mod_id"),
        "mod_name" to project.property("mod_name"),
        "mod_version" to project.property("mod_version")
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) { expand(props) }
}
