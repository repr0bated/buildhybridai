package com.example.callguardian.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.callguardian.data.db.AiStatus
import com.example.callguardian.data.db.ScreeningEventEntity
import com.example.callguardian.model.GuardianMethod
import com.example.callguardian.model.guardianMethodFromAlias
import com.example.callguardian.model.RiskLevel
import com.example.callguardian.model.ScamCategory
import com.example.callguardian.model.VerdictSource
import com.example.callguardian.model.label
import com.example.callguardian.ui.theme.Brand
import com.example.callguardian.ui.theme.color
import java.text.DateFormat
import java.util.Date

@Composable
fun Pill(text: String, color: Color, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun RiskPill(risk: RiskLevel) = Pill(risk.label(), risk.color())

@Composable
fun SourcePill(source: VerdictSource, model: String? = null) = when (source) {
    VerdictSource.ON_DEVICE -> Pill("On-device" + (model?.let { " · ${prettyModel(it)}" } ?: ""), Brand.Device, Icons.Default.Smartphone)
    VerdictSource.IN_CLOUD -> Pill("Cloud" + (model?.let { " · $it" } ?: ""), Brand.Cloud, Icons.Default.Cloud)
    VerdictSource.RULES -> Pill("Rules", Brand.Rules, Icons.Default.Rule)
    VerdictSource.USER -> Pill("You", Brand.Indigo, Icons.Default.Person)
}

fun prettyModel(name: String): String = when {
    name.contains("v4-fast", true) -> "Gemini Nano 4 Fast"
    name.contains("v4-full", true) -> "Gemini Nano 4"
    name.contains("nano", true) -> name
    else -> name
}

/** Glass card: translucent fill over the grid background, hairline gradient border (lime → transparent). */
@Composable
fun SectionCard(modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Brand.Glass2.copy(alpha = 0.92f), Brand.Glass.copy(alpha = 0.82f))))
            .border(1.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent.copy(alpha = 0.45f), Brand.Line, Brand.Line.copy(alpha = 0.4f))), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** Section headings read like terminal prompts: "// title". */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text("// " + text.lowercase(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 4.dp, bottom = 2.dp))

/** Replaces the stock M3 top app bar: a terminal-style prompt with a blinking cursor and a lime rule underneath. */
@Composable
fun GuardianTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    onSearch: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "cursor")
    val a by blink.animateFloat(1f, 0f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.LinearEasing),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxWidth().background(Brand.Void).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).androidx_minHeight(52.dp), verticalAlignment = Alignment.CenterVertically) {
            navigationIcon()
            Spacer(Modifier.width(8.dp))
            Text(">", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.headlineSmall,
                    androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        title()
                        Box(Modifier.padding(start = 3.dp).size(width = 10.dp, height = 20.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = a)))
                    }
                }
            }
            if (onSearch != null) androidx.compose.material3.IconButton(onClick = onSearch) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Search, "Search")
            }
            actions()
        }
        Box(Modifier.fillMaxWidth().size(height = 1.dp, width = 1.dp).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Brand.Lime.copy(alpha = 0.7f), Brand.Line, Color.Transparent))))
    }
}

private fun Modifier.androidx_minHeight(h: androidx.compose.ui.unit.Dp) = this.heightIn(min = h)

/** Animated verdict ring: sweeps to [fraction] in the threat colour, with a slow pulse when the risk is high. */
@Composable
fun VerdictRing(fraction: Float, color: Color, label: String, modifier: Modifier = Modifier.size(76.dp)) {
    val sweep by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f),
        androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "sweep")
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(0.15f, 0.45f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1400), androidx.compose.animation.core.RepeatMode.Reverse), label = "glow")
    Box(modifier.androidx_semantics(label), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val sz = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(Brand.Line, 0f, 360f, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(color.copy(alpha = glow), -90f, 360f * sweep, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 2.2f))
            drawArc(color, -90f, 360f * sweep, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text("${(sweep * 100).toInt()}", style = MaterialTheme.typography.titleLarge, color = color)
    }
}

private fun Modifier.androidx_semantics(label: String) = this.semantics { contentDescription = label }

@Composable
fun EventRow(e: ScreeningEventEntity, onClick: () -> Unit) {
    val risk = runCatching { RiskLevel.valueOf(e.risk) }.getOrDefault(RiskLevel.SAFE)
    val source = runCatching { VerdictSource.valueOf(e.decidedBy) }.getOrDefault(VerdictSource.RULES)
    val category = runCatching { ScamCategory.valueOf(e.category) }.getOrDefault(ScamCategory.NONE)
    val action = guardianMethodFromAlias(e.finalAction)
    val hidden = com.example.callguardian.privacy.LocalPrivacy.current.event(e)
    com.example.callguardian.privacy.PrivateGate(hidden = hidden) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .background(Brand.Glass.copy(alpha = 0.85f)).border(1.dp, risk.color().copy(alpha = if (risk >= RiskLevel.MEDIUM) 0.55f else 0.18f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(risk.color().copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(if (e.channel == "CALL") Icons.Default.Call else Icons.Default.Sms, null, tint = risk.color())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.displayName ?: e.number ?: "Hidden number", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (e.isTest) { Spacer(Modifier.width(6.dp)); Pill("Test", MaterialTheme.colorScheme.onSurfaceVariant) }
                if (LocalOwned.current.forSender(e.number) != null) { Spacer(Modifier.width(6.dp)); OwnedPill() }
            }
            Text(
                e.body?.replace('\n', ' ') ?: (e.lookupSummary ?: "Incoming call"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RiskPill(risk)
                when (e.aiStatus) {
                    AiStatus.PENDING, AiStatus.RUNNING -> Pill("Analyzing", Brand.Device)
                    else -> SourcePill(source)
                }
                if (category != ScamCategory.NONE) Text(category.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(relativeTime(e.receivedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val tag = when {
                e.userAction == "BLOCKED_BY_LIST" -> "Blocked"
                e.autoApplied && action == GuardianMethod.BLOCK_NUMBER -> "Blocked"
                e.autoApplied && action == GuardianMethod.REJECT_CALL -> "Stopped"
                e.quarantined -> "Quarantined"
                else -> null
            }
            if (tag != null) Text(tag,
                style = MaterialTheme.typography.labelSmall, color = risk.color())
        }
    }
    }
}

fun relativeTime(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "now"
        d < 3_600_000 -> "${d / 60_000}m"
        d < 86_400_000 -> "${d / 3_600_000}h"
        d < 7 * 86_400_000 -> "${d / 86_400_000}d"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
    }
}

fun fullTime(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))


val LocalOwned = androidx.compose.runtime.staticCompositionLocalOf { OwnedLookup(emptyList()) }

data class OwnedLookup(val accounts: List<com.example.callguardian.accounts.OwnedAccount>) {
    fun forSender(number: String?) = accounts.firstOrNull { it.matchesSender(number) }
}

@Composable
fun OwnedPill() = Pill("Owned", Brand.Lime)
