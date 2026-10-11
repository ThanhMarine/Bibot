package com.bibot.app.feature.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibot.app.data.music.DefaultMusicPlayer
import com.bibot.app.data.music.MusicIndex
import com.bibot.app.data.music.MusicQuery
import com.bibot.app.data.music.Track
import com.bibot.app.data.music.parseMusicCommand
import com.bibot.app.data.music.searchTracks
import com.bibot.app.R
import com.bibot.app.ui.player.PlayerViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Module Tìm nhạc — app nhạc mặc định của Android là luồng phát CHÍNH:
 *  - Toggles "App nhạc mặc định" (mặc định BẬT): bấm phát → ACTION_VIEW content:// MediaStore
 *  - Tìm trên máy (MediaStore + thư mục Bibot) trước, không thấy → gửi lệnh
 *    PLAY_FROM_SEARCH cho app nhạc ngoài
 *  - File riêng của Bibot (app ngoài không đọc được) → tự fallback phát bằng ExoPlayer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    playerVm: PlayerViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerState by playerVm.playerState.collectAsState()

    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var hasAudioPerm by remember { mutableStateOf(MusicIndex.hasAudioPermission(context)) }
    var useDefaultPlayer by remember { mutableStateOf(true) }
    var searchInput by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Pair<Track, Int>>>(emptyList()) }
    var searchMsg by remember { mutableStateOf<String?>(null) }
    var noLocalHit by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            tracks = try { MusicIndex.allTracks(context) } catch (_: Exception) { emptyList() }
            loading = false
        }
    }

    LaunchedEffect(hasAudioPerm) { reload() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudioPerm = granted
        searchMsg = if (granted) context.getString(R.string.mus_granted)
        else context.getString(R.string.mus_no_perm)
        if (granted) reload()
    }

    fun playInApp(track: Track) {
        playingId = track.id
        val uri = track.mediaUri ?: android.net.Uri.fromFile(File(track.path)).toString()
        playerVm.playUrl(uri, track.title, track.artist)
        searchMsg = context.getString(R.string.mus_playing_track, track.title)
    }

    fun play(track: Track) {
        when {
            !useDefaultPlayer -> playInApp(track)
            !track.isPlayableExternally -> {
                playInApp(track)
                searchMsg = context.getString(R.string.mus_own_file, track.title)
            }
            DefaultMusicPlayer.playTrack(context, track) ->
                searchMsg = context.getString(R.string.mus_opening, track.title)
            else -> {
                playInApp(track)
                searchMsg = context.getString(R.string.mus_open_fail, track.title)
            }
        }
    }

    fun openDefaultApp() {
        searchMsg = if (DefaultMusicPlayer.open(context)) context.getString(R.string.mus_opened)
        else context.getString(R.string.mus_no_app)
    }

    fun doSearch() {
        val input = searchInput.trim()
        if (input.isBlank()) { searchMsg = context.getString(R.string.mus_type_first); return }
        isSearching = true; searchMsg = null; noLocalHit = false
        scope.launch {
            try {
                // Chấp nhận cả câu lệnh đầy đủ ("mở bài Diễm Xưa của...") lẫn tên trần
                val q = parseMusicCommand(input) ?: MusicQuery(input, null)
                val hits = searchTracks(context, q, limit = 8)
                searchResults = hits
                noLocalHit = hits.isEmpty()
                searchMsg = when {
                    hits.isEmpty() && !MusicIndex.hasAudioPermission(context) ->
                        context.getString(R.string.mus_no_perm_search)
                    hits.isEmpty() -> context.getString(R.string.mus_not_found)
                    else -> context.getString(R.string.mus_found, hits.size)
                }
            } catch (e: Exception) { searchMsg = context.getString(R.string.mus_search_fail, e.message ?: "") }
            isSearching = false
        }
    }

    fun searchOnDefaultApp() {
        val input = searchInput.trim()
        val q = parseMusicCommand(input) ?: MusicQuery(input.ifBlank { context.getString(R.string.mus_default_search) }, null)
        searchMsg = if (DefaultMusicPlayer.searchAndPlay(context, q))
            context.getString(R.string.mus_sent, q.title)
        else context.getString(R.string.mus_no_handler)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Now-playing của ExoPlayer trong app (dùng chung với TTS/sách)
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (playerState.isPlaying) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playerState.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(playerState.subtitle, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
                    }
                    TextButton(onClick = { playerVm.togglePlayPause() }) {
                        Text(if (playerState.isPlaying) stringResource(R.string.reader_pause) else stringResource(R.string.mus_play), fontSize = 12.sp)
                    }
                }
            }
        }

        // Luồng chính: app nhạc mặc định
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (useDefaultPlayer) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LibraryMusic, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.mus_default_app), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (useDefaultPlayer) stringResource(R.string.mus_tap_plays)
                                else stringResource(R.string.mus_inapp_player),
                                style = MaterialTheme.typography.labelSmall, fontSize = 11.sp
                            )
                        }
                        Switch(checked = useDefaultPlayer, onCheckedChange = { useDefaultPlayer = it })
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { openDefaultApp() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.mus_open_app))
                    }
                }
            }
        }

        item { Text(stringResource(R.string.mus_search_title), style = MaterialTheme.typography.titleMedium) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    label = { Text(stringResource(R.string.mus_title_author)) },
                    placeholder = { Text(stringResource(R.string.mus_placeholder_example)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { doSearch() }),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { doSearch() }, enabled = !isSearching) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else Text(stringResource(R.string.mus_find))
                }
            }
            searchMsg?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (noLocalHit) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { searchOnDefaultApp() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mus_search_default_app))
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        items(searchResults, key = { "s${it.first.id}" }) { (track, score) ->
            TrackRow(
                track = track,
                score = score,
                isPlayingInApp = playingId == track.id && playerState.isPlaying,
                onPlay = { play(track) },
                onPlayInApp = { playInApp(track) }
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mus_library, tracks.size), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        if (!hasAudioPerm) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.mus_need_perm), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.mus_perm_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = { permLauncher.launch(MusicIndex.audioPermission()) }) {
                            Text(stringResource(R.string.mus_allow))
                        }
                    }
                }
            }
        }

        if (!loading && tracks.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.mus_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                score = null,
                isPlayingInApp = playingId == track.id && playerState.isPlaying,
                onPlay = { play(track) },
                onPlayInApp = { playInApp(track) }
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            Divider()
            Text(
                stringResource(R.string.mus_about),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/** Một dòng bài hát: bấm ▶ = luồng chính (app nhạc mặc định), 🎧 = phát trong app Bibot. */
@Composable
private fun TrackRow(
    track: Track,
    score: Int?,
    isPlayingInApp: Boolean,
    onPlay: () -> Unit,
    onPlayInApp: () -> Unit
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) { Text(if (score == null) "🎶" else "🔎", fontSize = 20.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    buildString {
                        append(track.artist)
                        if (score != null) append(context.getString(R.string.mus_match, score))
                        append(if (track.isPlayableExternally) context.getString(R.string.mus_on_device) else " • Bibot")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            val secs = (track.durationMs / 1000).toInt()
            if (secs > 0) {
                Text(
                    "${secs / 60}:${String.format("%02d", secs % 60)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp
                )
            }
            IconButton(onClick = onPlay) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.mus_cd_default),
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onPlayInApp) {
                Icon(
                    if (isPlayingInApp) Icons.Default.Pause else Icons.Default.Headphones,
                    contentDescription = stringResource(R.string.mus_cd_inapp),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
