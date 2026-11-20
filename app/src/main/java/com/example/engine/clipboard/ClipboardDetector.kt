package com.example.engine.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.util.Patterns
import com.example.engine.model.PlatformBadge

data class ClipboardLink(
    val url: String,
    val platform: PlatformBadge,
)

class ClipboardDetector(private val context: Context) {

    fun readClipboardUrl(): ClipboardLink? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        if (!clipboard.hasPrimaryClip()) return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) return null

        for (i in 0 until clip.itemCount) {
            val rawText = clip.getItemAt(i)?.coerceToText(context)?.toString()?.trim()
            if (!rawText.isNullOrBlank()) {
                val extracted = extractFirstValidUrl(rawText)
                if (extracted != null) {
                    return ClipboardLink(
                        url = extracted,
                        platform = PlatformBadge.fromUrl(extracted),
                    )
                }
            }
        }
        return null
    }

    fun extractFirstValidUrl(input: String): String? {
        val matcher = Patterns.WEB_URL.matcher(input)
        while (matcher.find()) {
            val candidate = matcher.group()?.trim()?.trimEnd('.', ',', ')', ']') ?: continue
            val normalized = if (candidate.startsWith("http://", ignoreCase = true) ||
                candidate.startsWith("https://", ignoreCase = true)
            ) {
                candidate
            } else {
                "https://$candidate"
            }
            if (isSupportedVideoCandidate(normalized)) {
                return normalized
            }
        }
        return null
    }

    private fun isSupportedVideoCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        return lower.contains(".") && !lower.contains(" ")
    }
}
