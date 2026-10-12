package com.fakelag.tool

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fakelag.tool.ui.theme.FakeLagTheme

/**
 * Main screen — setup + manual toggle.
 * Does NOT auto-finish. User can open app anytime.
 */
class MainActivity : ComponentActivity() {

    private lateinit var engine: FakeLagEngine
    private var engineOn by mutableStateOf(false)
    private var rootGranted by mutableStateOf(false)
    private var overlayOk by mutableStateOf(true)
    private var floatingOn by mutableStateOf(false)
    private var buttonSize by mutableStateOf(56f)
    private var minByte by mutableStateOf(70f)
    private var maxByte by mutableStateOf(250f)

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        overlayOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = FakeLagEngine(this)
        overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        
        // Đọc kích thước đã lưu (nếu có)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        buttonSize = prefs.getFloat("button_size", 56f)
        minByte = prefs.getFloat("min_byte", 50f)
        maxByte = prefs.getFloat("max_byte", 250f)
        
        engine.minByte = minByte.toInt()
        engine.maxByte = maxByte.toInt()

        // Root check (Python: is_admin() → elevate())
        lifecycleScope.launch(Dispatchers.IO) {
            rootGranted = RootManager.ensureElevated()
        }

        engine.onStateChanged = { engineOn = it }

        // Poll floating service state and engine state
        lifecycleScope.launch {
            while (true) {
                floatingOn = FloatingButtonService.isRunning
                engineOn = FakeLagEngine.isRunning
                delay(500)
            }
        }

        setContent {
            FakeLagTheme {
                MainScreen(
                    engineOn = engineOn,
                    rootGranted = rootGranted,
                    overlayOk = overlayOk,
                    floatingOn = floatingOn,
                    buttonSize = buttonSize,
                    minByte = minByte,
                    maxByte = maxByte,
                    onSizeChange = {
                        buttonSize = it
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putFloat("button_size", it).apply()
                        if (floatingOn) {
                            val intent = Intent(this, FloatingButtonService::class.java).apply {
                                action = "UPDATE_SIZE"
                                putExtra("size", it.toInt())
                            }
                            startService(intent)
                        }
                    },
                    onRangeChange = { min, max ->
                        minByte = min
                        maxByte = max
                        engine.minByte = min.toInt()
                        engine.maxByte = max.toInt()
                        getSharedPreferences("settings", MODE_PRIVATE).edit()
                            .putFloat("min_byte", min)
                            .putFloat("max_byte", max)
                            .apply()
                    },
                    onToggle = { on ->
                        if (on) {
                            if (RootManager.hasRoot) engine.start()
                            else startVpnMode()
                        } else {
                            engine.stop()
                            val stopIntent = Intent(this@MainActivity, VpnProxyService::class.java).apply {
                                action = VpnProxyService.ACTION_STOP
                            }
                            startService(stopIntent)
                            stopService(stopIntent)
                        }
                    },
                    onGrantOverlay = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            overlayLauncher.launch(Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            ))
                        }
                    },
                    onToggleFloating = { on ->
                        if (on) {
                            if (overlayOk) {
                                val intent = Intent(this, FloatingButtonService::class.java).apply {
                                    putExtra("size", buttonSize.toInt())
                                }
                                ContextCompat.startForegroundService(this, intent)
                                Toast.makeText(this, "Nút trợ năng đã hiển thị ●", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            stopService(Intent(this, FloatingButtonService::class.java))
                        }
                    }
                )
            }
        }
    }

    private fun startVpnMode() {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 0)
        } else {
            ContextCompat.startForegroundService(this, Intent(this, VpnProxyService::class.java))
        }
    }

    @Deprecated("Use ActivityResultContract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 0 && resultCode == RESULT_OK) {
            ContextCompat.startForegroundService(this, Intent(this, VpnProxyService::class.java))
        }
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }
}

@Composable
fun MainScreen(
    engineOn: Boolean, rootGranted: Boolean, overlayOk: Boolean,
    floatingOn: Boolean, buttonSize: Float,
    minByte: Float, maxByte: Float,
    onSizeChange: (Float) -> Unit,
    onRangeChange: (Float, Float) -> Unit,
    onToggle: (Boolean) -> Unit, onGrantOverlay: () -> Unit,
    onToggleFloating: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("FAKE LAG TOOL", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (rootGranted) "Root: GRANTED" else "Root: none",
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = if (rootGranted) androidx.compose.ui.graphics.Color(0xFF55CC55)
                else androidx.compose.ui.graphics.Color(0xFF886600)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (engineOn) "ACTIVE — dropping UDP" else "IDLE",
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                color = if (engineOn) androidx.compose.ui.graphics.Color(0xFF55CC55)
                else androidx.compose.ui.graphics.Color(0xFF666666)
            )

            Spacer(modifier = Modifier.height(30.dp))

            // Range Settings
            Column(modifier = Modifier.width(280.dp)) {
                Text("Dải byte chặn UDP:", fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${minByte.toInt()}B", fontSize = 10.sp, color = androidx.compose.ui.graphics.Color(0xFF55CC55))
                    RangeSlider(
                        value = minByte..maxByte,
                        onValueChange = { range -> onRangeChange(range.start, range.endInclusive) },
                        valueRange = 20f..500f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = androidx.compose.ui.graphics.Color(0xFF55CC55),
                            activeTrackColor = androidx.compose.ui.graphics.Color(0xFF2E8B2E)
                        )
                    )
                    Text("${maxByte.toInt()}B", fontSize = 10.sp, color = androidx.compose.ui.graphics.Color(0xFF55CC55))
                }
                Text(
                    text = if (minByte < 50) "⚠️ Cảnh báo: Dưới 50B dễ văng game" else "Mức an toàn (khuyên dùng 50-250B, giống PC)",
                    fontSize = 9.sp,
                    color = if (minByte < 50) androidx.compose.ui.graphics.Color.Yellow else androidx.compose.ui.graphics.Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main toggle
            Button(
                onClick = { onToggle(!engineOn) },
                modifier = Modifier.width(280.dp).height(110.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (engineOn) androidx.compose.ui.graphics.Color(0xFF2E8B2E)
                    else androidx.compose.ui.graphics.Color(0xFF555555),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Text(
                    text = if (engineOn) "FAKE LAG: ON" else "FAKE LAG: OFF",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Floating button section ──
            if (!overlayOk) {
                Button(onClick = onGrantOverlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFCC8800)
                    )
                ) {
                    Text("Cấp quyền hiển thị nút trợ năng", fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nút trợ năng", fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = androidx.compose.ui.graphics.Color(0xFFCCCCCC))
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = floatingOn,
                        onCheckedChange = onToggleFloating,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = androidx.compose.ui.graphics.Color(0xFF2E8B2E)
                        )
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (floatingOn) "● Nút đang hiển thị đè lên app khác"
                    else "Bật để hiển thị nút tròn điều khiển",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = androidx.compose.ui.graphics.Color(0xFF888888)
                )
                
                if (floatingOn) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Kích thước nút: ${buttonSize.toInt()}dp", 
                        fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.White)
                    Slider(
                        value = buttonSize,
                        onValueChange = onSizeChange,
                        valueRange = 30f..120f,
                        modifier = Modifier.width(200.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("Chạm nút ● để bật/tắt Fake Lag", fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
        }
    }
}
