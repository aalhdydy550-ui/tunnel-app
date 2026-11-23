package dev.aalhdydy550.tunnel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.aalhdydy550.tunnel.core.ui.TunnelTheme
import dev.aalhdydy550.tunnel.feature.connection.ConnectionViewModel
import dev.aalhdydy550.tunnel.feature.profiles.ImportScreen
import dev.aalhdydy550.tunnel.feature.profiles.ImportViewModel
import dev.aalhdydy550.tunnel.feature.profiles.ProfilesViewModel
import dev.aalhdydy550.tunnel.feature.profiles.ServersScreen
import kotlinx.coroutines.launch

/**
 * Single activity, Hamo-style shell:
 *
 *   yellow drawer header (our logo + name + version) with working
 *   menu items only (servers / import / GitHub repo / About dialog)
 *   → light top bar: our logo + "Ayman Tunnel Plus" + version
 *   → servers screen (banner + status + big round button + list)
 *   → import screen (opened from the drawer or the yellow "+" FAB)
 *
 * The app name is visible the moment the app opens — in the top bar,
 * the drawer header, and the About card/dialog — as the user asked.
 * No ads, no copied assets: the logo is our own hacker logo.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TunnelTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                var screen by remember { mutableStateOf(Screen.SERVERS) }
                var showAbout by remember { mutableStateOf(false) }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            // Yellow header (Hamo's drawer header is
                            // yellow with the app icon + name).
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF8CE29))
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_app_logo),
                                    contentDescription = "شعار Ayman Tunnel Plus",
                                    modifier = Modifier.size(80.dp),
                                    contentScale = ContentScale.Fit,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Ayman Tunnel Plus",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1C1C1C),
                                )
                                Text(
                                    text = "الإصدار 0.1.0",
                                    fontSize = 11.sp,
                                    color = Color(0xFF1C1C1C),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            NavigationDrawerItem(
                                label = { Text("الخوادم") },
                                selected = screen == Screen.SERVERS,
                                onClick = {
                                    screen = Screen.SERVERS
                                    scope.launch { drawerState.close() }
                                },
                            )
                            NavigationDrawerItem(
                                label = { Text("استيراد") },
                                selected = screen == Screen.IMPORT,
                                onClick = {
                                    screen = Screen.IMPORT
                                    scope.launch { drawerState.close() }
                                },
                            )
                            NavigationDrawerItem(
                                label = { Text("المستودع على GitHub") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(
                                                "https://github.com/aalhdydy550-ui/tunnel-app",
                                            ),
                                        ),
                                    )
                                },
                            )
                            NavigationDrawerItem(
                                label = { Text("حول التطبيق") },
                                selected = false,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showAbout = true
                                },
                            )
                        }
                    },
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                                navigationIcon = {
                                    if (screen == Screen.IMPORT) {
                                        IconButton(onClick = { screen = Screen.SERVERS }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "رجوع",
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = { scope.launch { drawerState.open() } },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Menu,
                                                contentDescription = "القائمة",
                                            )
                                        }
                                    }
                                },
                                title = {
                                    // Hamo's toolbar: app icon + name +
                                    // a small second line.
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Image(
                                            painter = painterResource(R.drawable.ic_app_logo),
                                            contentDescription = null,
                                            modifier = Modifier.size(35.dp),
                                            contentScale = ContentScale.Fit,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "Ayman Tunnel Plus",
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = "الإصدار 0.1.0",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                            )
                        },
                        floatingActionButton = {
                            if (screen == Screen.SERVERS) {
                                FloatingActionButton(
                                    onClick = { screen = Screen.IMPORT },
                                    containerColor = Color(0xFFF8CE29),
                                    contentColor = Color(0xFF1C1C1C),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "استيراد خادم",
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            when (screen) {
                                Screen.SERVERS -> ServersScreen(
                                    connectionViewModel = hiltViewModel<ConnectionViewModel>(),
                                    profilesViewModel = hiltViewModel<ProfilesViewModel>(),
                                )
                                Screen.IMPORT -> ImportScreen(
                                    viewModel = hiltViewModel<ImportViewModel>(),
                                )
                            }
                        }
                    }
                }

                if (showAbout) {
                    AlertDialog(
                        onDismissRequest = { showAbout = false },
                        title = { Text("Ayman Tunnel Plus") },
                        text = {
                            Text(
                                "الإصدار 0.1.0 — النواة sing-box 1.14.4 (GPL-3.0)\n" +
                                    "بناء نظيف: بلا إعلانات، بلا تتبّع، بلا بيانات " +
                                    "دخول مدمجة.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showAbout = false }) {
                                Text("إغلاق")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = {
                                    showAbout = false
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(
                                                "https://github.com/aalhdydy550-ui/tunnel-app",
                                            ),
                                        ),
                                    )
                                },
                            ) { Text("المستودع على GitHub") }
                        },
                    )
                }
            }
        }
    }

    private enum class Screen { SERVERS, IMPORT }
}
