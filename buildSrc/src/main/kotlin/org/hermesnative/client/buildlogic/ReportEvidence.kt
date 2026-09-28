package org.hermesnative.client.buildlogic

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Shared rules for reading a verification report as evidence: the report must parse, and it must be
 * at least as new as the compiled classes of the module it describes.
 */
internal fun parseXmlReport(report: File, label: String): Element =
    try {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report).documentElement
    } catch (exception: Exception) {
        throw IllegalStateException(
            "$label found an unreadable report at ${report.path}: ${exception.message}",
            exception,
        )
    }

/**
 * Requires a report to be at least as new as the compiled classes of its module. Gradle rewrites the
 * compiled output whenever source content changes, so this rejects a report that predates the code it
 * claims to measure, without treating a file that was merely touched or checked out again as a change.
 */
internal fun requireFreshReport(
    label: String,
    reportPath: String,
    report: File,
    moduleDirectory: File,
) {
    val newestClass =
        moduleDirectory.resolve("build/classes")
            .takeIf { directory -> directory.isDirectory }
            ?.walkTopDown()
            ?.filter { file -> file.isFile && file.extension == "class" }
            ?.maxByOrNull { file -> file.lastModified() }
    check(newestClass != null) {
        "$label found no compiled classes under ${moduleDirectory.path}/build/classes, so " +
            "$reportPath cannot be evidence for the current sources."
    }
    check(report.lastModified() >= newestClass.lastModified()) {
        "$label found a stale report at $reportPath: it is older than " +
            "${newestClass.relativeTo(moduleDirectory)}. Run ./gradlew coverageVerify mutationVerify " +
            "to regenerate its evidence."
    }
}

internal fun percentOf(covered: Int, total: Int): Double = if (total == 0) 0.0 else 100.0 * covered / total

internal fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/**
 * Requires the tasks that produce a report to be part of this invocation. A task excluded with `-x`
 * never executes, so a report it would have regenerated can carry a result the current tests no
 * longer produce; the evidence is then read as if it were current.
 */
fun requireTasksInInvocation(
    label: String,
    requiredTasks: List<String>,
    invocationTasks: Set<String>,
) {
    val missing = requiredTasks.filterNot { task -> task in invocationTasks }
    check(missing.isEmpty()) {
        "$label requires $missing to be part of this invocation, because their reports are the " +
            "evidence the verification reads."
    }
}
