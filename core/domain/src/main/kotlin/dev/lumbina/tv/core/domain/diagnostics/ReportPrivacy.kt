package dev.lumbina.tv.core.domain.diagnostics

private val QUOTED = Regex("\"[^\"]*\"")
private val LINK = Regex("""\b(https?)://([^/\s"?#:]+(?::\d+)?)[^\s"]*?(?=[.,:;)]*(?:[\s"]|$))""")

/**
 * A log line as it may leave the device in a report: the log keeps what was said and opened for
 * whoever reads it on the device itself, while a report goes to other people. Quoted text
 * (recognized phrases, their readings, typed text) is left as its length, links as their host.
 */
fun privateLogLine(line: String): String = line
    .replace(QUOTED) { "\"…${it.value.length - 2}\"" }
    .replace(LINK) { "${it.groupValues[1]}://${it.groupValues[2]}/…" }
