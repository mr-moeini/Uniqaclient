package com.mrmoeini.smsforwarder

import org.json.JSONObject
import java.util.UUID
import java.util.regex.Pattern

/** Everything needed to call an HTTP API with the forwarded SMS. */
data class ApiConfig(
    val url: String = "",
    val method: String = "POST",          // POST, PUT, PATCH, GET
    val format: Int = FORMAT_TEXT,
    val bodyTemplate: String = "",
    val contentType: String = "application/json",
    val authType: Int = AUTH_NONE,
    val authUser: String = "",            // basic user / oauth client id
    val authSecret: String = "",          // basic password / token / api key / oauth client secret
    val authName: String = "",            // api key header or query parameter name
    val tokenUrl: String = "",            // oauth2 token endpoint
    val scope: String = "",               // oauth2 scope
    val headers: String = "",             // extra headers, one "Name: value" per line
    val timeoutSec: Int = 15,
    val insecureSsl: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("method", method)
        put("format", format)
        put("bodyTemplate", bodyTemplate)
        put("contentType", contentType)
        put("authType", authType)
        put("authUser", authUser)
        put("authSecret", authSecret)
        put("authName", authName)
        put("tokenUrl", tokenUrl)
        put("scope", scope)
        put("headers", headers)
        put("timeoutSec", timeoutSec)
        put("insecureSsl", insecureSsl)
    }

    companion object {
        const val FORMAT_TEXT = 0
        const val FORMAT_JSON = 1
        const val FORMAT_FORM = 2
        const val FORMAT_CUSTOM = 3

        const val AUTH_NONE = 0
        const val AUTH_BASIC = 1
        const val AUTH_BEARER = 2
        const val AUTH_KEY_HEADER = 3
        const val AUTH_KEY_QUERY = 4
        const val AUTH_OAUTH2 = 5

        val METHODS = arrayOf("POST", "PUT", "PATCH", "GET")

        fun fromJson(o: JSONObject): ApiConfig = ApiConfig(
            url = o.optString("url"),
            method = o.optString("method", "POST").ifBlank { "POST" },
            format = o.optInt("format", FORMAT_TEXT),
            bodyTemplate = o.optString("bodyTemplate"),
            contentType = o.optString("contentType", "application/json").ifBlank { "application/json" },
            authType = o.optInt("authType", AUTH_NONE),
            authUser = o.optString("authUser"),
            authSecret = o.optString("authSecret"),
            authName = o.optString("authName"),
            tokenUrl = o.optString("tokenUrl"),
            scope = o.optString("scope"),
            headers = o.optString("headers"),
            timeoutSec = o.optInt("timeoutSec", 15),
            insecureSsl = o.optBoolean("insecureSsl", false)
        )
    }
}

/**
 * A forwarding rule: when an SMS arrives from [sender] (a phone number or an alphanumeric
 * sender ID) and, optionally, contains [keyword], the text (or the part selected by [regex])
 * is forwarded - either as an SMS to [destNumber] or to an HTTP API ([api]).
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sender: String,
    val senderName: String = "",
    val keyword: String,
    val destNumber: String = "",
    val destName: String = "",
    val enabled: Boolean = true,
    val regex: String = "",
    val action: Int = ACTION_SMS,
    val api: ApiConfig = ApiConfig()
) {

    fun isConfigured(): Boolean =
        if (action == ACTION_API) api.url.isNotBlank() else destNumber.isNotBlank()

    fun matches(from: String, body: String): Boolean {
        if (!enabled) return false
        if (!senderMatches(from)) return false
        if (keyword.isNotBlank() && !body.contains(keyword.trim(), ignoreCase = true)) return false
        return true
    }

    /**
     * Text to forward: the whole message, or - when a regex is set - group 1 of the first match
     * (or the whole match if the pattern has no groups). Returns null if the regex does not match.
     * An invalid stored regex falls back to the whole message.
     */
    fun extract(body: String): String? {
        if (regex.isBlank()) return body
        return try {
            applyRegex(regex, body)
        } catch (e: Exception) {
            body
        }
    }

    private fun senderMatches(from: String): Boolean {
        val filter = sender.trim()
        if (filter.isEmpty()) return false
        val filterDigits = digitsOnly(filter)
        return if (looksNumeric(filter) && filterDigits.isNotEmpty()) {
            val fromDigits = digitsOnly(from)
            // Compare the last 10 digits so +98912..., 0912... and 98912... all match.
            fromDigits.isNotEmpty() && fromDigits.takeLast(10) == filterDigits.takeLast(10)
        } else {
            from.trim().equals(filter, ignoreCase = true)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("sender", sender)
        put("senderName", senderName)
        put("keyword", keyword)
        put("destNumber", destNumber)
        put("destName", destName)
        put("enabled", enabled)
        put("regex", regex)
        put("action", action)
        put("api", api.toJson())
    }

    companion object {
        const val ACTION_SMS = 0
        const val ACTION_API = 1

        /** Throws PatternSyntaxException if [pattern] is invalid. Null when nothing matches. */
        fun applyRegex(pattern: String, body: String): String? {
            val m = Pattern.compile(pattern).matcher(body)
            if (!m.find()) return null
            val g1 = if (m.groupCount() >= 1) m.group(1) else null
            return g1 ?: m.group()
        }

        /** Keeps only digits, converting Persian/Arabic-Indic digits to ASCII 0-9. */
        fun digitsOnly(s: String): String = buildString {
            for (c in s) {
                val d = Character.digit(c, 10)
                if (d >= 0) append('0' + d)
            }
        }

        /** Like [digitsOnly] but keeps a '+' sign. Used for phone numbers. */
        fun toAsciiNumber(s: String): String = buildString {
            for (c in s) {
                if (c == '+') {
                    append(c)
                } else {
                    val d = Character.digit(c, 10)
                    if (d >= 0) append('0' + d)
                }
            }
        }

        /** True for things like "+98 912 123", "09121234567", "10008"; false for "BankName". */
        fun looksNumeric(s: String): Boolean {
            val t = s.trim()
            return t.isNotEmpty() && t.all { Character.digit(it, 10) >= 0 || it in "+-() " }
        }

        /** Phone numbers are normalized to ASCII digits; sender IDs (text) are kept as typed. */
        fun cleanSender(s: String): String =
            if (looksNumeric(s)) toAsciiNumber(s) else s.trim()

        fun fromJson(o: JSONObject): Rule = Rule(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            sender = o.optString("sender"),
            senderName = o.optString("senderName"),
            keyword = o.optString("keyword"),
            destNumber = o.optString("destNumber"),
            destName = o.optString("destName"),
            enabled = o.optBoolean("enabled", true),
            regex = o.optString("regex"),
            action = o.optInt("action", ACTION_SMS),
            api = o.optJSONObject("api")?.let { ApiConfig.fromJson(it) } ?: ApiConfig()
        )
    }
}
