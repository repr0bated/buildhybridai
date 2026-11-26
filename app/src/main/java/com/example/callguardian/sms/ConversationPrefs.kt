package com.example.callguardian.sms

import android.content.Context

/** Local pin / archive / mute flags for conversations. Not synced; safe without ROLE_SMS. */
class ConversationPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cg_conversations", Context.MODE_PRIVATE)

    fun pinned(): Set<String> = prefs.getStringSet(PINNED, emptySet()) ?: emptySet()
    fun archived(): Set<String> = prefs.getStringSet(ARCHIVED, emptySet()) ?: emptySet()
    fun muted(): Set<String> = prefs.getStringSet(MUTED, emptySet()) ?: emptySet()

    fun isPinned(key: String) = key in pinned()
    fun isArchived(key: String) = key in archived()
    fun isMuted(key: String) = key in muted()

    fun togglePinned(key: String) = flip(PINNED, key)
    fun toggleArchived(key: String) = flip(ARCHIVED, key)
    fun toggleMuted(key: String) = flip(MUTED, key)

    fun draft(key: String): String = prefs.getString(DRAFT + key, "").orEmpty()
    fun saveDraft(key: String, body: String) {
        prefs.edit().apply {
            if (body.isBlank()) remove(DRAFT + key) else putString(DRAFT + key, body)
        }.apply()
    }

    private fun flip(setKey: String, value: String) {
        val next = (prefs.getStringSet(setKey, emptySet()) ?: emptySet()).toMutableSet()
        if (!next.add(value)) next.remove(value)
        prefs.edit().putStringSet(setKey, next).apply()
    }

    companion object {
        private const val PINNED = "pinned"
        private const val ARCHIVED = "archived"
        private const val MUTED = "muted"
        private const val DRAFT = "draft:"
    }
}
