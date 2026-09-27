package com.yighy.pantograph.data

import com.yighy.pantograph.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Release versions as the tags on GitHub spell them - "v0.18.0" - compared number by number.
 * As text, "0.9.1" would sort after "0.18.0".
 */
object ReleaseVersion {

    /** The numbers of [tag], or null for anything that is not a plain dotted version. */
    fun parse(tag: String): List<Int>? {
        val core = tag.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        return core.split('.').map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    }

    /**
     * Whether [candidate] is a later release than [installed]. Missing trailing numbers count as
     * zero, so "0.19" and "0.19.0" are the same release. Anything unreadable on either side is
     * never newer: a notice for a version that cannot be read is not one worth showing.
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(installed) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The release's page. Built from the tag rather than taken from the response, so the one
     * link the app ever opens from this points at this repository whatever came back.
     */
    fun pageUrl(tag: String): String = "$REPOSITORY_URL/releases/tag/$tag"

    const val REPOSITORY_URL = "https://github.com/yighy/Pantograph"
}

/**
 * Asks GitHub for the latest published release, at most once a day, and remembers the answer.
 *
 * Pre-releases are left out, as GitHub's own "latest release" leaves them out: a notice offers
 * what has been published as ready. Until one has been, the answer is that there is none, which
 * reads as being up to date.
 *
 * The answer is kept in preferences rather than returned to whoever asked, so the notice on the
 * home screen survives restarts without asking again, and goes away by itself once the installed
 * version catches up. Only the tag is asked for and kept: nothing is downloaded or installed -
 * the notice links to the release page, and installing stays the user's to do.
 */
class UpdateChecker(
    private val preferenceManager: PreferenceManager,
    private val installedVersion: String = BuildConfig.VERSION_NAME
) {
    sealed interface Result {
        data class Available(val tag: String) : Result
        data object UpToDate : Result
        data object Failed : Result
    }

    /** What GitHub said, before it is compared with what is installed. */
    private sealed interface Answer {
        data class Latest(val tag: String) : Answer
        /** No published release that is not a draft or a pre-release. */
        data object NoRelease : Answer
        data object Unreachable : Answer
    }

    /** Checks if checking is on and a day has passed since the last answer. */
    suspend fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (!preferenceManager.checkForUpdates.first()) return
        if (now - preferenceManager.lastUpdateCheck() < CHECK_INTERVAL_MS) return
        check(now)
    }

    /**
     * Asks now. A failed attempt is not recorded as a check, so the next opening of the app
     * tries again rather than waiting out a day on a question that was never answered.
     */
    suspend fun check(now: Long = System.currentTimeMillis()): Result =
        when (val answer = withContext(Dispatchers.IO) { fetchLatest() }) {
            Answer.Unreachable -> Result.Failed
            Answer.NoRelease -> {
                preferenceManager.recordLatestRelease(null, now)
                Result.UpToDate
            }
            is Answer.Latest -> {
                preferenceManager.recordLatestRelease(answer.tag, now)
                if (ReleaseVersion.isNewer(answer.tag, installedVersion)) Result.Available(answer.tag)
                else Result.UpToDate
            }
        }

    private fun fetchLatest(): Answer {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub turns away requests that do not say who is asking.
            connection.setRequestProperty("User-Agent", "Pantograph/$installedVersion")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val tag = Json.parseToJsonElement(body).jsonObject["tag_name"]?.jsonPrimitive?.contentOrNull
                    // Only a tag that reads as a version is kept; it ends up in a link, see pageUrl.
                    tag?.takeIf { ReleaseVersion.parse(it) != null }?.let { Answer.Latest(it) } ?: Answer.Unreachable
                }
                // What GitHub answers when every release is a draft or a pre-release.
                HttpURLConnection.HTTP_NOT_FOUND -> Answer.NoRelease
                else -> Answer.Unreachable
            }
        } catch (e: Exception) {
            Answer.Unreachable
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        // "latest" leaves out drafts and pre-releases.
        const val LATEST_RELEASE_API = "https://api.github.com/repos/yighy/Pantograph/releases/latest"
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val TIMEOUT_MS = 10_000
    }
}
