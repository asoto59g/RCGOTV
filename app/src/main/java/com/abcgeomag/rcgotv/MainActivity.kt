package com.abcgeomag.rcgotv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address

class MainActivity : ComponentActivity() {
    private var screenCleanup: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF00C2A8),
                    secondary = androidx.compose.ui.graphics.Color(0xFF71D9C7),
                    surface = androidx.compose.ui.graphics.Color(0xFF101416),
                    background = androidx.compose.ui.graphics.Color(0xFF080B0D)
                )
            ) {
                RemoteApp(onCleanupInstalled = { screenCleanup = it })
            }
        }
    }

    override fun onDestroy() {
        screenCleanup?.invoke()
        screenCleanup = null
        super.onDestroy()
    }
}

private object SavedTvStore {
    private const val PREFERENCES = "saved_tv_profiles"

    fun networkKey(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return "sin-wifi"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "sin-wifi"
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "sin-wifi"
        val linkProperties = manager.getLinkProperties(network) ?: return "sin-wifi"
        val address = linkProperties.linkAddresses
            .firstOrNull { it.address is Inet4Address }
            ?: return "wifi-sin-ip"
        val octets = address.address.address.map { it.toInt() and 0xff }
        val prefix = address.prefixLength.coerceIn(0, 32)
        val addressNumber = octets.fold(0L) { value, octet -> (value shl 8) or octet.toLong() }
        val mask = if (prefix == 0) 0L else (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
        val networkNumber = addressNumber and mask
        val networkAddress = (24 downTo 0 step 8)
            .joinToString(".") { shift -> ((networkNumber shr shift) and 0xff).toString() }
        return "$networkAddress-$prefix"
    }

    fun load(context: Context, networkKey: String): List<TvDevice> {
        val prefix = "tv:$networkKey:"
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .all
            .mapNotNull { (key, value) ->
                if (!key.startsWith(prefix) || value !is String) return@mapNotNull null
                val host = key.removePrefix(prefix)
                TvDevice(value, host, 6466)
            }
            .sortedBy { it.name.lowercase() }
    }

    fun save(context: Context, networkKey: String, device: TvDevice) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString("tv:$networkKey:${device.host}", device.name)
                .commit()
        ) { "No se pudo guardar el televisor." }
    }

    fun remove(context: Context, networkKey: String, host: String) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove("tv:$networkKey:$host")
                .commit()
        ) { "No se pudo eliminar el televisor guardado." }
    }
}

@Composable
private fun RemoteApp(onCleanupInstalled: ((() -> Unit)?) -> Unit) {
    val context = LocalContext.current.applicationContext
    val discovery = remember(context) { NsdDiscovery(context) }
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<TvDevice>() }
    var networkKey by remember { mutableStateOf(SavedTvStore.networkKey(context)) }
    val savedDevices = remember { mutableStateListOf<TvDevice>() }
    var pairing by remember { mutableStateOf<TvPairing?>(null) }
    var remote by remember { mutableStateOf<TvRemote?>(null) }
    var connected by remember { mutableStateOf(false) }
    var connectionAvailable by remember { mutableStateOf(false) }
    var connectedTvName by remember { mutableStateOf("") }
    var pendingAppLaunch by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var codeRequested by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Conecta el teléfono y la TV a la misma red Wi-Fi.") }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanGeneration by remember { mutableIntStateOf(0) }
    var attemptGeneration by remember { mutableIntStateOf(0) }

    fun refreshSavedDevices() {
        networkKey = SavedTvStore.networkKey(context)
        savedDevices.clear()
        savedDevices.addAll(SavedTvStore.load(context, networkKey))
    }

    fun stopCurrentConnection() {
        attemptGeneration++
        pairing?.stop()
        pairing = null
        remote?.stop()
        remote = null
        connected = false
        connectionAvailable = false
        connectedTvName = ""
        codeRequested = false
        code = ""
    }

    fun scan() {
        scanJob?.cancel()
        scanGeneration++
        val scanToken = scanGeneration
        devices.clear()
        status = "Buscando TVs en la red Wi-Fi..."
        scanJob = scope.launch {
            try {
                discovery.scan().collect { device ->
                    if (scanToken == scanGeneration) {
                        val index = devices.indexOfFirst { it.host == device.host }
                        if (index >= 0) devices[index] = device else devices.add(device)
                        status = "Encontrados ${devices.size} televisores."
                    }
                }
                if (scanToken == scanGeneration) {
                    status = if (devices.isEmpty()) {
                        "No se detectaron TVs. Puedes conectarte por IP."
                    } else {
                        "Encontrados ${devices.size} televisores."
                    }
                }
            } catch (error: Exception) {
                if (scanToken == scanGeneration) {
                    status = "Falló la búsqueda: ${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                if (scanToken == scanGeneration) scanJob = null
            }
        }
    }

    fun connect(device: TvDevice) {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        stopCurrentConnection()
        val token = attemptGeneration
        status = "Conectando con ${device.name}..."
        try {
            SavedTvStore.save(context, networkKey, device)
            refreshSavedDevices()
        } catch (error: Exception) {
            status = error.message ?: "No se pudo guardar el televisor."
            return
        }

        fun startRemote(identity: ClientIdentity) {
            if (token != attemptGeneration) return
            lateinit var connection: TvRemote
            connection = TvRemote(
                host = device.host,
                id = identity,
                onReady = {
                    if (token == attemptGeneration && remote === connection) {
                        connected = true
                        connectionAvailable = true
                        connectedTvName = device.name
                        pendingAppLaunch = null
                        pairing = null
                        codeRequested = false
                        status = "Conectado"
                    }
                },
                onError = { error ->
                    if (token == attemptGeneration && remote === connection) {
                        if (connected && pendingAppLaunch != null) {
                            connectionAvailable = false
                            status = "No se pudo abrir Google Play para $pendingAppLaunch. Verifica la TV o reconecta."
                        } else if (connected) {
                            connectionAvailable = false
                            status = "Se perdió la conexión con $connectedTvName. Pulsa Reconectar para intentarlo de nuevo."
                        } else {
                            connection.stop()
                            remote = null
                            connected = false
                            connectionAvailable = false
                            status = "No se pudo conectar: ${error.message ?: error.javaClass.simpleName}"
                        }
                    }
                }
            )
            remote = connection
            connection.start()
        }

        fun startPairing() {
            if (token != attemptGeneration) return
            lateinit var attempt: TvPairing
            attempt = TvPairing(
                context = context,
                host = device.host,
                onCode = {
                    if (token == attemptGeneration && pairing === attempt) {
                        codeRequested = true
                        status = "Escribe el PIN hexadecimal que aparece en la TV."
                    }
                },
                onPaired = { identity ->
                    if (token == attemptGeneration && pairing === attempt) {
                        attempt.stop()
                        pairing = null
                        status = "TV autorizada. Conectando..."
                        startRemote(identity)
                    }
                },
                onError = { error ->
                    if (token == attemptGeneration && pairing === attempt) {
                        attempt.stop()
                        pairing = null
                        codeRequested = false
                        status = "Falló el emparejamiento: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            )
            pairing = attempt
            attempt.start()
        }

        if (CertificateStore.hasPairedTv(context, device.host)) {
            scope.launch {
                try {
                    val identity = withContext(Dispatchers.IO) {
                        CertificateStore.loadOrCreate(context, device.host)
                    }
                    if (token == attemptGeneration) startRemote(identity)
                } catch (error: Exception) {
                    if (token == attemptGeneration) {
                        status = "No se pudieron cargar las credenciales: ${error.message}"
                    }
                }
            }
        } else {
            startPairing()
        }
    }

    fun connectManual(ip: String, name: String) {
        val octets = ip.trim().split(".")
        if (octets.size != 4 || octets.any { part ->
                val octet = part.toIntOrNull()
                octet == null || octet !in 0..255
            }
        ) {
            status = "Escribe una dirección IPv4 válida, por ejemplo 192.168.1.50."
            return
        }
        connect(TvDevice(name.trim().ifBlank { "TV $ip" }, ip.trim(), 6466))
    }

    fun disconnect() {
        stopCurrentConnection()
        pendingAppLaunch = null
        status = "Desconectado."
    }

    val cleanup by rememberUpdatedState {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        stopCurrentConnection()
    }

    DisposableEffect(onCleanupInstalled) {
        onCleanupInstalled { cleanup() }
        onDispose {
            cleanup()
            onCleanupInstalled(null)
        }
    }
    LaunchedEffect(Unit) { refreshSavedDevices() }

    if (connected && remote != null) {
        val remoteActions = remember(remote) { TvRemoteUiActions(remote!!) }
        RemoteScreen(
            remote = remoteActions,
            tvName = connectedTvName,
            networkName = networkKey,
            status = status,
            connectionAvailable = connectionAvailable,
            onDisconnect = ::disconnect,
            onReconnect = {
                val currentRemote = remote
                if (currentRemote != null) {
                    pendingAppLaunch = null
                    status = "Reconectando con $connectedTvName..."
                    currentRemote.start()
                } else {
                    status = "No hay una TV guardada para reconectar."
                }
            },
            onAppLaunch = { appName ->
                pendingAppLaunch = appName
                status = "Abriendo Google Play para $appName."
            }
        )
    } else {
        ConnectionScreen(
            networkName = networkKey,
            status = status,
            devices = devices,
            savedDevices = savedDevices,
            pairing = pairing,
            codeRequested = codeRequested,
            code = code,
            onCodeChange = { value ->
                code = value.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
            },
            onScan = ::scan,
            onConnect = ::connect,
            onConnectManual = ::connectManual,
            onRemoveSaved = { host ->
                try {
                    SavedTvStore.remove(context, networkKey, host)
                    refreshSavedDevices()
                } catch (error: Exception) {
                    status = error.message ?: "No se pudo eliminar el televisor."
                }
            },
            onSubmitCode = {
                val currentPairing = pairing
                if (currentPairing == null) {
                    status = "El emparejamiento caducó; vuelve a conectar para solicitar otro PIN."
                } else {
                    val submittedCode = code
                    scope.launch {
                        val success = currentPairing.submitCode(submittedCode)
                        if (pairing === currentPairing) {
                            status = if (success) {
                                "Código enviado. Esperando confirmación de la TV..."
                            } else {
                                "PIN inválido o rechazado. Revisa los 6 caracteres hexadecimales."
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun ConnectionScreen(
    networkName: String,
    status: String,
    devices: List<TvDevice>,
    savedDevices: List<TvDevice>,
    pairing: TvPairing?,
    codeRequested: Boolean,
    code: String,
    onCodeChange: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: (TvDevice) -> Unit,
    onConnectManual: (String, String) -> Unit,
    onRemoveSaved: (String) -> Unit,
    onSubmitCode: () -> Unit
) {
    var ip by remember { mutableStateOf("") }
    var tvName by remember { mutableStateOf("") }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("RCGOTV", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("Control remoto para Google TV / Android TV", style = MaterialTheme.typography.bodyMedium)
                Text("Red local: $networkName", style = MaterialTheme.typography.labelMedium)
                Text(status, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                    Text("Buscar televisores")
                }
            }
            if (savedDevices.isNotEmpty()) {
                item { Text("Guardados en esta red", style = MaterialTheme.typography.titleMedium) }
                items(savedDevices, key = { "saved:${it.host}" }) { device ->
                    TvRow(device, actionLabel = "Conectar", onAction = { onConnect(device) }, onRemove = { onRemoveSaved(device.host) })
                }
            }
            if (devices.isNotEmpty()) {
                item { Text("Detectados", style = MaterialTheme.typography.titleMedium) }
                items(devices, key = { "found:${it.host}" }) { device ->
                    TvRow(device, actionLabel = "Conectar", onAction = { onConnect(device) })
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Conexión manual", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = ip,
                            onValueChange = { ip = it.filter { ch -> ch.isDigit() || ch == '.' }.take(15) },
                            label = { Text("Dirección IPv4 de la TV") },
                            placeholder = { Text("192.168.1.50") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = tvName,
                            onValueChange = { tvName = it.take(40) },
                            label = { Text("Nombre (opcional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { onConnectManual(ip, tvName) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Guardar y conectar") }
                    }
                }
            }
            if (pairing != null && codeRequested) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Autorizar en la TV", style = MaterialTheme.typography.titleMedium)
                            Text("Acepta la solicitud en el televisor e introduce el PIN de 6 caracteres (0–9 y A–F).")
                            OutlinedTextField(
                                value = code,
                                onValueChange = onCodeChange,
                                label = { Text("PIN de la TV") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(onClick = onSubmitCode, modifier = Modifier.fillMaxWidth()) { Text("Emparejar") }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.padding(4.dp))
                Text(
                    "Los perfiles guardados se separan por subred Wi-Fi. La TV debe estar encendida para la primera conexión.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun TvRow(
    device: TvDevice,
    actionLabel: String,
    onAction: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(device.host, style = MaterialTheme.typography.bodySmall)
            }
            if (onRemove != null) {
                androidx.compose.material3.TextButton(onClick = onRemove) { Text("Quitar") }
            }
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
