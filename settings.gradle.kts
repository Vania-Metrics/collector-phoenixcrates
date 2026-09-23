// =============================================================================
// L'API VIENT DU DÉPÔT GIT core, à la ref de gradle.properties — jamais d'un
// dossier voisin. Le dépôt est cloné dans .gradle/vania-core et inclus comme
// build composite : « fr.samflix:vania-metrics-api » est compilé depuis ses
// sources, à cette ref exacte.
//
// Surcharges, le temps d'un build :
//   ./gradlew build -PvaniaCore.ref=main          une autre ref de core
//   ./gradlew build -PvaniaCore.dir=../core       un core local (dev de l'API)
// =============================================================================
rootProject.name = "colecteur-phoenixcrates"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // LES INSTANTANÉS DATÉS (paper-api, spark-api) sont lus par un dépôt ivy, comme
        // des versions figées. Par le dépôt Maven, Gradle les traite en SNAPSHOT, et sa
        // vérification des empreintes plante en écrivant verification-metadata.xml
        // — voire écrit un fichier incomplet (gradle/gradle#32739, #26803, ouverts).
        // Le prix : aucune dépendance transitive. Le bundle « paper » du catalogue
        // les déclare.
        exclusiveContent {
            forRepository {
                ivy("https://repo.papermc.io/repository/maven-public/") {
                    name = "fige-paper-api"
                    patternLayout {
                        setM2compatible(true)
                        artifact("[organisation]/[module]/1.21.11-R0.1-SNAPSHOT/[module]-[revision].[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("io.papermc.paper", "paper-api") }
        }
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://api.modrinth.com/maven") {
            content { includeGroup("maven.modrinth") }
        }
    }
}

val vaniaCoreDir: File = providers.gradleProperty("vaniaCore.dir").orNull?.let { file(it) } ?: run {
    val url = providers.gradleProperty("vaniaCore.url").get()
    val ref = providers.gradleProperty("vaniaCore.ref").get()
    val dir = file(".gradle/vania-core")
    val refNotee = file(".gradle/vania-core.ref")

    fun git(vararg args: String): Pair<Boolean, String> {
        val r = providers.exec {
            commandLine("git", "-c", "advice.detachedHead=false", *args)
            isIgnoreExitValue = true
        }
        return (r.result.get().exitValue == 0) to r.standardError.asText.get().trim()
    }

    if (!dir.resolve(".git").exists()) {
        dir.deleteRecursively()
        val (ok, err) = git("clone", "--quiet", "--depth", "1", "--branch", ref, url, dir.path)
        if (!ok) error("clone impossible de $url @ $ref :\n$err")
    } else if (!gradle.startParameter.isOffline || refNotee.takeIf { it.exists() }?.readText() != ref) {
        // Un fetch à chaque build : c'est la seule façon de savoir où pointe la ref
        // AUJOURD'HUI, qu'elle soit tag ou branche. Sans réseau, on garde le clone
        // tant qu'il est à la bonne ref — sinon on refuse, plutôt que de compiler
        // en silence contre une autre version de l'API.
        git("-C", dir.path, "remote", "set-url", "origin", url)
        val (ok, err) = git("-C", dir.path, "fetch", "--quiet", "--depth", "1", "origin", ref)
        if (ok) {
            git("-C", dir.path, "checkout", "--quiet", "--detach", "FETCH_HEAD")
        } else if (refNotee.takeIf { it.exists() }?.readText() == ref) {
            logger.warn("vania-core : fetch impossible, le clone local à $ref est utilisé tel quel.\n$err")
        } else {
            error("fetch impossible de $url @ $ref :\n$err")
        }
    }
    refNotee.writeText(ref)
    dir
}

includeBuild(vaniaCoreDir)
gradle.extra["vaniaCoreDir"] = vaniaCoreDir
