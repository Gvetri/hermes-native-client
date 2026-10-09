package org.hermesnative.client.buildlogic

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The committed detekt baseline and its companion ledger, read as evidence by `detektVerify`.
 *
 * The baseline records every finding that was absorbed when detekt was adopted; the ledger records
 * that number in a single integer, so a change cannot absorb a new finding without the ledger
 * disagreeing, and the ledger may only shrink against the base reference.
 */
object DetektBaselineEvidence {
    const val BASELINE_PATH = "config/detekt/baseline.xml"
    const val LEDGER_PATH = "config/detekt/baseline-ledger.txt"

    /** Counts the baselined findings; a missing or ill-formed baseline fails the verification. */
    fun baselineEntryCount(baselineFile: File): Int {
        check(baselineFile.isFile) {
            "Detekt verification found no committed baseline at ${baselineFile.path}."
        }
        val document =
            try {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(baselineFile)
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Detekt verification found an unreadable baseline at ${baselineFile.path}: ${error.message}",
                    error,
                )
            }
        check(document.documentElement.nodeName == "SmellBaseline") {
            "Detekt verification found ${baselineFile.path} without a SmellBaseline root element."
        }
        val entries = document.getElementsByTagName("ID")
        val blank = (0 until entries.length).count { index -> entries.item(index).textContent.isBlank() }
        check(blank == 0) {
            "Detekt verification found $blank blank baseline entr(ies) at ${baselineFile.path}."
        }
        return entries.length
    }

    /** Reads the single-integer ledger; a missing or malformed ledger fails the verification. */
    fun ledgerCount(ledgerFile: File): Int {
        check(ledgerFile.isFile) {
            "Detekt verification found no baseline ledger at ${ledgerFile.path}."
        }
        val text = ledgerFile.readText().trim()
        check(text.matches(Regex("[0-9]+"))) {
            "Detekt verification found a malformed ledger at ${ledgerFile.path}: '$text'."
        }
        return text.toInt()
    }

    /**
     * The source-set-specific baseline files the detekt plugin would prefer over the committed
     * baseline. Their presence would let a source set skip findings the ledger counts, so the
     * verification rejects them.
     */
    fun shadowingBaselines(configDirectory: File): List<String> =
        configDirectory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.name.matches(Regex("baseline-[a-z-]+\\.xml")) }
            .map { file -> file.name }
            .sorted()

    /**
     * The baseline entry count recorded at [baseRef], or `null` when that reference holds no
     * ledger yet, because the adoption change introduces it. A reference that cannot be read in
     * this checkout fails closed instead of skipping the ratchet.
     */
    fun baseLedger(
        repositoryRoot: File,
        baseRef: String,
    ): Int? {
        val commit = runGit(repositoryRoot, listOf("rev-parse", "--verify", "--quiet", "$baseRef^{commit}"))
        check(commit.exitCode == 0) {
            "Detekt verification cannot check the shrink-only baseline ratchet: the base reference " +
                "'$baseRef' is not available in this checkout; fetch it before running the gate."
        }
        val objectPath = "$baseRef:$LEDGER_PATH"
        val listed = runGit(repositoryRoot, listOf("ls-tree", "--name-only", baseRef, "--", LEDGER_PATH))
        check(listed.exitCode == 0) {
            "Detekt verification could not read '$baseRef' for the shrink-only baseline ratchet."
        }
        if (listed.output.isBlank()) {
            return null
        }
        val content = runGit(repositoryRoot, listOf("show", objectPath))
        check(content.exitCode == 0) {
            "Detekt verification could not read '$objectPath' at $baseRef."
        }
        return ledgerValue(content.output, objectPath)
    }

    private fun ledgerValue(
        text: String,
        objectPath: String,
    ): Int {
        val trimmed = text.trim()
        check(trimmed.matches(Regex("[0-9]+"))) {
            "Detekt verification found a malformed ledger at '$objectPath': '$trimmed'."
        }
        return trimmed.toInt()
    }

    private data class GitResult(
        val exitCode: Int,
        val output: String,
    )

    private fun runGit(
        repositoryRoot: File,
        arguments: List<String>,
    ): GitResult {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repositoryRoot)
                .redirectErrorStream(true)
                .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return GitResult(process.waitFor(), output)
    }
}
