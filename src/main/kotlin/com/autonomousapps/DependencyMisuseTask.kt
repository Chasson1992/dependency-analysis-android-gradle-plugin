@file:Suppress("UnstableApiUsage")

package com.autonomousapps

import com.autonomousapps.internal.*
import kotlinx.html.*
import kotlinx.html.dom.create
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Produces a report of unused direct dependencies and used transitive dependencies.
 */
@CacheableTask
open class DependencyMisuseTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {

    init {
        group = "verification"
        description = "Produces a report of unused direct dependencies and used transitive dependencies"
    }

    /**
     * This is the "official" input for wiring task dependencies correctly, but is otherwise
     * unused.
     */
    @get:Classpath
    lateinit var artifactFiles: FileCollection

    /**
     * This is what the task actually uses as its input. I really only care about the [ResolutionResult].
     */
    @get:Internal
    val configurationName: Property<String> = objects.property(String::class.java)

    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    val declaredDependencies: RegularFileProperty = objects.fileProperty()

    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    val usedClasses: RegularFileProperty = objects.fileProperty()

    @get:OutputFile
    val outputUnusedDependencies: RegularFileProperty = objects.fileProperty()

    @get:OutputFile
    val outputUsedTransitives: RegularFileProperty = objects.fileProperty()

    @get:OutputFile
    val outputHtml: RegularFileProperty = objects.fileProperty()

    @TaskAction
    fun action() {
        // Input
        val declaredDependenciesFile = declaredDependencies.get().asFile
        val usedClassesFile = usedClasses.get().asFile
        val root = project.configurations.getByName(configurationName.get()).incoming.resolutionResult.root

        // Output
        val outputUnusedDependenciesFile = outputUnusedDependencies.get().asFile
        val outputUsedTransitivesFile = outputUsedTransitives.get().asFile
        val outputHtmlFile = outputHtml.get().asFile

        // Cleanup prior execution
        outputUnusedDependenciesFile.delete()
        outputUsedTransitivesFile.delete()
        outputHtmlFile.delete()

        val declaredLibraries = declaredDependenciesFile.readText().fromJsonList<Component>()
        val usedClasses = usedClassesFile.readLines()

        val unusedLibs = mutableListOf<String>()
        val usedTransitives = mutableSetOf<TransitiveDependency>()
        val usedDirectClasses = mutableSetOf<String>()
        declaredLibraries
            // Exclude dependencies with zero class files (such as androidx.legacy:legacy-support-v4)
            .filterNot { it.classes.isEmpty() }
            .forEach { lib ->
                var count = 0
                val classes = sortedSetOf<String>()

                lib.classes.forEach { declClass ->
                    // Looking for unused direct dependencies
                    if (!lib.isTransitive) {
                        if (!usedClasses.contains(declClass)) {
                            // Unused class
                            count++
                        } else {
                            // Used class
                            usedDirectClasses.add(declClass)
                        }
                    }

                    // Looking for used transitive dependencies
                    if (lib.isTransitive
                        // Black-listing this one.
                        && lib.identifier != "org.jetbrains.kotlin:kotlin-stdlib"
                        // Assume all these come from android.jar
                        && !declClass.startsWith("android.")
                        && usedClasses.contains(declClass)
                        // Not in the set of used direct dependencies
                        && !usedDirectClasses.contains(declClass)
                    ) {
                        classes.add(declClass)
                    }
                }
                if (count == lib.classes.size
                    // Blacklisting all of these
                    && !lib.identifier.startsWith("org.jetbrains.kotlin:kotlin-stdlib")
                ) {
                    unusedLibs.add(lib.identifier)
                }
                if (classes.isNotEmpty()) {
                    usedTransitives.add(TransitiveDependency(lib.identifier, classes))
                }
            }

        // Connect used-transitives to direct dependencies
        val unusedDepsWithTransitives = unusedLibs.mapNotNull { unusedLib ->
            root.dependencies.filterIsInstance<ResolvedDependencyResult>().find {
                unusedLib == it.selected.id.asString()
            }?.let {
                relate(it, UnusedDirectDependency(unusedLib, mutableSetOf()), usedTransitives.toSet())
            }
        }.toSet()

        // This is for printing to the console. A simplified view
        val completelyUnusedDeps = unusedDepsWithTransitives
            .filter { it.usedTransitiveDependencies.isEmpty() }
            .map { it.identifier }
            .toSortedSet()

        // Reports
        outputUnusedDependenciesFile.writeText(unusedDepsWithTransitives.toJson())
        outputUsedTransitivesFile.writeText(usedTransitives.toJson())
        logger.quiet("""===Misused Dependencies===
            |This report contains directly declared dependencies (in your `dependencies {}` block) which are either:
            | 1. Completely unused; or
            | 2. Unused except for transitive dependencies which _are_ used.
            |    These used-transitives are either declared on the `compile` or `api` configurations (or the Maven equivalent)
            |    of their respective projects. In some cases, it makes sense to simply use these transitive dependencies. In 
            |    others, it may be best to directly declare these transitive dependencies in your build script.
            |     
            |Unused dependencies report:          ${outputUnusedDependenciesFile.path}
            |Used-transitive dependencies report: ${outputUsedTransitivesFile.path}
            |
            |Completely unused dependencies:
            |${if (completelyUnusedDeps.isEmpty()) "none" else completelyUnusedDeps.joinToString(separator = "\n- ", prefix = "- ")}
        """.trimMargin())

        writeHtmlReport(completelyUnusedDeps, unusedDepsWithTransitives, usedTransitives, outputHtmlFile)
    }
}

private fun relate(
    resolvedDependency: ResolvedDependencyResult,
    unusedDep: UnusedDirectDependency,
    transitives: Set<TransitiveDependency>
): UnusedDirectDependency {
    resolvedDependency.selected.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach {
        val identifier = it.selected.id.asString()
        if (transitives.map { it.identifier }.contains(identifier)) {
            unusedDep.usedTransitiveDependencies.add(identifier)
        }
        relate(it, unusedDep, transitives)
    }
    return unusedDep
}

private fun writeHtmlReport(
    completelyUnusedDeps: Set<String>,
    unusedDepsWithTransitives: Set<UnusedDirectDependency>,
    usedTransitives: Set<TransitiveDependency>,
    outputHtmlFile: File
) {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
    document.create.html {
        head { title("Misused Dependencies Report") }
        body {
            h1 { +"Completely unused direct dependencies" }
            table {
                tr {
                    td {}
                    td { strong { +"Identifier" } }
                }
                completelyUnusedDeps.forEachIndexed { i, unusedDep ->
                    tr {
                        td { +"${i + 1}" }
                        td { +unusedDep }
                    }
                }
            }

            h1 { +"Unused direct dependencies" }
            table {
                unusedDepsWithTransitives.forEachIndexed { i, unusedDep ->
                    tr {
                        // TODO is valign="bottom" supported?
                        td { +"${i + 1}" }
                        td {
                            strong { +unusedDep.identifier }
                            if (unusedDep.usedTransitiveDependencies.isNotEmpty()) {
                                p {
                                    em { +"Used transitives" }
                                    ul {
                                        unusedDep.usedTransitiveDependencies.forEach {
                                            li { +it }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            h1 { +"Used transitive dependencies" }
            table {
                tr {
                    td {}
                    td { strong { +"Identifier" } }
                }
                usedTransitives.forEachIndexed { i, trans ->
                    tr {
                        td { +"${i + 1}" }
                        td {
                            p { strong { +trans.identifier } }
                            p {
                                em { +"Used transitives" }
                                ul {
                                    trans.usedTransitiveClasses.forEach {
                                        li { +it }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.writeToFile(outputHtmlFile)
}
