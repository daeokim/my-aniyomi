package mihon.buildlogic

// Personal fork: Fold8 replacement app, adaptive catalog and two-page reader changes.

import org.gradle.api.Project
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Personal fork: source archives can build without a git checkout.
fun Project.getCommitCount(): String {
    return runCatching { runCommand("git rev-list --count HEAD") }.getOrDefault("1")
}

fun Project.getGitSha(): String {
    return runCatching { runCommand("git rev-parse --short HEAD") }.getOrDefault("personal-fold8")
}

private val BUILD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

/**
 * @param useLastCommitTime If `true`, the build time is based on the timestamp of the last Git commit;
 *                          otherwise, the current time is used. Both are in UTC.
 * @return A formatted string representing the build time. The format used is defined by [BUILD_TIME_FORMATTER].
 */
fun Project.getBuildTime(useLastCommitTime: Boolean): String {
    return if (useLastCommitTime) {
        val epoch = runCatching { runCommand("git log -1 --format=%ct").toLong() }
            .getOrElse { Instant.now().epochSecond }
        Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
    } else {
        LocalDateTime.now(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
    }
}

private fun Project.runCommand(command: String): String {
    return providers.exec {
        commandLine = command.split(" ")
    }
        .standardOutput
        .asText
        .get()
        .trim()
}
