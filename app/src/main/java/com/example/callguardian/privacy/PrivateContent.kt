package com.example.callguardian.privacy

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.ui.theme.Brand

data class PrivacyUiState(
    val marks: Set<String> = emptySet(),
    val unlocked: Boolean = false,
    val onUnlock: () -> Unit = {},
) {
    val hasMarks: Boolean get() = marks.isNotEmpty()
    fun event(e: ScreeningEventEntity): Boolean =
        PrivacyKeys.coversEvent(marks, e.id, e.number ?: e.displayName, e.channel == "CALL")
    fun address(address: String): Boolean = PrivacyKeys.coversAddress(marks, address)
    fun call(id: Long, number: String?): Boolean = PrivacyKeys.coversCall(marks, id, number)
}

val LocalPrivacy = staticCompositionLocalOf { PrivacyUiState() }

/**
 * Blurs [content] when it is marked private and the session is locked.
 * API 31+ uses [Modifier.blur]; older APIs use a solid scrim so the text cannot be read.
 */
@Composable
fun PrivateGate(
    hidden: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val privacy = LocalPrivacy.current
    if (!hidden || privacy.unlocked) {
        Box(modifier, content = content)
        return
    }
    val blurOk = Build.VERSION.SDK_INT >= 31
    Box(
        modifier
            .clickable(onClick = privacy.onUnlock)
            .semantics(mergeDescendants = true) { contentDescription = "Private item. Tap to unlock." },
    ) {
        Box(
            Modifier
                .then(if (blurOk) Modifier.blur(16.dp) else Modifier)
                .clearAndSetSemantics { },
            content = content,
        )
        if (blurOk) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center) {
                Text("Private · tap to unlock", style = MaterialTheme.typography.labelSmall, color = Brand.Ink)
            }
        } else {
            Box(Modifier.matchParentSize().background(Brand.Void), contentAlignment = Alignment.Center) {
                Text("Private · tap to unlock", style = MaterialTheme.typography.labelSmall, color = Brand.Ink)
            }
        }
    }
}
