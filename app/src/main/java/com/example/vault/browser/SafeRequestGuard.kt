package com.example.vault.browser

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.math.ceil

data class SanitizedUrlResult(
    val originalUrl: String,
    val sanitizedUrl: String,
    val cleanOrigin: String,
    val strippedParamCount: Int,
    val isValidPublicHttpUrl: Boolean,
)

/**
 * Strips session tokens, OAuth fragments, and cross-site tracking parameters so only clean
 * public URLs are inspected or previewed.
 */
object UrlSanitizer {

    private val STRIPPED_QUERY_PARAMS = setOf(
        "fbclid",
        "gclid",
        "dclid",
        "yclid",
        "msclkid",
        "mc_cid",
        "mc_eid",
        "igshid",
        "igsh",
        "si",
        "s",
        "pp",
        "feature",
        "app",
        "ref",
        "ref_src",
        "ref_url",
        "share_id",
        "sender_device",
        "is_from_webapp",
        "token",
        "access_token",
        "auth",
        "session",
        "session_id",
        "sid",
        "sig",
        "signature",
        "expires",
        "x-amz-credential",
        "x-amz-signature",
        "x-amz-security-token",
    )

    fun sanitizePublicUrl(rawUrl: String): SanitizedUrlResult {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return SanitizedUrlResult(
                originalUrl = rawUrl,
                sanitizedUrl = "",
                cleanOrigin = "",
                strippedParamCount = 0,
                isValidPublicHttpUrl = false,
            )
        }

        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase().orEmpty()
        val host = parsed?.host.orEmpty()
        if (parsed == null || (scheme != "http" && scheme != "https") || host.isBlank()) {
            return SanitizedUrlResult(
                originalUrl = rawUrl,
                sanitizedUrl = "",
                cleanOrigin = "",
                strippedParamCount = 0,
                isValidPublicHttpUrl = false,
            )
        }

        val portPart = if (parsed.port > 0) ":${parsed.port}" else ""
        val cleanOrigin = "$scheme://$host$portPart"
        val builder = parsed.buildUpon().clearQuery().fragment(null)
        var strippedCount = if (!parsed.fragment.isNullOrBlank()) 1 else 0

        parsed.queryParameterNames.forEach { paramName ->
            val lower = paramName.lowercase()
            val shouldStrip = lower.startsWith("utm_") ||
                lower.startsWith("hsa_") ||
                lower in STRIPPED_QUERY_PARAMS
            if (shouldStrip) {
                strippedCount++
            } else {
                parsed.getQueryParameters(paramName).forEach { value ->
                    builder.appendQueryParameter(paramName, value)
                }
            }
        }

        val cleaned = builder.build().toString()
        return SanitizedUrlResult(
            originalUrl = rawUrl,
            sanitizedUrl = cleaned,
            cleanOrigin = cleanOrigin,
            strippedParamCount = strippedCount,
            isValidPublicHttpUrl = true,
        )
    }
}

data class RateLimiterUiStatus(
    val isInFlight: Boolean = false,
    val remainingCooldownSeconds: Int = 0,
    val isBackoffActive: Boolean = false,
    val consecutiveBlocks: Int = 0,
    val statusMessage: String? = null,
) {
    val canStartRequest: Boolean
        get() = !isInFlight && remainingCooldownSeconds <= 0
}

sealed interface RateLimitAcquireResult {
    data object Acquired : RateLimitAcquireResult
    data class Blocked(
        val reason: String,
        val remainingSeconds: Int,
        val isBackoff: Boolean,
    ) : RateLimitAcquireResult
}

/**
 * Enforces user-initiated-only requests with:
 * - Minimum 5-second cooldown between requests
 * - Strict single-flight lock (max 1 active request at a time)
 * - Automatic exponential backoff upon HTTP 429 / 403 responses
 */
class RequestRateLimiter(
    private val minCooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private val mutex = Mutex()
    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var countdownJob: Job? = null

    @Volatile
    private var nextAllowedAtMs: Long = 0L

    @Volatile
    private var consecutiveRateLimitHits: Int = 0

    @Volatile
    private var backoffActiveUntilMs: Long = 0L

    private val _status = MutableStateFlow(RateLimiterUiStatus())
    val status: StateFlow<RateLimiterUiStatus> = _status.asStateFlow()

    fun tryAcquire(): RateLimitAcquireResult {
        val now = System.currentTimeMillis()
        if (mutex.isLocked || _status.value.isInFlight) {
            return RateLimitAcquireResult.Blocked(
                reason = "يوجد طلب فحص قيد التنفيذ حالياً — انتظر اكتماله أولاً.",
                remainingSeconds = _status.value.remainingCooldownSeconds.coerceAtLeast(1),
                isBackoff = _status.value.isBackoffActive,
            )
        }
        if (now < nextAllowedAtMs) {
            val remainingSec = ceil((nextAllowedAtMs - now) / 1000.0).toInt().coerceAtLeast(1)
            val isBackoff = now < backoffActiveUntilMs
            val msg = if (isBackoff) {
                "تم تفعيل التهدئة التلقائية حمايةً من قيود الموقع — انتظر $remainingSec ثانية."
            } else {
                "فترة تهدئة لمنع الطلبات المتكررة — انتظر $remainingSec ثانية."
            }
            return RateLimitAcquireResult.Blocked(
                reason = msg,
                remainingSeconds = remainingSec,
                isBackoff = isBackoff,
            )
        }
        if (!mutex.tryLock()) {
            return RateLimitAcquireResult.Blocked(
                reason = "يوجد طلب نشط بالفعل.",
                remainingSeconds = 1,
                isBackoff = false,
            )
        }
        _status.value = _status.value.copy(
            isInFlight = true,
            statusMessage = null,
        )
        return RateLimitAcquireResult.Acquired
    }

    fun releaseAndArmCooldown(httpStatusCode: Int? = null) {
        val now = System.currentTimeMillis()
        val isRateLimited = httpStatusCode == 429 || httpStatusCode == 403
        val cooldownDurationMs = if (isRateLimited) {
            consecutiveRateLimitHits = (consecutiveRateLimitHits + 1).coerceAtMost(4)
            val backoff = (15_000L shl (consecutiveRateLimitHits - 1)).coerceAtMost(120_000L)
            backoffActiveUntilMs = now + backoff
            backoff
        } else {
            if (httpStatusCode != null && httpStatusCode in 200..399) {
                consecutiveRateLimitHits = 0
                backoffActiveUntilMs = 0L
            }
            minCooldownMs
        }

        nextAllowedAtMs = maxOf(nextAllowedAtMs, now + cooldownDurationMs)
        if (mutex.isLocked) {
            runCatching { mutex.unlock() }
        }
        startCountdownTicker()
    }

    fun recordHttpStatus(httpStatusCode: Int) {
        if (httpStatusCode == 429 || httpStatusCode == 403) {
            val now = System.currentTimeMillis()
            consecutiveRateLimitHits = (consecutiveRateLimitHits + 1).coerceAtMost(4)
            val backoff = (15_000L shl (consecutiveRateLimitHits - 1)).coerceAtMost(120_000L)
            backoffActiveUntilMs = now + backoff
            nextAllowedAtMs = maxOf(nextAllowedAtMs, backoffActiveUntilMs)
            startCountdownTicker()
        }
    }

    fun resetCooldownForTestsOnly() {
        countdownJob?.cancel()
        nextAllowedAtMs = 0L
        backoffActiveUntilMs = 0L
        consecutiveRateLimitHits = 0
        if (mutex.isLocked) runCatching { mutex.unlock() }
        _status.value = RateLimiterUiStatus()
    }

    private fun startCountdownTicker() {
        countdownJob?.cancel()
        countdownJob = tickerScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingMs = (nextAllowedAtMs - now).coerceAtLeast(0L)
                val remainingSec = ceil(remainingMs / 1000.0).toInt()
                val isBackoff = now < backoffActiveUntilMs
                val msg = when {
                    remainingSec <= 0 -> null
                    isBackoff -> "تهدئة تلقائية (HTTP 429/403): ${remainingSec}ث"
                    else -> "تهدئة الأمان: ${remainingSec}ث"
                }
                _status.value = RateLimiterUiStatus(
                    isInFlight = mutex.isLocked,
                    remainingCooldownSeconds = remainingSec,
                    isBackoffActive = isBackoff,
                    consecutiveBlocks = consecutiveRateLimitHits,
                    statusMessage = msg,
                )
                if (remainingMs <= 0L) break
                delay(500L)
            }
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5_000L
    }
}
