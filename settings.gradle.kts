// The API comes from the core git repo, at the ref from gradle.properties — never
// from a sibling folder. The repo is cloned into .gradle/vania-core and included
// as a composite build: "fr.samflix:vania-metrics-api" is compiled from its
// sources, at that exact ref.
//
// Overrides, for the duration of a build:
//   ./gradlew build -PvaniaCore.ref=main          another core ref
//   ./gradlew build -PvaniaCore.dir=../core       a local core (API development)
rootProject.name = "collector-phoenixcrates"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // DATED SNAPSHOTS (paper-api, spark-api) are read through an ivy repo, as
        // fixed versions. Through the Maven repo, Gradle treats them as SNAPSHOT, and its
        // checksum verification fails while writing verification-metadata.xml
        // — or writes an incomplete file (gradle/gradle#32739, #26803, open issues).
        // The cost: no transitive dependencies. The catalog's "paper" bundle
        // declares them.
        exclusiveContent {
            forRepository {
                ivy("https://repo.papermc.io/repository/maven-public/") {
                    name = "pinned-paper-api"
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
    val refMarker = file(".gradle/vania-core.ref")

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
        if (!ok) error("could not clone $url @ $ref:\n$err")
    } else if (!gradle.startParameter.isOffline || refMarker.takeIf { it.exists() }?.readText() != ref) {
        // A fetch on every build: it's the only way to know where the ref points to
        // TODAY, whether it's a tag or a branch. Without network access, we keep the
        // clone as long as it's already at the right ref — otherwise we refuse, rather
        // than silently compiling against a different API version.
        git("-C", dir.path, "remote", "set-url", "origin", url)
        val (ok, err) = git("-C", dir.path, "fetch", "--quiet", "--depth", "1", "origin", ref)
        if (ok) {
            git("-C", dir.path, "checkout", "--quiet", "--detach", "FETCH_HEAD")
        } else if (refMarker.takeIf { it.exists() }?.readText() == ref) {
            logger.warn("vania-core: fetch failed, using the local clone at $ref as is.\n$err")
        } else {
            error("could not fetch $url @ $ref:\n$err")
        }
    }
    refMarker.writeText(ref)
    dir
}

includeBuild(vaniaCoreDir)
gradle.extra["vaniaCoreDir"] = vaniaCoreDir
