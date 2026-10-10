package com.cloudframe.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal client for Degoo's unofficial web API (Degoo has no public API).
 * Mirrors what app.degoo.com does; see github.com/bernd-wechner/Degoo.
 */
class DegooApi(private val prefs: Prefs, private val credentials: CredentialStore) {

    open class DegooException(message: String) : IOException(message)
    class AuthException(message: String) : DegooException(message)

    data class Item(
        val id: String,
        val name: String,
        val category: Int,
        val url: String,
        val created: String,
        val inRecycleBin: Boolean,
    ) {
        val isFolder get() = category == CAT_DEVICE || category == CAT_FOLDER
        val isImage get() = category == CAT_IMAGE || IMAGE_EXTS.any { name.lowercase().endsWith(it) }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val signedIn get() = prefs.accessToken.isNotEmpty() || prefs.refreshToken.isNotEmpty()

    // ------------------------------------------------------------------ auth
    /** Sign in and save the password (encrypted) so the app can sign in again by itself later. */
    suspend fun login(email: String, password: String) = withContext(Dispatchers.IO) {
        signIn(email, password)
        credentials.save(email, password)
    }

    fun signOut() {
        prefs.signOut()
        credentials.clear()
    }

    private fun signIn(email: String, password: String) {
        val body = JSONObject()
            .put("GenerateToken", true)
            .put("Username", email)
            .put("Password", password)
        val rd = postRest(LOGIN_URL, body, "login")
        when {
            rd.optString("Token").isNotEmpty() ->
                prefs.setTokens(rd.getString("Token"), rd.optString("RefreshToken"))
            rd.optString("RefreshToken").isNotEmpty() -> {
                prefs.setTokens("", rd.getString("RefreshToken"))
                refresh()
            }
            else -> throw AuthException("Degoo did not return a session.")
        }
        prefs.email = email
    }

    fun useTokens(access: String, refresh: String) {
        prefs.setTokens(access.trim(), refresh.trim())
    }

    private fun refresh() {
        val token = if (prefs.refreshToken.isEmpty()) "" else try {
            postRest(TOKEN_URL, JSONObject().put("RefreshToken", prefs.refreshToken), "refresh").optString("AccessToken")
        } catch (e: AuthException) {
            ""
        }
        when {
            token.isNotEmpty() -> prefs.setTokens(token, prefs.refreshToken)
            autoSignIn() -> Unit
            else -> throw expired()
        }
    }

    /** Degoo ended the session: sign in again with the saved password, at most every 10 minutes. */
    @Synchronized
    private fun autoSignIn(): Boolean {
        val (email, password) = credentials.load() ?: return false
        val now = System.currentTimeMillis()
        if (now - lastAutoSignIn < AUTO_SIGN_IN_GAP_MS) return false
        lastAutoSignIn = now
        return try {
            signIn(email, password)
            true
        } catch (e: IOException) {
            false
        }
    }

    private var lastAutoSignIn = 0L

    private fun expired(): AuthException {
        prefs.sessionExpired = true
        return AuthException("Degoo session expired. Please sign in again.")
    }

    private fun postRest(url: String, body: JSONObject, what: String): JSONObject {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://app.degoo.com/")
            .header("Origin", "https://app.degoo.com")
            .header("x-api-authentication", "iNDNjhDZzYWMxQTNtM2Y0IWLxIjY00COzcDNtYGN2Y2Y1gzM")
            .header("x-version", "DegooWebClient/1.0:2022.11.11")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code in listOf(400, 401, 403)) {
                throw AuthException(
                    if (what == "login") "Degoo rejected the sign-in (HTTP ${r.code}). Check your email and password."
                    else "Session expired. Please sign in again."
                )
            }
            if (!r.isSuccessful) throw DegooException("Degoo $what failed (HTTP ${r.code})")
            return JSONObject(r.body!!.string())
        }
    }

    // --------------------------------------------------------------- graphql
    private suspend fun graphql(op: String, query: String, vars: JSONObject, retryAuth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            if (prefs.accessToken.isEmpty()) refresh()
            vars.put("Token", prefs.accessToken)
            val payload = JSONObject().put("operationName", op).put("variables", vars).put("query", query)
            var attempt = 0
            while (true) {
                val req = Request.Builder().url(GRAPHQL_URL)
                    .post(payload.toString().toRequestBody(JSON))
                    .header("x-api-key", API_KEY)
                    .build()
                val (code, text) = try {
                    http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
                } catch (e: IOException) {
                    if (attempt++ >= 3) throw DegooException("Can't reach Degoo: ${e.message}")
                    delay(1000L shl attempt); continue
                }
                if (code in listOf(429, 500, 502, 503, 504) && attempt++ < 3) {
                    delay(2000L shl attempt); continue
                }
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (code == 401 || (json != null && isAuthError(json))) {
                    if (retryAuth && prefs.refreshToken.isNotEmpty()) {
                        refresh()
                        return@withContext graphql(op, query, vars, retryAuth = false)
                    }
                    throw expired()
                }
                if (code !in 200..299 || json == null) throw DegooException("Degoo error (HTTP $code)")
                json.optJSONArray("errors")?.let { errs ->
                    if (errs.length() > 0) throw DegooException(
                        (0 until errs.length()).joinToString("; ") { errs.getJSONObject(it).optString("message") })
                }
                return@withContext json.getJSONObject("data")
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    suspend fun children(parentId: String): List<Item> {
        val q = "query GetFileChildren5(\$Token: String! \$ParentID: String \$AllParentIDs: [String] " +
            "\$Limit: Int! \$Order: Int! \$NextToken: String) { getFileChildren5(Token: \$Token " +
            "ParentID: \$ParentID AllParentIDs: \$AllParentIDs Limit: \$Limit Order: \$Order " +
            "NextToken: \$NextToken) { Items { $ITEM_FIELDS } NextToken } }"
        val items = mutableListOf<Item>()
        var next: String? = null
        do {
            val v = JSONObject().put("ParentID", parentId).put("Limit", 1000).put("Order", 3)
            if (next != null) v.put("NextToken", next)
            val data = graphql("GetFileChildren5", q, v).optJSONObject("getFileChildren5") ?: break
            data.optJSONArray("Items")?.let { items += parseItems(it) }
            next = data.optString("NextToken").takeIf { it.isNotEmpty() && it != "null" }
        } while (next != null)
        return items
    }

    suspend fun item(id: String): Item {
        val q = "query GetOverlay4(\$Token: String!, \$ID: IDType!) { getOverlay4(Token: \$Token, ID: \$ID) { $ITEM_FIELDS } }"
        val v = JSONObject().put("ID", JSONObject().put("FileID", id))
        return parseItem(graphql("GetOverlay4", q, v).getJSONObject("getOverlay4"))
    }

    suspend fun folders(parentId: String): List<Item> =
        children(parentId).filter { it.isFolder && !it.inRecycleBin }.sortedBy { it.name.lowercase() }

    /** All images under a folder, including sub-folders. */
    suspend fun allImages(folderId: String, limit: Int = 50_000, onProgress: (Int) -> Unit = {}): List<Item> {
        val out = mutableListOf<Item>()
        val queue = ArrayDeque(listOf(folderId))
        while (queue.isNotEmpty() && out.size < limit) {
            for (it in children(queue.removeFirst())) {
                if (it.inRecycleBin || it.category == CAT_RECYCLE) continue
                if (it.isFolder) queue.addLast(it.id) else if (it.isImage) out += it
            }
            onProgress(out.size)
        }
        return out
    }

    private fun parseItems(arr: JSONArray) = (0 until arr.length()).map { parseItem(arr.getJSONObject(it)) }

    private fun parseItem(o: JSONObject) = Item(
        id = o.optString("ID"),
        name = o.optString("Name"),
        category = o.optInt("Category"),
        url = o.optString("URL").takeIf { it != "null" } ?: "",
        created = o.optString("CreationTime").takeIf { it != "null" } ?: "",
        inRecycleBin = o.optBoolean("IsInRecycleBin"),
    )

    private fun isAuthError(json: JSONObject): Boolean {
        val errs = json.optJSONArray("errors") ?: return false
        return (0 until errs.length()).any {
            val m = errs.getJSONObject(it).optString("message").lowercase()
            "not authorized" in m || "unauthorized" in m || "invalid token" in m
        }
    }

    companion object {
        private const val GRAPHQL_URL = "https://production-appsync.degoo.com/graphql"
        private const val LOGIN_URL = "https://rest-api.degoo.com/login"
        private const val TOKEN_URL = "https://rest-api.degoo.com/access-token/v2"
        private const val API_KEY = "da2-vs6twz5vnjdavpqndtbzg3prra"
        private const val AUTO_SIGN_IN_GAP_MS = 10 * 60 * 1000L
        private const val BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val ITEM_FIELDS = "ID ParentID Name Category Size URL CreationTime IsInRecycleBin"
        private val JSON = "application/json".toMediaType()

        const val CAT_DEVICE = 1
        const val CAT_FOLDER = 2
        const val CAT_IMAGE = 3
        const val CAT_RECYCLE = 10
        // Formats Android can decode (HEIC needs Android 9+)
        private val IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif", ".bmp")
    }
}
