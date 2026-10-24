package app.prashant.castbridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.prashant.castbridge.R
import app.prashant.castbridge.TvInfo

/** Where the session is: choosing a TV, waiting for Android's capture prompt, or casting. */
enum class Phase { Idle, Starting, Casting }

data class CastUiState(
    val phase: Phase = Phase.Idle,
    val onWifi: Boolean = true,
    val scanning: Boolean = false,
    val tvs: List<TvInfo> = emptyList(),
    /** Selected TV ip, or null for "I'll open CastBridge on the TV myself". */
    val selectedIp: String? = null,
    val audio: Boolean = true,
    val castingTo: String? = null,
    val viewers: Int = 0,
    val message: String = "",
    /** App version shown in the footer, e.g. "1.0.0". */
    val version: String = "",
)

@Composable
fun CastScreen(
    state: CastUiState,
    onSelect: (ip: String?) -> Unit,
    onRescan: () -> Unit,
    onAudioChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenWifiSettings: () -> Unit,
) {
    Scaffold(
        bottomBar = {
            BottomAction(state, onStart, onStop)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Header()
            AnimatedContent(
                targetState = state.phase == Phase.Casting,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "phase",
            ) { casting ->
                if (casting) CastingPanel(state) else SetupPanel(state, onSelect, onRescan, onAudioChange, onOpenWifiSettings)
            }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.padding(top = 28.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        // The launcher icon itself: its 108dp adaptive layers, shown at the 72dp a launcher shows.
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BrandBlue),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                modifier = Modifier.requiredSize(72.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("CastBridge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Show this phone on your Samsung TV",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Choosing a TV -------------------------------------------------------------------------

@Composable
private fun SetupPanel(
    state: CastUiState,
    onSelect: (String?) -> Unit,
    onRescan: () -> Unit,
    onAudioChange: (Boolean) -> Unit,
    onOpenWifiSettings: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Choose your TV",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.onWifi) {
                if (state.scanning) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.5.dp)
                } else {
                    IconButton(onClick = onRescan) { Icon(Icons.Default.Refresh, contentDescription = "Search again") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            !state.onWifi -> NoWifiCard(onOpenWifiSettings)
            state.tvs.isEmpty() -> SearchingCard()
            else -> Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.tvs.forEach { tv ->
                    ChoiceCard(
                        icon = R.drawable.ic_tv,
                        title = tv.name,
                        subtitle = tv.ip,
                        selected = state.selectedIp == tv.ip,
                        onClick = { onSelect(tv.ip) },
                    )
                }
                ChoiceCard(
                    icon = R.drawable.ic_open_tv_manually,
                    title = "I'll open CastBridge on the TV",
                    subtitle = "The TV app finds this phone by itself",
                    selected = state.selectedIp == null,
                    onClick = { onSelect(null) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        AudioRow(state.audio, onAudioChange)
        if (state.message.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            MessageCard(state.message, isError = true)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChoiceCard(icon: Int, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.primaryContainer else colors.surfaceContainer,
        ),
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(icon), contentDescription = null,
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun SearchingCard() {
    StatusCard {
        CircularProgressIndicator(Modifier.size(36.dp))
        Spacer(Modifier.height(16.dp))
        Text("Looking for Samsung TVs…", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Turn the TV on and make sure it's on the same Wi-Fi as this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoWifiCard(onOpenWifiSettings: () -> Unit) {
    StatusCard {
        Icon(painterResource(R.drawable.ic_wifi_off), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text("Connect to Wi-Fi", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Your phone and TV need to be on the same Wi-Fi network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenWifiSettings) { Text("Wi-Fi settings") }
    }
}

@Composable
private fun StatusCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun AudioRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_volume_up), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Play sound on the TV", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your phone stays silent while casting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun MessageCard(text: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (isError) colors.errorContainer else colors.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning, contentDescription = null,
                tint = if (isError) colors.onErrorContainer else colors.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text, style = MaterialTheme.typography.bodyMedium,
                color = if (isError) colors.onErrorContainer else colors.onSecondaryContainer,
            )
        }
    }
}

// --- Casting -------------------------------------------------------------------------------

@Composable
private fun CastingPanel(state: CastUiState) {
    val connected = state.viewers > 0
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        PulsingCastBadge(active = connected)
        Spacer(Modifier.height(28.dp))
        Text(
            if (connected) "Casting" else "Getting the TV ready",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            state.castingTo?.let { "to $it" } ?: "to CastBridge on your TV",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        StatusPill(connected)
        Spacer(Modifier.height(28.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoLine(
                    R.drawable.ic_volume_up,
                    if (state.audio) "Sound plays on the TV" else "Sound stays on the phone",
                )
                InfoLine(R.drawable.ic_cast_connected, "You can leave this app. Stop anytime from the notification.")
            }
        }
        if (state.message.isNotEmpty() && !connected) {
            Spacer(Modifier.height(16.dp))
            MessageCard(state.message, isError = false)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PulsingCastBadge(active: Boolean) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "scale",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(150.dp)
                .scale(scale)
                .alpha(if (active) 0.25f else 0.12f)
                .background(BrandBlue, CircleShape),
        )
        Box(
            Modifier
                .size(112.dp)
                .background(BrandBlue, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_cast_connected), contentDescription = null,
                tint = Color.White, modifier = Modifier.size(52.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(connected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (connected) colors.primaryContainer else colors.surfaceContainerHigh,
        shape = RoundedCornerShape(50),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (connected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            } else {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (connected) "TV connected" else "Waiting for the TV to connect…",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun InfoLine(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(icon), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Bottom action -------------------------------------------------------------------------

@Composable
private fun BottomAction(state: CastUiState, onStart: () -> Unit, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val modifier = Modifier.fillMaxWidth().height(56.dp)
            when (state.phase) {
                Phase.Casting -> Button(
                    onClick = onStop, modifier = modifier,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text("Stop casting", style = MaterialTheme.typography.titleMedium) }

                Phase.Starting -> Button(onClick = {}, enabled = false, modifier = modifier) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(12.dp))
                    Text("Starting…", style = MaterialTheme.typography.titleMedium)
                }

                Phase.Idle -> Button(onClick = onStart, enabled = state.onWifi, modifier = modifier) {
                    Icon(painterResource(R.drawable.ic_cast_connected), contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Start casting", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (state.version.isNotEmpty()) {
                Text(
                    "CastBridge v${state.version}",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

// --- Previews ------------------------------------------------------------------------------

private val sampleTvs = listOf(TvInfo("192.168.1.104", "Samsung Q60AA 75 TV"), TvInfo("192.168.1.120", "Bedroom TV"))

@Composable
private fun PreviewHost(state: CastUiState) {
    CastBridgeTheme(darkTheme = true) {
        CastScreen(state.copy(version = "1.0.0"), {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "TVs found", widthDp = 380, heightDp = 800)
@Composable
private fun PreviewFound() = PreviewHost(CastUiState(tvs = sampleTvs, selectedIp = "192.168.1.104"))

@Preview(name = "Searching", widthDp = 380, heightDp = 800)
@Composable
private fun PreviewSearching() = PreviewHost(CastUiState(scanning = true))

@Preview(name = "No Wi-Fi", widthDp = 380, heightDp = 800)
@Composable
private fun PreviewNoWifi() = PreviewHost(CastUiState(onWifi = false))

@Preview(name = "Starting", widthDp = 380, heightDp = 800)
@Composable
private fun PreviewStarting() = PreviewHost(CastUiState(phase = Phase.Starting, tvs = sampleTvs, selectedIp = "192.168.1.104"))

@Preview(name = "Casting, waiting for TV", widthDp = 380, heightDp = 800)
@Composable
private fun PreviewCastingWaiting() = PreviewHost(
    CastUiState(phase = Phase.Casting, castingTo = "Samsung Q60AA 75 TV", message = "Opening CastBridge on the TV…"),
)

@Preview(name = "Casting, connected", widthDp = 380, heightDp = 800)
@Composable
private fun PreviewCastingConnected() = PreviewHost(
    CastUiState(phase = Phase.Casting, castingTo = "Samsung Q60AA 75 TV", viewers = 1),
)
