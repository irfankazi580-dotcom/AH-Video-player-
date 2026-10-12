package org.irfan.ahvideoplayer

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================ Colors ============================

internal object Pal {
    val Black = Color(0xFF05070D)
    val Navy = Color(0xFF0A1330)
    val Deep = Color(0xFF0F1D45)
    val Royal = Color(0xFF1D4ED8)
    val Electric = Color(0xFF3B82F6)
    val Ice = Color(0xFF7DD3FC)
    val Text = Color(0xFFE6EEFF)
    val Muted = Color(0xFF8DA2C8)
}

// ============================ Data ============================

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
    val durationMs: Long,
    val sizeBytes: Long
)

fun loadVideos(context: Context): List<VideoItem> {
    val list = ArrayList<VideoItem>()
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE
    )
    val sort = MediaStore.Video.Media.DATE_ADDED + " DESC"
    try {
        context.contentResolver.query(collection, projection, null, null, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                list.add(
                    VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "Video",
                        folder = c.getString(folderCol) ?: "Videos",
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol)
                    )
                )
            }
        }
    } catch (e: Exception) {
        // permission missing or provider error -> empty list
    }
    return list
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

fun formatSize(bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}

// ============================ Activity ============================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Pal.Electric,
                    background = Pal.Black,
                    surface = Pal.Navy,
                    onSurface = Pal.Text
                )
            ) {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var denied by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("All") }
    var playing by remember { mutableStateOf<Pair<List<VideoItem>, Int>?>(null) }

    val perms: Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val loader = remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }

    fun refresh() {
        scope.launch {
            loading = true
            videos = withContext(Dispatchers.IO) { loadVideos(context) }
            loading = false
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        denied = !result.values.all { it }
        if (!denied) {
            refresh()
        } else {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        val ok = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (ok) refresh() else launcher.launch(perms)
    }

    val folders = remember(videos) { listOf("All") + videos.map { it.folder }.distinct().sorted() }
    val filtered = remember(videos, query, folder) {
        videos.filter {
            (folder == "All" || it.folder == folder) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }

    Box(Modifier.fillMaxSize()) {
        SparkleBackground()
        Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
            Header(count = filtered.size, onRefresh = { refresh() })
            Spacer(Modifier.height(10.dp))
            SearchBar(query = query, onChange = { query = it })
            Spacer(Modifier.height(10.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(folders) { f ->
                    Chip(label = f, selected = f == folder, onClick = { folder = f })
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                denied -> CenterMessage(
                    text = "Allow video access to see your videos",
                    button = "Allow access",
                    onClick = { launcher.launch(perms) }
                )
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Pal.Ice)
                }
                filtered.isEmpty() -> CenterMessage(
                    text = "No videos found",
                    button = "Refresh",
                    onClick = { refresh() }
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(filtered, key = { _, v -> v.id }) { index, v ->
                        VideoCard(item = v, loader = loader, onClick = { playing = Pair(filtered, index) })
                    }
                }
            }
        }
        val current = playing
        if (current != null) {
            PlayerScreen(items = current.first, startIndex = current.second, onClose = { playing = null })
        }
    }
}

// ============================ UI pieces ============================

private class Star(val x: Float, val y: Float, val size: Float, val phase: Float)

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val p = Path()
    p.moveTo(c.x, c.y - r)
    p.quadraticBezierTo(c.x, c.y, c.x + r, c.y)
    p.quadraticBezierTo(c.x, c.y, c.x, c.y + r)
    p.quadraticBezierTo(c.x, c.y, c.x - r, c.y)
    p.quadraticBezierTo(c.x, c.y, c.x, c.y - r)
    p.close()
    drawPath(p, color)
}

@Composable
fun SparkleBackground() {
    val stars = remember {
        val r = java.util.Random(7)
        List(48) { Star(r.nextFloat(), r.nextFloat(), 3f + r.nextFloat() * 7f, r.nextFloat()) }
    }
    val transition = rememberInfiniteTransition(label = "sparkle")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "t"
    )
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Pal.Deep, Pal.Navy, Pal.Black)))
        for (s in stars) {
            val a = (sin((t + s.phase) * 2f * PI.toFloat()) + 1f) / 2f
            val alpha = 0.12f + 0.8f * a * a
            val center = Offset(s.x * size.width, s.y * size.height)
            val r = s.size.dp.toPx()
            drawCircle(Pal.Electric.copy(alpha = alpha * 0.18f), r * 1.8f, center)
            drawStar(center, r, Pal.Ice.copy(alpha = alpha))
        }
    }
}

@Composable
fun DiamondLogo(logoSize: Dp = 40.dp) {
    Canvas(Modifier.size(logoSize)) {
        val w = size.width
        val h = size.height
        val top = Path()
        top.moveTo(w * 0.27f, h * 0.10f)
        top.lineTo(w * 0.73f, h * 0.10f)
        top.lineTo(w * 0.96f, h * 0.38f)
        top.lineTo(w * 0.04f, h * 0.38f)
        top.close()
        val bottom = Path()
        bottom.moveTo(w * 0.04f, h * 0.38f)
        bottom.lineTo(w * 0.96f, h * 0.38f)
        bottom.lineTo(w * 0.5f, h * 0.95f)
        bottom.close()
        drawPath(bottom, Brush.linearGradient(listOf(Pal.Royal, Pal.Electric), Offset(0f, h), Offset(w, 0f)))
        drawPath(top, Brush.linearGradient(listOf(Pal.Ice, Pal.Electric), Offset(0f, 0f), Offset(w, h * 0.4f)))
        val line = Color.White.copy(alpha = 0.6f)
        drawLine(line, Offset(w * 0.04f, h * 0.38f), Offset(w * 0.96f, h * 0.38f), 1.5f)
        drawLine(line, Offset(w * 0.27f, h * 0.10f), Offset(w * 0.36f, h * 0.38f), 1.5f)
        drawLine(line, Offset(w * 0.73f, h * 0.10f), Offset(w * 0.64f, h * 0.38f), 1.5f)
        drawLine(line, Offset(w * 0.36f, h * 0.38f), Offset(w * 0.5f, h * 0.95f), 1.5f)
        drawLine(line, Offset(w * 0.64f, h * 0.38f), Offset(w * 0.5f, h * 0.95f), 1.5f)
    }
}

@Composable
fun Header(count: Int, onRefresh: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "shine")
    val shift by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "shift"
    )
    val brush = Brush.linearGradient(
        colors = listOf(Pal.Electric, Pal.Ice, Color.White, Pal.Ice, Pal.Electric),
        start = Offset(shift, 0f),
        end = Offset(shift + 300f, 0f)
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DiamondLogo(44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "AH Video Player",
                style = TextStyle(
                    brush = brush,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )
            Text("$count videos", color = Pal.Muted, fontSize = 13.sp)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, Pal.Electric.copy(alpha = 0.7f), RoundedCornerShape(50))
                .clickable(onClick = onRefresh)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("Refresh", color = Pal.Ice, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SearchBar(query: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(Color(0x66101C3F))
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Pal.Electric, Color(0xFF16254F), Pal.Ice)),
                shape
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Pal.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(Pal.Ice),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("Search videos...", color = Pal.Muted, fontSize = 15.sp)
                    }
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Text(
                "Clear",
                color = Pal.Ice,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onChange("") }.padding(start = 10.dp)
            )
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg = if (selected) {
        Brush.horizontalGradient(listOf(Pal.Royal, Pal.Electric))
    } else {
        Brush.horizontalGradient(listOf(Color(0x33182A5A), Color(0x33182A5A)))
    }
    val borderColor = if (selected) Pal.Ice.copy(alpha = 0.9f) else Pal.Royal.copy(alpha = 0.45f)
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else Pal.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun PlayBadge() {
    Canvas(Modifier.size(38.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(Brush.linearGradient(listOf(Pal.Electric, Pal.Royal)))
        val tri = Path()
        tri.moveTo(w * 0.40f, h * 0.30f)
        tri.lineTo(w * 0.72f, h * 0.50f)
        tri.lineTo(w * 0.40f, h * 0.70f)
        tri.close()
        drawPath(tri, Color.White)
    }
}

@Composable
fun VideoCard(item: VideoItem, loader: ImageLoader, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF0E1A3D), Color(0xFF080D1E))))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Pal.Electric.copy(alpha = 0.8f), Color(0xFF0B1226), Pal.Ice.copy(alpha = 0.5f))
                ),
                shape
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(128.dp)
                .height(76.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Pal.Black)
        ) {
            AsyncImage(
                model = item.uri,
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(formatDuration(item.durationMs), color = Color.White, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                color = Pal.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.folder + "  |  " + formatSize(item.sizeBytes),
                color = Pal.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        PlayBadge()
    }
}

@Composable
fun CenterMessage(text: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = Pal.Muted, fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Pal.Royal, Pal.Electric)))
                .clickable(onClick = onClick)
                .padding(horizontal = 26.dp, vertical = 12.dp)
        ) {
            Text(button, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ============================ Player ============================

private fun savePosition(p: ExoPlayer, prefs: SharedPreferences) {
    val id = p.currentMediaItem?.mediaId ?: return
    val dur = p.duration
    val pos = p.currentPosition
    val e = prefs.edit()
    if (dur > 0 && pos > dur - 5000) e.remove("pos_$id") else e.putLong("pos_$id", pos)
    e.apply()
}

@Composable
fun PlayerScreen(items: List<VideoItem>, startIndex: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("resume", Context.MODE_PRIVATE) }
    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItems = items.map {
                MediaItem.Builder().setUri(it.uri).setMediaId(it.id.toString()).build()
            }
            val first = items[startIndex]
            setMediaItems(mediaItems, startIndex, prefs.getLong("pos_" + first.id, 0L))
            prepare()
            playWhenReady = true
        }
    }

    // hide system bars while playing
    DisposableEffect(Unit) {
        val activity = context as Activity
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // save position and free the player
    DisposableEffect(Unit) {
        onDispose {
            savePosition(exo, prefs)
            exo.release()
        }
    }

    // pause when the app goes to background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exo.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(onBack = onClose)

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = exo
                keepScreenOn = true
            }
        },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
}
