// colecteur-phoenixcrates — a VaniaMetrics-<Name>-<v>.jar under build/libs/
//
// A module is one jar, loaded by the platform if and only if the core is
// present ("depend: [VaniaMetrics]" in plugin.yml). No third-party jar is
// bundled: everything below is compileOnly.
plugins {
    java
}

// The version is that of the API this jar is compiled against: read from the
// included core's Version.java, never copied.
val vaniaCoreDir = gradle.extra["vaniaCoreDir"] as File
val versionSource = vaniaCoreDir.resolve("api/src/main/java/fr/samflix/vaniametrics/api/Version.java")
version = Regex("""VALUE = "([^"]+)"""").find(versionSource.readText())?.groupValues?.get(1)
    ?: error("could not read version from $versionSource")

dependencies {
    // Wired by the composite build to the core repo's api/ project.
    compileOnly("fr.samflix:vania-metrics-api")
    compileOnly(libs.bundles.paper)
    compileOnly(libs.phoenixcrates)
}

tasks.withType<JavaCompile>().configureEach {
    // --release 21: the lobby targets Java 25, the proxy Java 21. The lower one wins.
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-path,-processing,-options", "-Werror"))
}

tasks.processResources {
    val v = version.toString()
    inputs.property("version", v)
    filesMatching("plugin.yml") { filter { it.replace("\${version}", v) } }
}

// The jar name comes from "name:", not the entry class: the server repo lists
// modules by their plugin name. One source, the one Bukkit displays.
val pluginYml = file("src/main/resources/plugin.yml")
val displayName = Regex("""(?m)^name: VaniaMetrics-(\S+)""").find(pluginYml.readText())?.groupValues?.get(1)
    ?: error("$pluginYml: expected \"name:\" in the form VaniaMetrics-<Name>")

tasks.jar {
    archiveFileName = "VaniaMetrics-$displayName-$version.jar"
}
