package com.example.privatednstoggle

import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var repository: DnsRepository
    private val refreshTrigger = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = DnsRepository(applicationContext)

        setContent {
            MaterialTheme(colorScheme = dynamicOrDefaultColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PrivateDnsScreen(
                        repository = repository,
                        refreshKey = refreshTrigger.intValue,
                        onRequestRefresh = {
                            refreshTrigger.intValue++
                            PrivateDnsTileService.requestTileSync(this)
                        },
                        onRequestAddTile = { requestAddQuickSettingsTile() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger.intValue++
        PrivateDnsTileService.requestTileSync(this)
    }

    /**
     * На Android 13+ (API 33+) вызывает системный диалог добавления плитки в шторку.
     */
    private fun requestAddQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            statusBarManager?.requestAddTileService(
                ComponentName(this, PrivateDnsTileService::class.java),
                "Частный DNS",
                Icon.createWithResource(this, R.drawable.ic_qs_dns),
                mainExecutor
            ) { result ->
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        "Переключатель добавлен в шторку быстрых настроек"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        "Переключатель уже находится в шторке быстрых настроек"
                    else ->
                        "Вы можете добавить плитку вручную через редактирование шторки"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(
                this,
                "Откройте шторку уведомлений -> Изменить (карандаш) и перетащите плитку «Частный DNS»",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

@Composable
private fun dynamicOrDefaultColorScheme(): ColorScheme {
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        lightColorScheme()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateDnsScreen(
    repository: DnsRepository,
    refreshKey: Int,
    onRequestRefresh: () -> Unit,
    onRequestAddTile: () -> Unit
) {
    val context = LocalContext.current
    val state = remember(refreshKey) { repository.getState() }

    var hostnameInput by remember(state.currentHostname) { mutableStateOf(state.currentHostname) }
    var nameInput by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    val adbCommand = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Частный DNS", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onRequestAddTile) {
                        Text("Плитка в шторку")
                    }
                    IconButton(onClick = onRequestRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить статус")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    nameInput = ""
                    hostnameInput = ""
                    showAddDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Новый DNS") }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (!state.hasPermission) {
                item {
                    PermissionRequiredCard(
                        adbCommand = adbCommand,
                        onCopyCommand = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCommand))
                            Toast.makeText(context, "Команда ADB скопирована", Toast.LENGTH_SHORT).show()
                        },
                        onCheckAgain = onRequestRefresh
                    )
                }
            }

            item {
                ModeSelectorCard(
                    state = state,
                    hostnameInput = hostnameInput,
                    onHostnameChange = { hostnameInput = it },
                    onSelectOff = {
                        repository.disablePrivateDns()
                            .onSuccess {
                                Toast.makeText(context, "Частный DNS выключен", Toast.LENGTH_SHORT).show()
                                onRequestRefresh()
                            }
                            .onFailure { e ->
                                Toast.makeText(context, e.localizedMessage ?: "Ошибка", Toast.LENGTH_LONG).show()
                            }
                    },
                    onSelectHostnameMode = { targetHost ->
                        repository.enablePrivateDns(targetHost)
                            .onSuccess {
                                Toast.makeText(context, "Включен DNS: $targetHost", Toast.LENGTH_SHORT).show()
                                onRequestRefresh()
                            }
                            .onFailure { e ->
                                Toast.makeText(context, e.localizedMessage ?: "Ошибка", Toast.LENGTH_LONG).show()
                            }
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Быстрый выбор (недавние и сохраненные)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${state.savedServers.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            items(state.savedServers, key = { it.hostname }) { server ->
                val isCurrentActive = state.mode == PrivateDnsMode.HOSTNAME &&
                        state.currentHostname.equals(server.hostname, ignoreCase = true)

                SavedDnsServerItem(
                    server = server,
                    isActive = isCurrentActive,
                    onSelectAndApply = {
                        hostnameInput = server.hostname
                        repository.enablePrivateDns(server.hostname, server.name)
                            .onSuccess {
                                Toast.makeText(
                                    context,
                                    "Активирован: ${server.name} (${server.hostname})",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onRequestRefresh()
                            }
                            .onFailure { e ->
                                Toast.makeText(context, e.localizedMessage ?: "Ошибка", Toast.LENGTH_LONG).show()
                            }
                    },
                    onDelete = {
                        repository.deleteDnsServer(server.hostname)
                        onRequestRefresh()
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (showAddDialog) {
        AddDnsServerDialog(
            initialName = nameInput,
            initialHostname = hostnameInput,
            onDismiss = { showAddDialog = false },
            onSaveOnly = { name, host ->
                repository.saveDnsServer(host, name)
                    .onSuccess {
                        showAddDialog = false
                        onRequestRefresh()
                        Toast.makeText(context, "Сервер сохранен в список", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { e ->
                        Toast.makeText(context, e.localizedMessage ?: "Ошибка", Toast.LENGTH_LONG).show()
                    }
            },
            onSaveAndActivate = { name, host ->
                repository.enablePrivateDns(host, name)
                    .onSuccess {
                        showAddDialog = false
                        onRequestRefresh()
                        Toast.makeText(context, "Сервер сохранен и включен", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { e ->
                        Toast.makeText(context, e.localizedMessage ?: "Ошибка", Toast.LENGTH_LONG).show()
                    }
            }
        )
    }
}

@Composable
private fun PermissionRequiredCard(
    adbCommand: String,
    onCopyCommand: () -> Unit,
    onCheckAgain: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Требуется однократная выдача разрешения",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Чтобы менять системный Частный DNS напрямую без использования VPN, Android требует разрешение WRITE_SECURE_SETTINGS. Выдайте его один раз через ПК (ADB) или Shizuku:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = adbCommand,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onCopyCommand) {
                    Text("Скопировать команду")
                }
                OutlinedButton(onClick = onCheckAgain) {
                    Text("Проверить снова")
                }
            }
        }
    }
}

@Composable
private fun ModeSelectorCard(
    state: PrivateDnsState,
    hostnameInput: String,
    onHostnameChange: (String) -> Unit,
    onSelectOff: () -> Unit,
    onSelectHostnameMode: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Режим работы Частного DNS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectOff() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = state.mode == PrivateDnsMode.OFF,
                    onClick = onSelectOff
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = PrivateDnsMode.OFF.titleRu,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Частный DNS отключен (используется стандартный DNS сети)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectHostnameMode(hostnameInput) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = state.mode == PrivateDnsMode.HOSTNAME,
                    onClick = { onSelectHostnameMode(hostnameInput) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = PrivateDnsMode.HOSTNAME.titleRu,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (state.mode == PrivateDnsMode.HOSTNAME) {
                            "Активен: ${state.currentHostname}"
                        } else {
                            "Включить с выбранным ниже хостом провайдера"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.mode == PrivateDnsMode.HOSTNAME) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            OutlinedTextField(
                value = hostnameInput,
                onValueChange = onHostnameChange,
                label = { Text("Адрес хоста провайдера DNS (DoT)") },
                placeholder = { Text("например, dns.adguard-dns.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { onSelectHostnameMode(hostnameInput) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Применить и включить этот DNS")
            }
        }
    }
}

@Composable
private fun SavedDnsServerItem(
    server: SavedDnsServer,
    isActive: Boolean,
    onSelectAndApply: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectAndApply() },
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Активен",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = server.hostname,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (server.lastUsedAt > 0L) {
                    Text(
                        text = "Использован: ${dateFormat.format(Date(server.lastUsedAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить сервер",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddDnsServerDialog(
    initialName: String,
    initialHostname: String,
    onDismiss: () -> Unit,
    onSaveOnly: (String, String) -> Unit,
    onSaveAndActivate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var hostname by remember { mutableStateOf(initialHostname) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить новый DNS-сервер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название (необязательно)") },
                    placeholder = { Text("Например: Мой AdGuard / NextDNS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it },
                    label = { Text("Имя хоста провайдера DNS") },
                    placeholder = { Text("dns.adguard-dns.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSaveAndActivate(name, hostname) }) {
                Text("Сохранить и включить")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
                TextButton(onClick = { onSaveOnly(name, hostname) }) {
                    Text("Только сохранить")
                }
            }
        }
    )
}
