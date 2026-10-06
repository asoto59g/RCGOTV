package com.abcgeomag.rcgotv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal interface RemoteUiActions {
    fun setTextStateListener(listener: ((Boolean) -> Unit)?)
    fun sendText(text: String)
    fun launchAppLink(appLink: String)
    fun power()
    fun home()
    fun back()
    fun up()
    fun down()
    fun left()
    fun right()
    fun ok()
    fun playPause()
    fun volumeUp()
    fun volumeDown()
    fun mute()
    fun channelUp()
    fun channelDown()
    fun menu()
    fun input()
    fun number(number: Int)
    fun star()
    fun pound()
}

internal class TvRemoteUiActions(private val remote: TvRemote) : RemoteUiActions {
    override fun setTextStateListener(listener: ((Boolean) -> Unit)?) = remote.setTextStateListener(listener)
    override fun sendText(text: String) = remote.sendText(text)
    override fun launchAppLink(appLink: String) = remote.launchAppLink(appLink)
    override fun power() = remote.power()
    override fun home() = remote.home()
    override fun back() = remote.back()
    override fun up() = remote.up()
    override fun down() = remote.down()
    override fun left() = remote.left()
    override fun right() = remote.right()
    override fun ok() = remote.ok()
    override fun playPause() = remote.playPause()
    override fun volumeUp() = remote.volumeUp()
    override fun volumeDown() = remote.volumeDown()
    override fun mute() = remote.mute()
    override fun channelUp() = remote.channelUp()
    override fun channelDown() = remote.channelDown()
    override fun menu() = remote.menu()
    override fun input() = remote.input()
    override fun number(number: Int) = remote.number(number)
    override fun star() = remote.star()
    override fun pound() = remote.pound()
}

internal data class TvApp(
    val name: String,
    val playStoreLink: String,
    val logo: AppLogo
)

internal enum class AppLogo { NETFLIX, YOUTUBE, PRIME_VIDEO, SPOTIFY, XUPER_TV, STREMIO }

internal val tvApps = listOf(
    TvApp("Netflix", "market://details?id=com.netflix.ninja", AppLogo.NETFLIX),
    TvApp("YouTube", "market://details?id=com.google.android.youtube.tv", AppLogo.YOUTUBE),
    TvApp("Prime Video", "market://details?id=com.amazon.amazonvideo.livingroom", AppLogo.PRIME_VIDEO),
    TvApp("Spotify", "market://details?id=com.spotify.tv.android", AppLogo.SPOTIFY),
    TvApp("Xuper TV", "market://details?id=com.xuper.tv", AppLogo.XUPER_TV),
    TvApp("Stremio", "market://details?id=com.stremio.one", AppLogo.STREMIO)
)

@Composable
internal fun RemoteScreen(
    remote: RemoteUiActions,
    tvName: String,
    networkName: String,
    status: String,
    connectionAvailable: Boolean,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onAppLaunch: (String) -> Unit
) {
    DisposableEffect(remote) {
        remote.setTextStateListener { }
        onDispose { remote.setTextStateListener(null) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(Modifier.padding(top = 14.dp)) {
                    Text("RCGOTV", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text(tvName, style = MaterialTheme.typography.titleMedium)
                    Text("Red local: $networkName", style = MaterialTheme.typography.labelSmall)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                ControlCard("Navegación") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        RemoteButton(Icons.Filled.PowerSettingsNew, "Encendido / reposo", remote::power, Modifier.weight(1f))
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(2f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        RemoteButton(Icons.Filled.KeyboardArrowUp, "Arriba", remote::up, Modifier.weight(1f), 38.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemoteButton(Icons.Filled.ChevronLeft, "Izquierda", remote::left, Modifier.weight(1f), 38.dp)
                        Button(
                            onClick = remote::ok,
                            modifier = Modifier.weight(1f).heightIn(min = 58.dp),
                            shape = CircleShape
                        ) { Text("OK", fontWeight = FontWeight.Bold) }
                        RemoteButton(Icons.Filled.ChevronRight, "Derecha", remote::right, Modifier.weight(1f), 38.dp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        RemoteButton(Icons.Filled.KeyboardArrowDown, "Abajo", remote::down, Modifier.weight(1f), 38.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemoteButton(Icons.Filled.Home, "Inicio", remote::home, Modifier.weight(1f))
                        RemoteButton(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", remote::back, Modifier.weight(1f))
                    }
                }
            }
            item {
                ControlCard("TV y reproducción") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemoteButton(Icons.AutoMirrored.Filled.VolumeUp, "Subir volumen", remote::volumeUp, Modifier.weight(1f))
                        RemoteButton(Icons.AutoMirrored.Filled.VolumeOff, "Silenciar", remote::mute, Modifier.weight(1f))
                        RemoteButton(Icons.AutoMirrored.Filled.VolumeDown, "Bajar volumen", remote::volumeDown, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChannelButton(Icons.Filled.SkipNext, "CH +", "Subir canal", remote::channelUp, Modifier.weight(1f))
                        ChannelButton(Icons.Filled.SkipPrevious, "CH −", "Bajar canal", remote::channelDown, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemoteButton(Icons.Filled.PlayArrow, "Reproducir o pausar", remote::playPause, Modifier.weight(1f))
                        RemoteButton(Icons.AutoMirrored.Filled.Input, "Cambiar entrada", remote::input, Modifier.weight(1f))
                        RemoteButton(Icons.Filled.MoreVert, "Menú", remote::menu, Modifier.weight(1f))
                    }
                }
            }
            item {
                ControlCard("Aplicaciones") {
                    tvApps.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { app ->
                                AppShortcutButton(
                                    app = app,
                                    onClick = {
                                        remote.launchAppLink(app.playStoreLink)
                                        onAppLaunch(app.name)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (row.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item {
                if (!connectionAvailable) {
                    OutlinedButton(
                        onClick = onReconnect,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                    ) {
                        Text("Reconectar")
                    }
                }
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                    Text("Salir")
                }
            }
        }
    }
}

@Composable
private fun AppShortcutButton(
    app: TvApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 92.dp).semantics {
            contentDescription = "Abrir ${app.name}"
        },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF20282B),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AppLogoIcon(app.logo, Modifier.size(46.dp))
            Spacer(Modifier.height(5.dp))
            Text(
                app.name,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AppLogoIcon(logo: AppLogo, modifier: Modifier = Modifier) {
    when (logo) {
        AppLogo.NETFLIX -> Box(
            modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF080808)),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = Color(0xFFE50914), fontSize = 36.sp, fontWeight = FontWeight.Black)
        }
        AppLogo.YOUTUBE -> Surface(
            modifier = modifier,
            shape = RoundedCornerShape(13.dp),
            color = Color(0xFFFF0033)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        AppLogo.PRIME_VIDEO -> Box(
            modifier.clip(RoundedCornerShape(9.dp)).background(
                Brush.verticalGradient(listOf(Color(0xFF16A8E0), Color(0xFF0877BD)))
            ),
            contentAlignment = Alignment.Center
        ) {
            Text("prime", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        AppLogo.SPOTIFY -> Surface(modifier = modifier, shape = CircleShape, color = Color(0xFF1ED760)) {
            Box(contentAlignment = Alignment.Center) {
                Text("≋", color = Color(0xFF10251A), fontSize = 32.sp, fontWeight = FontWeight.Black)
            }
        }
        AppLogo.XUPER_TV -> Box(
            modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF1E293B)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("X", color = Color(0xFF38BDF8), fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text("TV", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }
        }
        AppLogo.STREMIO -> Surface(modifier = modifier, shape = CircleShape, color = Color(0xFF5B4BDB)) {
            Box(contentAlignment = Alignment.Center) {
                Text("S", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChannelButton(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp).semantics {
            contentDescription = description
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF20282B),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RemoteButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp).semantics {
            contentDescription = description
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF20282B),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.padding(horizontal = 2.dp).size(iconSize)
        )
    }
}
