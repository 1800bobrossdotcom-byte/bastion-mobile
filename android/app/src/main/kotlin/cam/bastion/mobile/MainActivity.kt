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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    SENSOR("01", "sensor"),
    SHIELD("02", "shield"),
    LOG("03", "audit"),
    SETTINGS("04", "conf"),
}

@Composable
fun BastionApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.SENSOR) }
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            TopBar(tab)
            ServiceStatusStrip()
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
            Text("v0.2.7 :: ${tab.short} ${tab.long}", color = INK_DIM,
                fontFamily = MONO, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Pulse()
    }
    Divider(color = BORDER, thickness = 1.dp)
}

/**
 * Persistent two-pill status row visible on every tab so the user can see at a
 * glance that Sensor and Shield run independently and can be active together.
 */
@Composable
private fun ServiceStatusStrip() {
    var sensorOn by remember { mutableStateOf(BastionVpnService.isRunning) }
    var shieldMode by remember { mutableStateOf(AcousticShieldService.currentMode) }
    LaunchedEffect(Unit) {
        while (true) {
            sensorOn = BastionVpnService.isRunning
            shieldMode = AcousticShieldService.currentMode
            delay(400)
        }
    }
    val shieldOn = shieldMode != AcousticShieldService.Mode.OFF
    Row(
        Modifier.fillMaxWidth().background(Color.Black)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPill("SENSOR", if (sensorOn) "ON" else "OFF", sensorOn, Modifier.weight(1f))
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

/* ───────────────────────── SENSOR ───────────────────────── */

@Composable
private fun SensorScreen() {
    val ctx = LocalContext.current
    var sensorActive by remember { mutableStateOf(BastionVpnService.isRunning) }
    LaunchedEffect(Unit) {
        while (true) { sensorActive = BastionVpnService.isRunning; delay(400) }
    }
    val dao = remember { AuditDb.get(ctx).dao() }
    val count by dao.countFlow().collectAsStateWithLifecycle(initialValue = 0)

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, BastionVpnService::class.java))
            sensorActive = true
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BigPowerButton(active = sensorActive) {
            if (sensorActive) {
                // Send explicit STOP action so the service tears down
                // synchronously and frees the route back to normal internet.
                val stopIntent = Intent(ctx, BastionVpnService::class.java)
                    .setAction(BastionVpnService.ACTION_STOP)
                ContextCompat.startForegroundService(ctx, stopIntent)
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
        Spacer(Modifier.height(20.dp))
        Text(if (sensorActive) "DNS SENSOR ACTIVE" else "SENSOR OFFLINE",
            color = if (sensorActive) PHOSPHOR else AMBER,
            fontFamily = MONO, fontSize = 14.sp, letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(label = "blocks", value = count.toString())
            Stat(label = "hosts", value = BlocklistRepo.hostCount.toString())
            Stat(label = "upstream", value = Settings.resolver(ctx).ip)
        }
        Spacer(Modifier.height(28.dp))
        Disclaimer(
            "BASTION runs silently in the background filtering DNS lookups " +
                "against URLhaus + OpenPhish. All other traffic uses your normal " +
                "network — leave it on. It cannot stop spyware or detect Pegasus."
        )
    }
}

@Composable
private fun BigPowerButton(active: Boolean, onClick: () -> Unit) {
    val color = if (active) PHOSPHOR else AMBER
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(180.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.08f))
            .border(2.dp, color, CircleShape)
            .clickable(interactionSource = src, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(140.dp).clip(CircleShape)
                .background(color.copy(alpha = if (active) 0.18f else 0.04f))
                .border(1.dp, color.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (active) "STOP" else "START",
                color = color,
                fontFamily = MONO,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = PHOSPHOR, fontFamily = MONO, fontSize = 18.sp,
            fontWeight = FontWeight.Bold)
        Text(label.uppercase(), color = INK_DIM, fontFamily = MONO, fontSize = 9.sp,
            letterSpacing = 2.sp)
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

/* ───────────────────────── LOG ───────────────────────── */

@Composable
private fun LogScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AuditDb.get(ctx).dao() }
    val events by dao.recent(500).collectAsStateWithLifecycle(initialValue = emptyList())
    val count by dao.countFlow().collectAsStateWithLifecycle(initialValue = 0)
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val dateFmt = remember { SimpleDateFormat("MMM dd", Locale.US) }

    Column(Modifier.fillMaxSize()) {
        // Stats bar
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("BLOCKED LOOKUPS", color = INK_DIM, fontFamily = MONO,
                    fontSize = 10.sp, letterSpacing = 2.sp)
                Text(count.toString(), color = PHOSPHOR, fontFamily = MONO,
                    fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.height(40.dp)
                    .background(AMBER.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                    .border(1.dp, AMBER.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                    .clickable(interactionSource = src, indication = null) {
                        scope.launch { dao.clear() }
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("WIPE", color = AMBER, fontFamily = MONO, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            }
        }
        Divider(color = BORDER)
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NO BLOCKS YET", color = INK_DIM, fontFamily = MONO,
                        fontSize = 12.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("start the sensor to begin filtering",
                        color = INK_DIM, fontFamily = MONO, fontSize = 10.sp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { ev ->
                    AuditRow(ev, timeFmt, dateFmt)
                    Divider(color = BORDER.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun AuditRow(ev: AuditEvent, timeFmt: SimpleDateFormat, dateFmt: SimpleDateFormat) {
    val d = Date(ev.ts)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(64.dp)) {
            Text(timeFmt.format(d), color = INK, fontFamily = MONO, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
            Text(dateFmt.format(d), color = INK_DIM, fontFamily = MONO, fontSize = 9.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.background(AMBER.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text("BLOCK", color = AMBER, fontFamily = MONO, fontSize = 9.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(ev.host, color = PHOSPHOR, fontFamily = MONO, fontSize = 12.sp,
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
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SectionHeader("upstream resolver")
        Spacer(Modifier.height(8.dp))
        Settings.Resolver.entries.forEach { r ->
            ResolverRow(r, r == resolver) {
                resolver = r
                Settings.setResolver(ctx, r)
            }
            Spacer(Modifier.height(6.dp))
        }
        Text("Restart sensor to apply.", color = INK_DIM, fontFamily = MONO,
            fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(28.dp))
        SectionHeader("blocklist")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            KvCell("hosts", hostCount.toString(), Modifier.weight(1f))
            KvCell("last sync",
                if (lastRefresh == 0L) "never" else fmt.format(Date(lastRefresh)),
                Modifier.weight(1.4f))
        }
        Spacer(Modifier.height(10.dp))
        refreshMsg?.let {
            Text(it, color = AMBER, fontFamily = MONO, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        ActionButton(if (refreshing) "REFRESHING..." else "REFRESH BLOCKLIST", refreshing) {
            scope.launch {
                refreshing = true; refreshMsg = null
                val ok = BlocklistRepo.forceRefresh(ctx)
                refreshing = false
                lastRefresh = Settings.lastRefresh(ctx)
                hostCount = BlocklistRepo.hostCount
                refreshMsg = if (ok) "ok" else "failed (no network?)"
            }
        }

        Spacer(Modifier.height(28.dp))
        Disclaimer(
            "Cloudflare and Quad9 publish privacy policies; Google logs more. " +
                "The blocklist is fetched over HTTPS \u2014 no telemetry."
        )
        Spacer(Modifier.height(20.dp))
    }
}

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
private fun ResolverRow(r: Settings.Resolver, on: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .background(if (on) PHOSPHOR.copy(alpha = 0.10f) else SURFACE,
                RoundedCornerShape(3.dp))
            .border(1.dp, if (on) PHOSPHOR else BORDER, RoundedCornerShape(3.dp))
            .clickable(interactionSource = src, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(if (on) PHOSPHOR else Color.Transparent)
                .border(1.dp, if (on) PHOSPHOR else INK_DIM, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.ip, color = if (on) PHOSPHOR else INK, fontFamily = MONO,
                fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(r.label.substringBefore(' '),
                color = INK_DIM, fontFamily = MONO, fontSize = 10.sp)
        }
    }
}

@Composable
private fun KvCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(SURFACE, RoundedCornerShape(3.dp))
            .border(1.dp, BORDER, RoundedCornerShape(3.dp))
            .padding(12.dp)
    ) {
        Text(label.uppercase(), color = INK_DIM, fontFamily = MONO, fontSize = 9.sp,
            letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = PHOSPHOR, fontFamily = MONO, fontSize = 13.sp,
            fontWeight = FontWeight.Bold)
    }
}

/* ───────────────────────── shared ───────────────────────── */

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
