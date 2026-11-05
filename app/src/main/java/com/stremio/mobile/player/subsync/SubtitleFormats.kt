package com.stremio.mobile.player.subsync

import androidx.media3.common.MimeTypes

/** Subtitle format detection by URL extension, falling back to content sniffing. */
object SubtitleFormats {
    fun mimeFor(url: String, head: ByteArray? = null): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        when {
            path.endsWith(".vtt") -> return MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> return MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".dfxp") -> return MimeTypes.APPLICATION_TTML
            path.endsWith(".srt") -> return MimeTypes.APPLICATION_SUBRIP
        }
        if (head != null) sniff(head)?.let { return it }
        return MimeTypes.APPLICATION_SUBRIP
    }

    fun sniff(head: ByteArray): String? {
        val text = String(head, 0, minOf(head.size, 512), Charsets.UTF_8).trimStart('﻿', ' ', '\r', '\n', '\t')
        return when {
            text.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
            text.startsWith("[Script Info]", ignoreCase = true) -> MimeTypes.TEXT_SSA
            text.startsWith("<?xml") || text.startsWith("<tt") -> MimeTypes.APPLICATION_TTML
            else -> null
        }
    }
}
