// =============================================================================
// colecteur-phoenixcrates — un jar VaniaMetrics-<Nom>-<v>.jar dans build/libs/
//
// Un module = un jar, chargé par la plateforme si — et seulement si — le noyau
// est présent (« depend: [VaniaMetrics] » dans plugin.yml). Aucun jar tiers
// n'y est embarqué : tout ce qui suit est compileOnly.
// =============================================================================
plugins {
    java
}

// LA VERSION EST CELLE DE L'API contre laquelle ce jar est compilé : lue dans le
// Version.java du core inclus, jamais recopiée.
val vaniaCoreDir = gradle.extra["vaniaCoreDir"] as File
val versionSource = vaniaCoreDir.resolve("api/src/main/java/fr/samflix/vaniametrics/api/Version.java")
version = Regex("""VALEUR = "([^"]+)"""").find(versionSource.readText())?.groupValues?.get(1)
    ?: error("version illisible dans $versionSource")

dependencies {
    // Relié par le build composite au projet api/ du dépôt core.
    compileOnly("fr.samflix:vania-metrics-api")
    compileOnly(libs.bundles.paper)
    compileOnly(libs.phoenixcrates)
}

tasks.withType<JavaCompile>().configureEach {
    // --release 21 : le lobby vise Java 25, le proxy Java 21. Le plus petit commande.
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-path,-processing,-options", "-Werror"))
}

tasks.processResources {
    val v = version.toString()
    inputs.property("version", v)
    filesMatching("plugin.yml") { filter { it.replace("\${version}", v) } }
}

// LE NOM DU JAR VIENT DE « name: », pas de la classe d'entrée : la liste du dépôt
// serveur désigne les modules par leur nom de plugin. Une seule source, celle que
// Bukkit affiche.
val pluginYml = file("src/main/resources/plugin.yml")
val nomAffiche = Regex("""(?m)^name: VaniaMetrics-(\S+)""").find(pluginYml.readText())?.groupValues?.get(1)
    ?: error("$pluginYml : « name: » attendu sous la forme VaniaMetrics-<Nom>")

tasks.jar {
    archiveFileName = "VaniaMetrics-$nomAffiche-$version.jar"
}
