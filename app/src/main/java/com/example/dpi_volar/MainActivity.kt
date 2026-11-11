package com.example.dpi_volar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.dpi_volar.ui.theme.*
import kotlinx.coroutines.launch

private const val GITHUB_URL = "https://github.com/PetyaFOSS/DPI-Volar"

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            DPIVolarTheme {
                val vpnState by MyVpnService.state.collectAsState()
                AppRoot(
                    vpnState = vpnState,
                    onToggle = { toggleProtection(vpnState) }
                )
            }
        }
    }

    private fun toggleProtection(currentState: VpnState) {
        when (currentState) {
            VpnState.Running, VpnState.Starting -> stopVpnService()
            else -> requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "No se pudo iniciar la protección: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopVpnService() {
        val stopIntent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_STOP
        }
        try {
            startService(stopIntent)
        } catch (_: Exception) {
        }
        stopService(Intent(this, MyVpnService::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vpnState: VpnState, onToggle: () -> Unit) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val version = rememberAppVersion()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawerContent(
                version = version,
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        AppBackground {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    AppTopBar(
                        vpnState = vpnState,
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                }
            ) { padding ->
                ProtectionScreen(
                    modifier = Modifier.padding(padding),
                    vpnState = vpnState,
                    onToggle = onToggle
                )
            }
        }
    }
}

@Composable
private fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.background,
                        scheme.surfaceVariant.copy(alpha = 0.45f),
                        scheme.background
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 90.dp, y = (-70).dp)
                .size(260.dp)
                .background(
                    Brush.radialGradient(
                        listOf(scheme.primary.copy(alpha = 0.14f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-80).dp, y = 70.dp)
                .size(240.dp)
                .background(
                    Brush.radialGradient(
                        listOf(scheme.tertiary.copy(alpha = 0.10f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(vpnState: VpnState, onOpenDrawer: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "DPI-Volar",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Rounded.Menu, contentDescription = "Menú")
            }
        },
        actions = {
            StatusPill(vpnState)
            Spacer(modifier = Modifier.width(12.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
fun AppDrawerContent(version: String, onCloseDrawer: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight(),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppLogo(size = 52.dp, iconSize = 28.dp)
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "DPI-Volar",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v$version · Protección local",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            NavigationDrawerItem(
                label = { Text("Protección DPI") },
                selected = true,
                icon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
                onClick = onCloseDrawer,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Ajustes (próximamente)") },
                selected = false,
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                onClick = onCloseDrawer,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Código en GitHub") },
                selected = false,
                icon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                onClick = {
                    onCloseDrawer()
                    uriHandler.openUri(GITHUB_URL)
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Herramienta local para evadir DPI.\nNo oculta tu IP ni cambia tu ubicación.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}

@Composable
fun ProtectionScreen(
    modifier: Modifier = Modifier,
    vpnState: VpnState,
    onToggle: () -> Unit
) {
    val errorMessage = (vpnState as? VpnState.Error)?.message

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroCard(vpnState = vpnState)

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(14.dp))
            ErrorCard(message = errorMessage)
        }

        Spacer(modifier = Modifier.height(18.dp))
        ConnectButton(vpnState = vpnState, onToggle = onToggle)
        Spacer(modifier = Modifier.height(20.dp))
        FeatureRow()
        Spacer(modifier = Modifier.height(20.dp))
        HowItWorksCard()
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Procesamiento local · Sin telemetría",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun HeroCard(vpnState: VpnState) {
    val isRunning = vpnState is VpnState.Running
    val isStarting = vpnState is VpnState.Starting
    val hasError = vpnState is VpnState.Error

    val statusColor = when {
        isRunning -> MaterialTheme.colorScheme.tertiary
        isStarting -> MaterialTheme.colorScheme.primary
        hasError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val gradient = when {
        isRunning -> listOf(ActiveGradientStart, ActiveGradientEnd)
        hasError -> listOf(StopGradientStart, StopGradientEnd)
        else -> listOf(HeroGradientStart, HeroGradientEnd)
    }

    val title = when {
        isRunning -> "Protección activa"
        isStarting -> "Iniciando protección…"
        hasError -> "No se pudo activar"
        else -> "Protección inactiva"
    }

    val subtitle = when {
        isRunning -> "Tu tráfico se está procesando en este dispositivo."
        isStarting -> "Creando la interfaz VPN y preparando el túnel."
        hasError -> "Revisa el mensaje y vuelve a intentarlo."
        else -> "Activa DPI-Volar para empezar a proteger tu conexión."
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                shape = RoundedCornerShape(32.dp)
            ),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(158.dp)
                        .scale(if (isRunning) pulse else 1f)
                        .background(
                            Brush.radialGradient(
                                listOf(statusColor.copy(alpha = 0.22f), Color.Transparent)
                            ),
                            CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(116.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(colors = gradient)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Rounded.VerifiedUser else Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            StatusPill(vpnState)
        }
    }
}

@Composable
private fun StatusPill(vpnState: VpnState) {
    val (label, foreground, background) = when (vpnState) {
        VpnState.Running -> Triple(
            "ACTIVA",
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.tertiaryContainer
        )
        VpnState.Starting -> Triple(
            "INICIANDO",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer
        )
        is VpnState.Error -> Triple(
            "ERROR",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.errorContainer
        )
        VpnState.Stopped -> Triple(
            "INACTIVA",
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant
        )
    }

    Surface(
        shape = CircleShape,
        color = background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(foreground, CircleShape)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = foreground
            )
        }
    }
}

@Composable
private fun ConnectButton(vpnState: VpnState, onToggle: () -> Unit) {
    val isRunning = vpnState is VpnState.Running
    val isStarting = vpnState is VpnState.Starting

    val gradient = when {
        isRunning -> listOf(StopGradientStart, StopGradientEnd)
        else -> listOf(HeroGradientStart, HeroGradientEnd)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .shadow(elevation = 10.dp, shape = RoundedCornerShape(22.dp), clip = false)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(gradient))
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isStarting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = when {
                    isRunning -> "Detener protección"
                    isStarting -> "Iniciando…"
                    else -> "Activar protección"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun FeatureRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FeatureCard(
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
            label = "100% local"
        )
        FeatureCard(
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Rounded.CloudOff, contentDescription = null) },
            label = "Sin servidores"
        )
        FeatureCard(
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
            label = "TLS + SNI"
        )
    }
}

@Composable
private fun FeatureCard(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.primary
            ) {
                Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    icon()
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HowItWorksCard() {
    val uriHandler = LocalUriHandler.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "¿Cómo funciona?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "DPI-Volar intercepta el tráfico con VpnService, detecta el ClientHello de TLS y fragmenta el handshake para que el DPI no pueda identificar el dominio. Todo ocurre en tu teléfono.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.clickable { uriHandler.openUri(GITHUB_URL) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ver el código en GitHub",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "No se pudo activar la protección",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun AppLogo(size: Dp, iconSize: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(HeroGradientStart, HeroGradientEnd))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Shield,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember(context) { packageVersion(context) }
}

@Suppress("DEPRECATION")
private fun packageVersion(context: Context): String {
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()
}
