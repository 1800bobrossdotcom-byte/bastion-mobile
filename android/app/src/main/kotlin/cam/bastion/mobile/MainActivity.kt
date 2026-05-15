package cam.bastion.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cam.bastion.mobile.acoustic.AcousticShieldService
import cam.bastion.mobile.settings.Settings
import cam.bastion.mobile.theme.AMBER
import cam.bastion.mobile.theme.BORDER
import cam.bastion.mobile.theme.PHOSPHOR
import cam.bastion.mobile.theme.SURFACE
import kotlinx.coroutines.delay

private val MONO = FontFamily.Monospace
private val INK_DIM = Color.White.copy(alpha = 0.45f)
private val INK = Color.White.copy(alpha = 0.78f)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BastionApp() }
    }
}

private enum class Tab(val short: String, val long: String) {
    DNS("01", "dns"),
    SHIELD("02", "shield"),
    ABOUT("03", "about"),
}

@Composable
fun BastionApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.DNS) }
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            TopBar(tab)
            ServiceStatusStrip()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.DNS -> DnsScreen()
                    Tab.SHIELD -> ShieldScreen()
                    Tab.ABOUT -> AboutScreen()
                }
            }
            BottomTabs(tab) { tab = it }
        }
    }
}

/* ───────────────────────── chrome ───────────────────────── */

@Composable
private fun TopBar(tab: Tab) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("BASTION", color = PHOSPHOR, fontFamily = MONO, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("v0.3.0 :: ${tab.short} ${tab.long}", color = INK_DIM,
                fontFamily = MONO, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Pulse()
    }
    Divider(color = BORDER, thickness = 1.dp)
}

/**
 * Two-pill status row: DNS shows whether system Private DNS matches the
 * provider the user picked in this app; SHIELD shows the acoustic shield mode.
 * They are independent — DNS doesn't run any process; SHIELD runs an FGS.
 */
@Composable
private fun ServiceStatusStrip() {
    val ctx = LocalContext.current
    var dnsActive by remember { mutableStateOf(currentlyUsingSelectedProvider(ctx)) }
    var shieldMode by remember { mutableStateOf(AcousticShieldService.currentMode) }
    LaunchedEffect(Unit) {
        while (true) {
            dnsActive = currentlyUsingSelectedProvider(ctx)
            shieldMode = AcousticShieldService.currentMode
            delay(800)
        }
    }
    val shieldOn = shieldMode != AcousticShieldService.Mode.OFF
    Row(
        Modifier.fillMaxWidth().background(Color.Black)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPill("DNS", if (dnsActive) "ACTIVE" else "OFF", dnsActive, Modifier.weight(1f))
        StatusPill("SHIELD",
            if (shieldOn) shieldMode.name else "OFF",
            shieldOn, Modifier.weight(1f))
    }
    Divider(color = BORDER.copy(alpha = 0.6f), thickness = 1.dp)
}

@Composable
private fun StatusPill(label: String, value: String, on: Boolean, modifier: Modifier) {
    val color = if (on) PHOSPHOR else INK_DIM
    Row(
        modifier
            .background(if (on) PHOSPHOR.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(2.dp))
            .border(1.dp, if (on) PHOSPHOR.copy(alpha = 0.6f) else BORDER,
                RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = INK_DIM, fontFamily = MONO, fontSize = 9.sp,
            letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = color, fontFamily = MONO, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun Pulse() {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val a by infinite.animateFloat(
        0.3f, 1f, animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ), label = "a"
    )
    Box(Modifier.size(10.dp).clip(CircleShape).background(PHOSPHOR.copy(alpha = a)))
}

@Composable
private fun BottomTabs(active: Tab, onSelect: (Tab) -> Unit) {
    Divider(color = BORDER, thickness = 1.dp)
    Row(Modifier.fillMaxWidth().background(Color.Black).height(64.dp)) {
        Tab.entries.forEach { t ->
            val on = t == active
            val src = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (on) PHOSPHOR.copy(alpha = 0.10f) else Color.Transparent)
                    .clickable(interactionSource = src, indication = null) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(t.short, color = if (on) PHOSPHOR else INK_DIM,
                    fontFamily = MONO, fontSize = 9.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(2.dp))
                Text(t.long, color = if (on) PHOSPHOR else INK,
                    fontFamily = MONO, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/* ───────────────────────── DNS (Private DNS config wizard) ───────────────────────── */

/** Reads the system-wide Private DNS specifier (the hostname the user typed
 *  into Settings → Network → Private DNS). Returns null if Private DNS is off
 *  or set to "Automatic". Falls back gracefully on older Android. */
private fun systemPrivateDns(ctx: android.content.Context): String? = try {
    AndroidSettings.Global.getString(ctx.contentResolver, "private_dns_specifier")
        ?.takeIf { it.isNotBlank() }
} catch (_: Throwable) { null }

private fun systemPrivateDnsMode(ctx: android.content.Context): String = try {
    AndroidSettings.Global.getString(ctx.contentResolver, "private_dns_mode") ?: "off"
} catch (_: Throwable) { "off" }

private fun currentlyUsingSelectedProvider(ctx: android.content.Context): Boolean {
    val selected = Settings.provider(ctx)
    val sys = systemPrivateDns(ctx) ?: return false
    return sys.equals(selected.hostname, ignoreCase = true)
}

@Composable
private fun DnsScreen() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var provider by remember { mutableStateOf(Settings.provider(ctx)) }
    var sysHostname by remember { mutableStateOf(systemPrivateDns(ctx)) }
    var sysMode by remember { mutableStateOf(systemPrivateDnsMode(ctx)) }

    LaunchedEffect(Unit) {
        // Re-poll the system Private DNS state so the status reflects user
        // returning from Settings without a full app restart.
        while (true) {
            sysHostname = systemPrivateDns(ctx)
            sysMode = systemPrivateDnsMode(ctx)
            delay(1000)
        }
    }

    val active = sysHostname?.equals(provider.hostname, ignoreCase = true) == true

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Big status panel
        StatusPanel(
            active = active,
            provider = provider,
            sysHostname = sysHostname,
            sysMode = sysMode,
        )
        Spacer(Modifier.height(14.dp))

        // Primary action: open system Private DNS settings
        ActionButton("OPEN PRIVATE DNS SETTINGS") {
            try {
                ctx.startActivity(
                    Intent("android.settings.WIRELESS_SETTINGS")
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_SETTINGS)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ActionButton("COPY HOSTNAME", dim = !provider.available) {
            if (provider.available) {
                clipboard.setText(AnnotatedString(provider.hostname))
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("how to enable")
        Spacer(Modifier.height(8.dp))
        Steps(
            "1. Tap [OPEN PRIVATE DNS SETTINGS] above.",
            "2. Find \"Private DNS\" (under Network & Internet → Advanced on most phones).",
            "3. Choose \"Private DNS provider hostname\".",
            "4. Paste: ${provider.hostname}",
            "5. Tap Save. Return here — status will turn green.",
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("provider")
        Spacer(Modifier.height(8.dp))
        Settings.Provider.entries.forEach { p ->
            ProviderRow(p, p == provider) {
                if (p.available) {
                    provider = p
                    Settings.setProvider(ctx, p)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(20.dp))
        Disclaimer(
            "Private DNS is an OS-level setting — bastion is just a config wizard. " +
                "Once set, your phone uses the chosen DoT resolver for ALL apps, " +
                "including ones that pin DNS. We never see your queries; the resolver " +
                "operator does. Pick one whose privacy policy you trust."
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatusPanel(
    active: Boolean,
    provider: Settings.Provider,
    sysHostname: String?,
    sysMode: String,
) {
    val color = if (active) PHOSPHOR else AMBER
    Column(
        Modifier.fillMaxWidth()
            .background(SURFACE, RoundedCornerShape(4.dp))
            .border(1.dp, BORDER, RoundedCornerShape(4.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("STATUS", color = INK_DIM, fontFamily = MONO, fontSize = 10.sp,
                letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            if (active) Pulse()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (active) "FILTERING ACTIVE" else "NOT CONFIGURED",
            color = color, fontFamily = MONO, fontSize = 22.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(12.dp))
        KvLine("selected", provider.short)
        KvLine("system mode", sysMode)
        KvLine("system hostname", sysHostname ?: "(none)")
    }
}

@Composable
private fun KvLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k.uppercase(), color = INK_DIM, fontFamily = MONO, fontSize = 10.sp,
            letterSpacing = 2.sp, modifier = Modifier.width(120.dp))
        Text(v, color = INK, fontFamily = MONO, fontSize = 11.sp)
    }
}

@Composable
private fun Steps(vararg lines: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(SURFACE, RoundedCornerShape(3.dp))
            .border(1.dp, BORDER, RoundedCornerShape(3.dp))
            .padding(14.dp)
    ) {
        lines.forEachIndexed { i, line ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Text(line, color = INK, fontFamily = MONO, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ProviderRow(p: Settings.Provider, on: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val enabled = p.available
    val borderColor = when {
        on -> PHOSPHOR
        !enabled -> BORDER.copy(alpha = 0.5f)
        else -> BORDER
    }
    val nameColor = when {
        on -> PHOSPHOR
        !enabled -> INK_DIM
        else -> INK
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (on) PHOSPHOR.copy(alpha = 0.10f) else SURFACE,
                RoundedCornerShape(3.dp))
            .border(1.dp, borderColor, RoundedCornerShape(3.dp))
            .clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(if (on) PHOSPHOR else Color.Transparent)
                .border(1.dp, if (on) PHOSPHOR else INK_DIM, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.short, color = nameColor, fontFamily = MONO, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (!enabled) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.background(AMBER.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("SOON", color = AMBER, fontFamily = MONO, fontSize = 8.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }
            Text(p.hostname, color = if (on) PHOSPHOR.copy(alpha = 0.7f) else INK_DIM,
                fontFamily = MONO, fontSize = 10.sp)
            Spacer(Modifier.height(3.dp))
            Text(p.description, color = INK_DIM, fontFamily = MONO,
                fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

/* ───────────────────────── SHIELD ───────────────────────── */

@Composable
private fun ShieldScreen() {
    val ctx = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(AcousticShieldService.currentMode) }
    var volume by rememberSaveable { mutableFloatStateOf(AcousticShieldService.currentVolume) }
    var intensity by rememberSaveable { mutableFloatStateOf(AcousticShieldService.currentIntensity) }
    var target by rememberSaveable { mutableIntStateOf(AcousticShieldService.currentTarget) }
    var hasMicPerm by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) }
    val micPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPerm = granted
        if (granted) {
            mode = AcousticShieldService.Mode.PHASE
            AcousticShieldService.start(ctx, AcousticShieldService.Mode.PHASE, volume, intensity, target)
        }
    }

    fun selectMode(newMode: AcousticShieldService.Mode) {
        if (newMode == AcousticShieldService.Mode.OFF) {
            AcousticShieldService.stop(ctx); mode = newMode
        } else if (newMode == AcousticShieldService.Mode.PHASE && !hasMicPerm) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            mode = newMode
            AcousticShieldService.start(ctx, newMode, volume, intensity, target)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ShieldStatusPanel(mode)
        Spacer(Modifier.height(18.dp))
        ModeGrid(mode, ::selectMode)
        Spacer(Modifier.height(18.dp))
        BigSlider("VOLUME", "${(volume * 100).toInt()}%", volume) {
            volume = it
            AcousticShieldService.updateParams(it, intensity, target)
        }
        BigSlider("INTENSITY", "${(intensity * 100).toInt()}%", intensity) {
            intensity = it
            AcousticShieldService.updateParams(volume, it, target)
        }
        if (mode == AcousticShieldService.Mode.COUNTER) {
            BigSliderInt("TARGET", "${target} Hz", target, 50..6000) {
                target = it
                AcousticShieldService.updateParams(volume, intensity, it)
            }
        }
        Spacer(Modifier.height(20.dp))
        Disclaimer(
            "Phone speakers cap ~85 dB SPL. This raises the noise floor in the speech " +
                "band, degrading nearby smartphone-quality recordings. It does NOT defeat " +
                "directional mics and is NOT an LRAD."
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ShieldStatusPanel(mode: AcousticShieldService.Mode) {
    val active = mode != AcousticShieldService.Mode.OFF
    val color = if (active) PHOSPHOR else AMBER
    Column(
        Modifier.fillMaxWidth()
            .background(SURFACE, RoundedCornerShape(4.dp))
            .border(1.dp, BORDER, RoundedCornerShape(4.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MODE", color = INK_DIM, fontFamily = MONO, fontSize = 10.sp,
                letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            if (active) Pulse()
        }
        Spacer(Modifier.height(4.dp))
        Text(mode.name, color = color, fontFamily = MONO, fontSize = 28.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        Spacer(Modifier.height(12.dp))
        if (active) LiveLevelMeter() else Box(Modifier.height(24.dp).fillMaxWidth()) {
            Text("(idle)", color = INK_DIM, fontFamily = MONO, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterStart))
        }
    }
}

@Composable
private fun LiveLevelMeter() {
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            level = AcousticShieldService.outputLevel
            kotlinx.coroutines.delay(60)
        }
    }
    val animated by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(80, easing = LinearEasing),
        label = "lvl",
    )
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val cellW = size.width / 28f
        val gap = 2f
        for (i in 0 until 28) {
            val frac = (i + 1) / 28f
            val on = frac <= animated * 1.4f
            val color = when {
                frac > 0.85f -> AMBER
                frac > 0.65f -> Color(0xFFFFDD33)
                else -> PHOSPHOR
            }
            drawRect(
                color = if (on) color else color.copy(alpha = 0.10f),
                topLeft = Offset(i * cellW + gap / 2f, 0f),
                size = Size(cellW - gap, size.height),
            )
        }
    }
}

@Composable
private fun ModeGrid(active: AcousticShieldService.Mode, onSelect: (AcousticShieldService.Mode) -> Unit) {
    val cells = listOf(
        AcousticShieldService.Mode.SWEEP to "swept tone\n2.75 kHz \u00b11.25",
        AcousticShieldService.Mode.BROADBAND to "filtered noise\nspeech band",
        AcousticShieldService.Mode.COUNTER to "harmonics of\ntarget Hz",
        AcousticShieldService.Mode.PHASE to "mic invert\n(headphones)",
    )
    Column(Modifier.fillMaxWidth()) {
        for (rowStart in cells.indices step 2) {
            Row(Modifier.fillMaxWidth()) {
                ModeCell(cells[rowStart], active, Modifier.weight(1f), onSelect)
                Spacer(Modifier.width(8.dp))
                ModeCell(cells[rowStart + 1], active, Modifier.weight(1f), onSelect)
            }
            Spacer(Modifier.height(8.dp))
        }
        OffButton(active) { onSelect(AcousticShieldService.Mode.OFF) }
    }
}

@Composable
private fun ModeCell(
    cell: Pair<AcousticShieldService.Mode, String>,
    active: AcousticShieldService.Mode,
    modifier: Modifier,
    onSelect: (AcousticShieldService.Mode) -> Unit,
) {
    val (m, sub) = cell
    val on = m == active
    val src = remember { MutableInteractionSource() }
    Column(
        modifier
            .height(96.dp)
            .background(if (on) PHOSPHOR.copy(alpha = 0.18f) else SURFACE,
                RoundedCornerShape(4.dp))
            .border(1.dp, if (on) PHOSPHOR else BORDER, RoundedCornerShape(4.dp))
            .clickable(interactionSource = src, indication = null) { onSelect(m) }
            .padding(12.dp),
    ) {
        Text(m.name, color = if (on) PHOSPHOR else INK,
            fontFamily = MONO, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(sub, color = INK_DIM, fontFamily = MONO, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun OffButton(active: AcousticShieldService.Mode, onClick: () -> Unit) {
    val on = active == AcousticShieldService.Mode.OFF
    val src = remember { MutableInteractionSource() }
    val color = if (on) AMBER else INK_DIM
    Box(
        Modifier.fillMaxWidth().height(48.dp)
            .background(if (on) AMBER.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = if (on) 1f else 0.4f), RoundedCornerShape(4.dp))
            .clickable(interactionSource = src, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("STOP / OFF", color = color, fontFamily = MONO, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
    }
}

@Composable
private fun BigSlider(label: String, valueLabel: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = INK_DIM, fontFamily = MONO, fontSize = 10.sp,
                letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text(valueLabel, color = PHOSPHOR, fontFamily = MONO, fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = PHOSPHOR, activeTrackColor = PHOSPHOR.copy(alpha = 0.6f),
                inactiveTrackColor = BORDER))
    }
}

@Composable
private fun BigSliderInt(label: String, valueLabel: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = INK_DIM, fontFamily = MONO, fontSize = 10.sp,
                letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text(valueLabel, color = PHOSPHOR, fontFamily = MONO, fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
        }
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = PHOSPHOR, activeTrackColor = PHOSPHOR.copy(alpha = 0.6f),
                inactiveTrackColor = BORDER))
    }
}

/* ───────────────────────── ABOUT ───────────────────────── */

@Composable
private fun AboutScreen() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SectionHeader("what bastion does")
        Spacer(Modifier.height(8.dp))
        BodyBlock(
            "BASTION is two independent tools:",
            "",
            "01 DNS — a config wizard for Android's built-in Private DNS feature. " +
                "We point your phone at a hosted DoT resolver that filters malware, " +
                "phishing, and (depending on provider) ads + trackers. The OS does the " +
                "DNS resolution; bastion is just the wizard. Works on every app, can't " +
                "break your internet, costs zero battery.",
            "",
            "02 SHIELD — generates audio designed to raise the noise floor in the " +
                "speech band of nearby microphones. Useful for in-person privacy in a " +
                "small bubble. Not a force field.",
        )

        Spacer(Modifier.height(20.dp))
        SectionHeader("what bastion does NOT do")
        Spacer(Modifier.height(8.dp))
        BodyBlock(
            "• Block spyware that's already on your device.",
            "• Detect Pegasus / Predator / commercial implants.",
            "• Inspect or scan inside other apps' sandboxes.",
            "• Defeat directional mics or LRAD-class recording.",
            "• Encrypt your traffic (use a real VPN for that).",
        )

        Spacer(Modifier.height(20.dp))
        SectionHeader("changelog")
        Spacer(Modifier.height(8.dp))
        BodyBlock(
            "v0.3.0 — Removed the on-device DNS sinkhole VPN entirely. It was " +
                "fragile and occasionally broke users' internet (carrier middleboxes, " +
                "DoH-by-default browsers, OS connectivity heuristics). Replaced with " +
                "an OS-level Private DNS config wizard pointing at hosted DoT " +
                "providers. Self-hosted bastion DNS coming once dns.bastion.cam is live.",
            "",
            "v0.2.7 — Last VPN-mode release. Final patch to the DNS sinkhole.",
        )

        Spacer(Modifier.height(20.dp))
        SectionHeader("links")
        Spacer(Modifier.height(8.dp))
        BodyBlock(
            "source:   github.com/1800bobrossdotcom-byte/bastion-mobile",
            "issues:   github.com/1800bobrossdotcom-byte/bastion-mobile/issues",
            "version:  0.3.0 (build 10)",
        )
        Spacer(Modifier.height(24.dp))
    }
}

/* ───────────────────────── shared ───────────────────────── */

@Composable
private fun SectionHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("//", color = PHOSPHOR, fontFamily = MONO, fontSize = 14.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(title.uppercase(), color = INK, fontFamily = MONO, fontSize = 13.sp,
            letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionButton(label: String, dim: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().height(48.dp)
            .background(PHOSPHOR.copy(alpha = if (dim) 0.05f else 0.15f), RoundedCornerShape(3.dp))
            .border(1.dp, PHOSPHOR.copy(alpha = if (dim) 0.3f else 1f), RoundedCornerShape(3.dp))
            .clickable(interactionSource = src, indication = null, enabled = !dim) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = PHOSPHOR.copy(alpha = if (dim) 0.6f else 1f),
            fontFamily = MONO, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    }
}

@Composable
private fun BodyBlock(vararg lines: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(SURFACE, RoundedCornerShape(3.dp))
            .border(1.dp, BORDER, RoundedCornerShape(3.dp))
            .padding(14.dp)
    ) {
        lines.forEach { line ->
            Text(
                if (line.isEmpty()) " " else line,
                color = INK, fontFamily = MONO, fontSize = 11.sp, lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun Disclaimer(body: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(AMBER.copy(alpha = 0.06f), RoundedCornerShape(3.dp))
            .border(1.dp, AMBER.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
            .padding(12.dp)
    ) {
        Text("[!] HONESTY", color = AMBER, fontFamily = MONO, fontSize = 10.sp,
            letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = INK, fontFamily = MONO, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
