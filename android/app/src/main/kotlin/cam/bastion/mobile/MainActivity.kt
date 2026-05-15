package cam.bastion.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cam.bastion.mobile.acoustic.AcousticShieldService
import cam.bastion.mobile.audit.AuditDb
import cam.bastion.mobile.audit.AuditEvent
import cam.bastion.mobile.blocklist.BlocklistRepo
import cam.bastion.mobile.settings.Settings
import cam.bastion.mobile.theme.AMBER
import cam.bastion.mobile.theme.BORDER
import cam.bastion.mobile.theme.PHOSPHOR
import cam.bastion.mobile.theme.SURFACE
import cam.bastion.mobile.vpn.BastionVpnService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BastionApp() }
    }
}

private enum class Tab(val label: String) { SENSOR("sensor"), SHIELD("shield"), LOG("log"), SETTINGS("conf") }

@Composable
fun BastionApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.SENSOR) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Header()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.SENSOR -> SensorScreen()
                    Tab.SHIELD -> ShieldScreen()
                    Tab.LOG -> LogScreen()
                    Tab.SETTINGS -> SettingsScreen()
                }
            }
            BottomTabs(tab) { tab = it }
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp)) {
        Text("[B] BASTION", color = PHOSPHOR, fontFamily = FontFamily.Monospace, fontSize = 22.sp)
        Text("v0.2.0 // sensor + acoustic shield", color = Color.White.copy(alpha = 0.4f),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun BottomTabs(active: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SURFACE).border(1.dp, BORDER)
    ) {
        Tab.entries.forEach { t ->
            val on = t == active
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.weight(1f)
                    .background(if (on) PHOSPHOR.copy(alpha = 0.10f) else Color.Transparent)
                    .clickable(interactionSource = src, indication = null) { onSelect(t) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "./${t.label}",
                    color = if (on) PHOSPHOR else Color.White.copy(alpha = 0.55f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/* ───────────────────────── SENSOR ───────────────────────── */

@Composable
private fun SensorScreen() {
    val ctx = LocalContext.current
    var sensorActive by rememberSaveable { mutableStateOf(false) }
    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, BastionVpnService::class.java))
            sensorActive = true
        }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        StatusCard(
            title = if (sensorActive) "[ status ] sensor: ACTIVE" else "[ status ] sensor: OFFLINE",
            color = if (sensorActive) PHOSPHOR else AMBER,
            body = "Filters DNS against URLhaus + OpenPhish.\n" +
                "Nothing leaves the phone except the DNS query itself, which is\n" +
                "forwarded to your chosen upstream resolver. Audit log is local."
        )
        Spacer(Modifier.height(16.dp))
        TerminalButton(if (sensorActive) "./sensor stop" else "./sensor start") {
            if (sensorActive) {
                ctx.stopService(Intent(ctx, BastionVpnService::class.java))
                sensorActive = false
            } else {
                val intent = VpnService.prepare(ctx)
                if (intent != null) vpnPermission.launch(intent)
                else {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, BastionVpnService::class.java))
                    sensorActive = true
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Disclaimer(
            "BASTION is a sensor, not a shield. It cannot block spyware, detect Pegasus, " +
                "or see what other apps do inside their sandbox. It can only watch DNS " +
                "lookups against a public blocklist of known-malicious hosts."
        )
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

    fun apply(newMode: AcousticShieldService.Mode) {
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
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        StatusCard(
            title = "[ shield ] mode: ${mode.name}",
            color = if (mode == AcousticShieldService.Mode.OFF) AMBER else PHOSPHOR,
            body = "Generates audio designed to obstruct nearby microphone recording.\n" +
                "SWEEP   — sawtooth swept ±1.25 kHz around 2.75 kHz\n" +
                "BROAD   — bandpass-filtered noise centred on 2 kHz\n" +
                "COUNTER — stacked harmonics of target frequency\n" +
                "PHASE   — live mic inverted + delayed (headphone use)"
        )
        Spacer(Modifier.height(14.dp))
        ModeRow(mode) { apply(it) }
        Spacer(Modifier.height(16.dp))
        Slider01("volume", volume) {
            volume = it
            if (mode != AcousticShieldService.Mode.OFF) AcousticShieldService.start(ctx, mode, it, intensity, target)
        }
        Slider01("intensity", intensity) {
            intensity = it
            if (mode != AcousticShieldService.Mode.OFF) AcousticShieldService.start(ctx, mode, volume, it, target)
        }
        if (mode == AcousticShieldService.Mode.COUNTER) {
            SliderInt("target Hz", target, 50..6000) {
                target = it
                AcousticShieldService.start(ctx, mode, volume, intensity, it)
            }
        }
        Spacer(Modifier.height(20.dp))
        Disclaimer(
            "Phone speakers cap ~85 dB SPL with negligible output above ~16 kHz. " +
                "This raises the noise floor in the speech band so nearby smartphone-quality " +
                "recordings of speech become harder to understand. It does NOT defeat directional mics, " +
                "is NOT an LRAD, and does NOT 'block' anything. Sustained high-intensity audio can " +
                "damage hearing and speakers — use at your own discretion."
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModeRow(active: AcousticShieldService.Mode, onSelect: (AcousticShieldService.Mode) -> Unit) {
    val modes = listOf(
        AcousticShieldService.Mode.SWEEP,
        AcousticShieldService.Mode.BROADBAND,
        AcousticShieldService.Mode.COUNTER,
        AcousticShieldService.Mode.PHASE,
        AcousticShieldService.Mode.OFF,
    )
    androidx.compose.foundation.layout.FlowRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEach { m ->
            val on = m == active
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .padding(end = 6.dp, bottom = 6.dp)
                    .background(if (on) PHOSPHOR.copy(alpha = 0.18f) else SURFACE,
                        RoundedCornerShape(2.dp))
                    .border(1.dp, if (on) PHOSPHOR else BORDER, RoundedCornerShape(2.dp))
                    .clickable(interactionSource = src, indication = null) { onSelect(m) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(m.name, color = if (on) PHOSPHOR else Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Slider01(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("$label: ${(value * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = PHOSPHOR, activeTrackColor = PHOSPHOR.copy(alpha = 0.6f),
                inactiveTrackColor = BORDER))
    }
}

@Composable
private fun SliderInt(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("$label: $value", color = Color.White.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = PHOSPHOR, activeTrackColor = PHOSPHOR.copy(alpha = 0.6f),
                inactiveTrackColor = BORDER))
    }
}

/* ───────────────────────── LOG ───────────────────────── */

@Composable
private fun LogScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AuditDb.get(ctx).dao() }
    val events by dao.recent(500).collectAsStateWithLifecycle(initialValue = emptyList())
    val count by dao.countFlow().collectAsStateWithLifecycle(initialValue = 0)
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("[ audit ] $count blocked", color = PHOSPHOR,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { dao.clear() } }) {
                Text("./wipe", color = AMBER, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text("(no blocks yet — start the sensor and browse a bit)",
                color = Color.White.copy(alpha = 0.4f),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { ev -> AuditRow(ev, fmt) }
            }
        }
    }
}

@Composable
private fun AuditRow(ev: AuditEvent, fmt: SimpleDateFormat) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(fmt.format(Date(ev.ts)),
            color = Color.White.copy(alpha = 0.45f),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        Text(ev.action, color = AMBER, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        Text(ev.host, color = PHOSPHOR, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.weight(1f))
    }
}

/* ───────────────────────── SETTINGS ───────────────────────── */

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolver by remember { mutableStateOf(Settings.resolver(ctx)) }
    var lastRefresh by remember { mutableLongStateOf(Settings.lastRefresh(ctx)) }
    var hostCount by remember { mutableIntStateOf(BlocklistRepo.hostCount) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshMsg by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        Text("[ conf ] upstream resolver", color = PHOSPHOR,
            fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Settings.Resolver.entries.forEach { r ->
            val on = r == resolver
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth()
                    .background(if (on) PHOSPHOR.copy(alpha = 0.10f) else SURFACE,
                        RoundedCornerShape(2.dp))
                    .border(1.dp, if (on) PHOSPHOR else BORDER, RoundedCornerShape(2.dp))
                    .clickable(interactionSource = src, indication = null) {
                        resolver = r
                        Settings.setResolver(ctx, r)
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (on) "[x]" else "[ ]", color = if (on) PHOSPHOR else Color.White.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(r.label, color = if (on) PHOSPHOR else Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
        }
        Text("Restart sensor to apply.",
            color = Color.White.copy(alpha = 0.4f),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(24.dp))
        Text("[ conf ] blocklist", color = PHOSPHOR,
            fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text("hosts loaded: $hostCount",
            color = Color.White.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("last refresh: " + if (lastRefresh == 0L) "never" else fmt.format(Date(lastRefresh)),
            color = Color.White.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        refreshMsg?.let {
            Text(it, color = AMBER, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        TerminalButton(if (refreshing) "./refreshing..." else "./blocklist refresh") {
            if (!refreshing) scope.launch {
                refreshing = true
                refreshMsg = null
                val ok = BlocklistRepo.forceRefresh(ctx)
                refreshing = false
                lastRefresh = Settings.lastRefresh(ctx)
                hostCount = BlocklistRepo.hostCount
                refreshMsg = if (ok) "ok" else "failed (no network?)"
            }
        }

        Spacer(Modifier.height(28.dp))
        Disclaimer(
            "Resolver choice affects which DNS server BASTION forwards non-blocked queries to. " +
                "Cloudflare and Quad9 publish privacy policies; Google logs more. The blocklist " +
                "is fetched from the bastion-mobile GitHub Pages site over HTTPS — no telemetry."
        )
    }
}

/* ───────────────────────── shared bits ───────────────────────── */

@Composable
private fun StatusCard(title: String, color: Color, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SURFACE),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, BORDER, RoundedCornerShape(4.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = color, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Color.White.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TerminalButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = PHOSPHOR.copy(alpha = 0.15f),
            contentColor = PHOSPHOR
        ),
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Disclaimer(body: String) {
    Column {
        Text("[warn] honesty.disclaimer", color = AMBER,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(body, color = Color.White.copy(alpha = 0.5f),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
}
