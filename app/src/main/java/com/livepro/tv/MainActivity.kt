package com.livepro.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.livepro.tv.data.DeviceActivationManager
import com.livepro.tv.data.IptvRepository
import com.livepro.tv.model.ActivationStatus
import com.livepro.tv.model.AppScreen
import com.livepro.tv.model.CategoryItem
import com.livepro.tv.model.ChannelItem
import com.livepro.tv.player.TvPlayerEngine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var activationManager: DeviceActivationManager
    private lateinit var repository: IptvRepository
    private lateinit var playerEngine: TvPlayerEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        activationManager = DeviceActivationManager(this)
        repository = IptvRepository()
        playerEngine = TvPlayerEngine(this)

        setContent {
            LiveProTvApp(
                activationManager = activationManager,
                repository = repository,
                playerEngine = playerEngine
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playerEngine.release()
    }
}

@Composable
fun LiveProTvApp(
    activationManager: DeviceActivationManager,
    repository: IptvRepository,
    playerEngine: TvPlayerEngine
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(activationManager.loadSavedStatus()) }
    var screen by remember { mutableStateOf(if (status.isActive) AppScreen.HOME else AppScreen.ACTIVATION) }
    var categories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
    var channels by remember { mutableStateOf<List<ChannelItem>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var currentChannelIndex by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }

    val filteredChannels = remember(channels, selectedCategory) {
        if (selectedCategory == "ALL") channels
        else channels.filter { it.categoryId == selectedCategory }
    }

    fun refreshPlaylist() {
        scope.launch {
            isLoading = true
            val (cats, chs) = repository.loadPlaylist(status)
            categories = cats
            channels = chs
            isLoading = false
        }
    }

    LaunchedEffect(status.isActive) {
        if (status.isActive && channels.isEmpty()) {
            refreshPlaylist()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF0A0E1A), Color(0xFF141E30), Color(0xFF0A0E1A))
                )
            )
    ) {
        when (screen) {
            AppScreen.ACTIVATION -> ActivationScreen(
                status = status,
                initialApiUrl = activationManager.getActivationApiUrl(),
                onCheckServer = { apiUrl ->
                    scope.launch {
                        isLoading = true
                        status = activationManager.verifyWithServer(apiUrl)
                        isLoading = false
                        if (status.isActive) {
                            screen = AppScreen.HOME
                            refreshPlaylist()
                        }
                    }
                },
                onManualXtream = { host, user, pass ->
                    activationManager.saveManualXtream(host, user, pass)
                    status = activationManager.loadSavedStatus()
                    screen = AppScreen.HOME
                    refreshPlaylist()
                },
                onManualM3u = { m3u ->
                    activationManager.saveManualM3u(m3u)
                    status = activationManager.loadSavedStatus()
                    screen = AppScreen.HOME
                    refreshPlaylist()
                },
                isLoading = isLoading
            )

            AppScreen.HOME -> HomeDashboardScreen(
                status = status,
                channelCount = channels.size,
                isLoading = isLoading,
                onOpenLiveTv = { screen = AppScreen.LIVE_TV },
                onRefresh = { refreshPlaylist() },
                onOpenActivation = { screen = AppScreen.ACTIVATION }
            )

            AppScreen.LIVE_TV -> LiveTvScreen(
                categories = categories,
                selectedCategory = selectedCategory,
                channels = filteredChannels,
                onSelectCategory = { selectedCategory = it },
                onPlayChannel = { idx ->
                    currentChannelIndex = idx
                    playerEngine.playStream(filteredChannels[idx].streamUrl)
                    screen = AppScreen.PLAYER
                },
                onBack = { screen = AppScreen.HOME }
            )

            AppScreen.PLAYER -> FullscreenPlayerScreen(
                playerEngine = playerEngine,
                channel = filteredChannels.getOrNull(currentChannelIndex),
                onNextChannel = {
                    if (filteredChannels.isNotEmpty()) {
                        currentChannelIndex = (currentChannelIndex + 1) % filteredChannels.size
                        playerEngine.playStream(filteredChannels[currentChannelIndex].streamUrl)
                    }
                },
                onPrevChannel = {
                    if (filteredChannels.isNotEmpty()) {
                        currentChannelIndex = if (currentChannelIndex - 1 < 0) filteredChannels.lastIndex else currentChannelIndex - 1
                        playerEngine.playStream(filteredChannels[currentChannelIndex].streamUrl)
                    }
                },
                onBack = {
                    playerEngine.stop()
                    screen = AppScreen.LIVE_TV
                }
            )

            AppScreen.SETTINGS -> ActivationScreen(
                status = status,
                initialApiUrl = activationManager.getActivationApiUrl(),
                onCheckServer = { apiUrl ->
                    scope.launch {
                        status = activationManager.verifyWithServer(apiUrl)
                        if (status.isActive) screen = AppScreen.HOME
                    }
                },
                onManualXtream = { h, u, p ->
                    activationManager.saveManualXtream(h, u, p)
                    status = activationManager.loadSavedStatus()
                    screen = AppScreen.HOME
                },
                onManualM3u = { m3u ->
                    activationManager.saveManualM3u(m3u)
                    status = activationManager.loadSavedStatus()
                    screen = AppScreen.HOME
                },
                isLoading = isLoading
            )
        }
    }
}

@Composable
fun TvFocusableButton(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF00D4FF),
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .background(
                color = if (focused) accent else Color(0xFF1F2A40),
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color(0xFF324363),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .focusable()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ActivationScreen(
    status: ActivationStatus,
    initialApiUrl: String,
    onCheckServer: (String) -> Unit,
    onManualXtream: (String, String, String) -> Unit,
    onManualM3u: (String) -> Unit,
    isLoading: Boolean
) {
    var apiUrl by remember { mutableStateOf(initialApiUrl) }
    var host by remember { mutableStateOf(status.host) }
    var user by remember { mutableStateOf(status.username) }
    var pass by remember { mutableStateOf(status.password) }
    var m3u by remember { mutableStateOf(status.m3uUrl) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Left Panel: Device ID & Server Activation
        Column(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF121A2B), RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("LIVE PRO TV - التفعيل", color = Color(0xFF00D4FF), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("معرف الجهاز (Device ID):", color = Color.Gray, fontSize = 14.sp)
            Text(status.deviceId, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Text("الصلاحية: ${status.expiryDate}", color = Color(0xFF4ADE80), fontSize = 16.sp)

            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                label = { Text("رابط سيرفر التفعيل (Apps Script / API)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            TvFocusableButton(
                text = if (isLoading) "جاري التحقق..." else "تفعيل عبر السيرفر",
                modifier = Modifier.fillMaxWidth(),
                onClick = { onCheckServer(apiUrl) }
            )

            if (status.message.isNotBlank()) {
                Text(status.message, color = Color(0xFFFACC15), fontSize = 14.sp)
            }
        }

        // Right Panel: Direct Xtream / M3U Entry
        Column(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF121A2B), RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("إدخال سيرفر مباشر (Xtream / M3U)", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host (http://domain:port)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TvFocusableButton(text = "حفظ وتشغيل Xtream", modifier = Modifier.fillMaxWidth()) {
                if (host.isNotBlank()) onManualXtream(host, user, pass)
            }
            HorizontalDivider(color = Color(0xFF283550))
            OutlinedTextField(value = m3u, onValueChange = { m3u = it }, label = { Text("رابط M3U مباشر") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TvFocusableButton(text = "حفظ وتشغيل M3U", modifier = Modifier.fillMaxWidth(), accent = Color(0xFF4ADE80)) {
                if (m3u.isNotBlank()) onManualM3u(m3u)
            }
        }
    }
}

@Composable
fun HomeDashboardScreen(
    status: ActivationStatus,
    channelCount: Int,
    isLoading: Boolean,
    onOpenLiveTv: () -> Unit,
    onRefresh: () -> Unit,
    onOpenActivation: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LIVE PRO TV", color = Color(0xFF00D4FF), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Column(horizontalAlignment = Alignment.End) {
                Text("ID: ${status.deviceId}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("الانتهاء: ${status.expiryDate}", color = Color(0xFF4ADE80), fontSize = 14.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            TvFocusableButton(
                text = if (isLoading) "جاري تحميل القنوات..." else "البث المباشر ($channelCount قناة)",
                modifier = Modifier.weight(1f).height(140.dp),
                accent = Color(0xFF00D4FF),
                onClick = onOpenLiveTv
            )
            TvFocusableButton(
                text = "تحديث القائمة",
                modifier = Modifier.weight(1f).height(140.dp),
                accent = Color(0xFF4ADE80),
                onClick = onRefresh
            )
            TvFocusableButton(
                text = "إعدادات السيرفر والتفعيل",
                modifier = Modifier.weight(1f).height(140.dp),
                accent = Color(0xFFF59E0B),
                onClick = onOpenActivation
            )
        }

        Text("استخدم أسهم الريموت للتنقل و OK للتشغيل", color = Color.Gray, fontSize = 14.sp)
    }
}

@Composable
fun LiveTvScreen(
    categories: List<CategoryItem>,
    selectedCategory: String,
    channels: List<ChannelItem>,
    onSelectCategory: (String) -> Unit,
    onPlayChannel: (Int) -> Unit,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    onBack()
                    true
                } else false
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Categories Column
        LazyColumn(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(Color(0xFF101829), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(categories) { _, cat ->
                TvFocusableButton(
                    text = cat.name,
                    modifier = Modifier.fillMaxWidth(),
                    accent = if (cat.id == selectedCategory) Color(0xFF00D4FF) else Color(0xFF38BDF8),
                    onClick = { onSelectCategory(cat.id) }
                )
            }
        }

        // Channels Column
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF101829), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(channels) { idx, ch ->
                TvFocusableButton(
                    text = "${ch.number}. ${ch.name}",
                    modifier = Modifier.fillMaxWidth(),
                    accent = Color(0xFF4ADE80),
                    onClick = { onPlayChannel(idx) }
                )
            }
        }
    }
}

@Composable
fun FullscreenPlayerScreen(
    playerEngine: TvPlayerEngine,
    channel: ChannelItem?,
    onNextChannel: () -> Unit,
    onPrevChannel: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                            onNextChannel(); true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                            onPrevChannel(); true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            onBack(); true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerEngine.exoPlayer
                    useController = true
                    controllerAutoShow = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (channel != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "${channel.number} - ${channel.name}",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
