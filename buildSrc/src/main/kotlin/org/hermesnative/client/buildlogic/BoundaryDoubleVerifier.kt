package org.hermesnative.client.buildlogic

import java.io.File

/**
 * One Kotlin class, interface, or object declaration: its simple name, its declaration text up to
 * the body, and its body text when the declaration has a block body.
 */
internal data class KotlinTypeDeclaration(
    val kind: String,
    val name: String,
    val header: String,
    val body: String?,
) {
    /** The declared supertypes, or null when the declaration names none. */
    fun supertypes(): String? {
        var parenDepth = 0
        header.forEachIndexed { index, character ->
            when (character) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ':' -> if (parenDepth == 0) return header.substring(index + 1)
            }
        }
        return null
    }
}

internal object KotlinSourceScanner {
    private val declaration = Regex("""\b(class|interface|object)\s+([A-Z][A-Za-z0-9_]*)\b""")

    fun declarations(source: String): List<KotlinTypeDeclaration> =
        declaration.findAll(source).mapNotNull { match ->
            val bodyStart = bodyStart(source, match.range.last + 1)
            KotlinTypeDeclaration(
                kind = match.groupValues[1],
                name = match.groupValues[2],
                header = source.substring(match.range.first, bodyStart ?: endOfDeclaration(source, match.range.last + 1)),
                body = bodyStart?.let { bodyText(source, it) },
            )
        }.toList()

    private fun endOfDeclaration(source: String, from: Int): Int =
        source.indexOf('\n', from).let { if (it < 0) source.length else it }

    private fun bodyStart(source: String, from: Int): Int? {
        var parenDepth = 0
        var index = from
        while (index < source.length) {
            when (source[index]) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '{' -> if (parenDepth == 0) return index
                ';' -> return null
                '\n' -> if (parenDepth == 0) return null
            }
            index++
        }
        return null
    }

    private fun bodyText(source: String, start: Int): String? {
        var depth = 0
        var index = start
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
            index++
        }
        return null
    }
}

/**
 * Requires a deterministic in-repository test double for every declared datasource and repository
 * boundary. A boundary that loses its double, or keeps one that reads the wall clock or randomness,
 * fails the verification: the gate rejects both a mocking framework and a double whose behaviour
 * depends on its environment.
 */
object BoundaryDoubleVerifier {
    fun verify(
        repositoryRoot: File,
        requirements: List<BoundaryDoubleRequirement>,
        forbiddenReferences: List<String>,
        allowedReferences: List<DoubleReferenceAllowance>,
        moduleRoots: List<String>,
    ): String {
        check(requirements.isNotEmpty()) {
            "Boundary double verification has no declared datasource or repository boundary."
        }
        val mainSources = kotlinSources(repositoryRoot, moduleRoots, setOf("main"))
        val testSources = kotlinSources(repositoryRoot, moduleRoots, setOf("test", "androidTest"))
        check(mainSources.isNotEmpty()) {
            "Boundary double verification found no main Kotlin sources under ${moduleRoots.joinToString(", ")}."
        }
        check(testSources.isNotEmpty()) {
            "Boundary double verification found no test Kotlin sources under ${moduleRoots.joinToString(", ")}."
        }

        val mainDeclarations = mainSources.flatMap { file ->
            KotlinSourceScanner.declarations(file.readText()).map { file to it }
        }
        val testDeclarations = testSources.flatMap { file ->
            KotlinSourceScanner.declarations(file.readText()).map { file to it }
        }

        val violations = mutableListOf<String>()
        var doubleCount = 0
        requirements.forEach { requirement ->
            val boundaryName = requirement.boundaryType.substringAfterLast('.')
            val declaredInProduction =
                mainDeclarations.any { (_, declaration) -> declaration.name == boundaryName }
            if (!declaredInProduction) {
                violations +=
                    "declared ${requirement.kind} boundary ${requirement.boundaryType} has no production declaration"
                return@forEach
            }
            val doubles =
                testDeclarations.filter { (_, declaration) ->
                    declaration.kind != "interface" &&
                        declaration.name != boundaryName &&
                        declaration.supertypes()?.containsWord(boundaryName) == true
                }
            if (doubles.isEmpty()) {
                violations +=
                    "declared ${requirement.kind} boundary ${requirement.boundaryType} has no in-repository test double"
                return@forEach
            }
            doubles.forEach { (file, declaration) ->
                doubleCount++
                val body = declaration.body
                if (body == null) {
                    violations +=
                        "${file.relativeTo(repositoryRoot)}: ${declaration.name} implements " +
                        "${requirement.boundaryType} without a body to verify"
                } else {
                    val references =
                        forbiddenReferences.filter { reference ->
                            body.contains(reference) &&
                                allowedReferences.none { allowance ->
                                    allowance.doubleType == declaration.name && allowance.reference == reference
                                }
                        }
                    if (references.isNotEmpty()) {
                        violations +=
                            "${file.relativeTo(repositoryRoot)}: ${declaration.name} implements " +
                            "${requirement.boundaryType} with non-deterministic ${references.joinToString(", ")}"
                    }
                }
            }
        }
        allowedReferences.forEach { allowance ->
            val used =
                testDeclarations.any { (_, declaration) ->
                    declaration.name == allowance.doubleType &&
                        declaration.body?.contains(allowance.reference) == true
                }
            if (!used) {
                violations +=
                    "declared allowance for ${allowance.doubleType} / ${allowance.reference} is stale: no " +
                    "verified double uses it (${allowance.reason})"
            }
        }
        check(violations.isEmpty()) {
            "Boundary double violations:\n${violations.joinToString("\n")}"
        }
        return "Boundary double verification checked $doubleCount double(s) for " +
            "${requirements.size} declared datasource and repository boundary(ies), with " +
            "${allowedReferences.size} declared allowance(s)."
    }

    private fun String.containsWord(word: String): Boolean =
        Regex("""(?<![A-Za-z0-9_])${Regex.escape(word)}(?![A-Za-z0-9_])""").containsMatchIn(this)

    private fun kotlinSources(
        repositoryRoot: File,
        moduleRoots: List<String>,
        sourceSets: Set<String>,
    ): List<File> =
        moduleRoots.flatMap { moduleRoot ->
            repositoryRoot.resolve(moduleRoot)
                .walkTopDown()
                .onEnter { directory -> directory.name !in setOf("build", ".gradle", ".kotlin") }
                .filter { directory ->
                    directory.isDirectory && directory.name == "kotlin" &&
                        directory.parentFile?.name in sourceSets
                }
                .flatMap { sourceRoot ->
                    sourceRoot.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()
                }
                .toList()
        }
}
