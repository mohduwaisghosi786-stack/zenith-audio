package com.zenith.audio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.zenith.audio.model.ConnectionState
import com.zenith.audio.model.StreamMetrics
import com.zenith.audio.service.AudioReceiverService
import kotlinx.coroutines.flow.collectLatest

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var audioService: AudioReceiverService? = null
    private var isBound = false
    private val metricsState = mutableStateOf(StreamMetrics())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AudioReceiverService.LocalBinder
            audioService = localBinder?.getService()
            isBound = true

            audioService?.getMetricsFlow()?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { metrics ->
                        metricsState.value = metrics
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        val prefs = getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
        val initialIp = prefs.getString("server_ip", "192.168.1.9") ?: "192.168.1.9"

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZenithAudioScreen(
                        initialIp = initialIp,
                        metrics = metricsState.value,
                        onConnect = { ip, bitrate ->
                            prefs.edit().putString("server_ip", ip).apply()
                            startStreamingService(ip, bitrate)
                        },
                        onDisconnect = {
                            stopStreamingService()
                        },
                        onBitrateChange = { bitrate ->
                            audioService?.setBitrate(bitrate)
                        }
                    )
                }
            }
        }
    }

    private fun startStreamingService(ip: String, bitrate: Int) {
        val intent = Intent(this, AudioReceiverService::class.java).apply {
            action = AudioReceiverService.ACTION_START
            putExtra(AudioReceiverService.EXTRA_SERVER_IP, ip)
            putExtra(AudioReceiverService.EXTRA_BITRATE, bitrate)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopStreamingService() {
        val intent = Intent(this, AudioReceiverService::class.java).apply {
            action = AudioReceiverService.ACTION_STOP
        }
        startService(intent)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        metricsState.value = StreamMetrics(connectionState = ConnectionState.DISCONNECTED)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun ZenithAudioScreen(
    initialIp: String,
    metrics: StreamMetrics,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onBitrateChange: (Int) -> Unit
) {
    var serverIp by remember { mutableStateOf(initialIp) }
    var selectedBitrate by remember { mutableIntStateOf(320) }
    val bitrates = listOf(320, 256, 192, 128, 96, 64)

    val isConnected = metrics.connectionState == ConnectionState.CONNECTED
    val isConnecting = metrics.connectionState == ConnectionState.CONNECTING ||
            metrics.connectionState == ConnectionState.RECONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "ZENITH AUDIO",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Ultra-Low-Latency Stream",
                    fontSize = 13.sp,
                    color = Color(0xFFAAAAAA)
                )
            }

            StatusBadge(state = metrics.connectionState)
        }

        // Server IP Input Field
        OutlinedTextField(
            value = serverIp,
            onValueChange = { serverIp = it },
            label = { Text("Linux Server IP") },
            placeholder = { Text("192.168.1.9") },
            enabled = !isConnected && !isConnecting,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00E676),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Quality Bitrate Selector
        Text(
            text = "AUDIO QUALITY (BITRATE)",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(bitrates) { br ->
                val isSelected = (br == selectedBitrate)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedBitrate = br
                        if (isConnected) onBitrateChange(br)
                    },
                    label = {
                        Text(
                            text = if (br == 320) "$br kbps (HQ)" else "$br kbps",
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E676),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Main Connect / Disconnect Action Button
        Button(
            onClick = {
                if (isConnected || isConnecting) {
                    onDisconnect()
                } else {
                    onConnect(serverIp.trim(), selectedBitrate)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected || isConnecting) Color(0xFFFF5252) else Color(0xFF00E676),
                contentColor = if (isConnected || isConnecting) Color.White else Color.Black
            )
        ) {
            Icon(
                imageVector = if (isConnected || isConnecting) Icons.Default.Close else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isConnected -> "DISCONNECT"
                    isConnecting -> "CANCEL CONNECTING"
                    else -> "CONNECT TO SERVER"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Real-Time Diagnostic Dashboard
        Text(
            text = "LIVE TELEMETRY & DIAGNOSTICS",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Hardware Output Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (metrics.isBluetooth) Icons.Default.Bluetooth else Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = if (metrics.isBluetooth) Color(0xFF448AFF) else Color(0xFF00E676),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "ACTIVE AUDIO SINK",
                        fontSize = 10.sp,
                        color = Color(0xFF888888),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = metrics.outputDeviceName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Telemetry Grid
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                title = "EST. TOTAL LATENCY",
                value = if (isConnected) "%.1f ms".format(metrics.totalEstimatedLatencyMs) else "--",
                subtitle = "Net: %.1f | Jbuf: %.0f | DAC: %.0f".format(
                    metrics.networkRttMs / 2.0,
                    metrics.jitterBufferMs,
                    metrics.playbackLatencyMs
                ),
                icon = Icons.Default.Timer,
                highlight = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(
                title = "PACKET LOSS",
                value = if (isConnected) "%.2f%%".format(metrics.packetLossPercent) else "0.00%",
                subtitle = "Lost: ${metrics.packetsLost} / Rcv: ${metrics.packetsReceived}",
                icon = Icons.Default.ReportProblem,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                title = "NETWORK JITTER",
                value = if (isConnected) "%.2f ms".format(metrics.jitterMs) else "--",
                subtitle = "RFC 3550 Estimator",
                icon = Icons.Default.Timeline,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(
                title = "STREAM FORMAT",
                value = if (isConnected) "${metrics.bitrateKbps} kbps" else "320 kbps",
                subtitle = "48 kHz | Stereo | Opus",
                icon = Icons.Default.GraphicEq,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                title = "ROUND TRIP (RTT)",
                value = if (isConnected) "%.1f ms".format(metrics.networkRttMs) else "--",
                subtitle = "UDP Ping/Pong",
                icon = Icons.Default.NetworkCheck,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(
                title = "UNDERRUN COUNT",
                value = "${metrics.audioUnderruns}",
                subtitle = "AudioTrack Buffer Glitches",
                icon = Icons.Default.Warning,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun StatusBadge(state: ConnectionState) {
    val (bgColor, textColor, text) = when (state) {
        ConnectionState.CONNECTED -> Triple(Color(0xFF1B5E20), Color(0xFF00E676), "CONNECTED")
        ConnectionState.CONNECTING -> Triple(Color(0xFFE65100), Color(0xFFFFB74D), "CONNECTING")
        ConnectionState.RECONNECTING -> Triple(Color(0xFFB71C1C), Color(0xFFFF8A80), "RECONNECTING")
        ConnectionState.DISCONNECTED -> Triple(Color(0xFF333333), Color(0xFFAAAAAA), "DISCONNECTED")
    }

    Box(
        modifier = Modifier
            .background(bgColor, shape = CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(textColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) Color(0xFF1C2A20) else Color(0xFF1E1E1E)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (highlight) Color(0xFF00E676) else Color(0xFF888888)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlight) Color(0xFF00E676) else Color(0xFF666666),
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (highlight) Color(0xFF00E676) else Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 9.sp,
                color = Color(0xFFAAAAAA),
                maxLines = 1
            )
        }
    }
}
