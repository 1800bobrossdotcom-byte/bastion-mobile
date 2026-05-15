package cam.bastion.mobile

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.bastion.mobile.theme.PHOSPHOR
import cam.bastion.mobile.vpn.BastionVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BastionApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BastionApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sensorActive by remember { mutableStateOf(false) }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            ctx.startForegroundService(Intent(ctx, BastionVpnService::class.java))
            sensorActive = true
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        color = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "BASTION",
                color = PHOSPHOR,
                fontFamily = FontFamily.Monospace,
                fontSize = 28.sp
            )
            Text(
                "v0.1.0 // dns sensor",
                color = Color.White.copy(alpha = 0.4f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )

            Spacer(Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (sensorActive) "[ status ] sensor: ACTIVE" else "[ status ] sensor: OFFLINE",
                        color = if (sensorActive) PHOSPHOR else Color(0xFFFFAA33),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Filters DNS against URLhaus + OpenPhish + MalwareBazaar.\n" +
                                "Nothing leaves the phone. Audit log stored locally.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    if (sensorActive) {
                        ctx.stopService(Intent(ctx, BastionVpnService::class.java))
                        sensorActive = false
                    } else {
                        val intent = VpnService.prepare(ctx)
                        if (intent != null) {
                            vpnPermission.launch(intent)
                        } else {
                            ctx.startForegroundService(Intent(ctx, BastionVpnService::class.java))
                            sensorActive = true
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PHOSPHOR.copy(alpha = 0.15f),
                    contentColor = PHOSPHOR
                ),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (sensorActive) "./sensor stop" else "./sensor start",
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "[warn] honesty.disclaimer",
                color = Color(0xFFFFAA33),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Text(
                "BASTION is a sensor, not a shield. It cannot block spyware, detect Pegasus, " +
                        "or see what other apps do inside their sandbox. It can only watch DNS " +
                        "lookups against a public blocklist of known-malicious hosts.",
                color = Color.White.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
