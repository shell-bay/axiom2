package com.alpineterminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0D1117)
private val Surface = Color(0xFF161B22)
private val AccentGreen = Color(0xFF3FB950)
private val AccentBlue = Color(0xFF1F6FEB)
private val AccentOrange = Color(0xFFD29922)
private val TextMain = Color(0xFFE6EDF3)
private val TextDim = Color(0xFF8B949E)

@Composable
fun PackageInstallerScreen(viewModel: PackageInstallerViewModel) {
    val isInstalling by viewModel.isInstalling
    val installLog by viewModel.installLog
    val logState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(16.dp)
    ) {
        Text(
            text = "Package Manager",
            color = TextMain,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Alpine Linux apk packages",
            color = TextDim,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.updatePackages() },
                enabled = isInstalling == null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Update Index", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { viewModel.upgradePackages() },
                enabled = isInstalling == null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentOrange)
            ) {
                Icon(Icons.Default.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Upgrade All", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (installLog.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF000000).copy(alpha = 0.3f))
            ) {
                LazyColumn(state = logState, modifier = Modifier.padding(8.dp)) {
                    item {
                        Text(
                            text = installLog,
                            color = AccentGreen,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(viewModel.popularPackages) { pkg ->
                PackageItem(
                    pkg = pkg,
                    isInstalling = isInstalling == pkg.packageName,
                    isInstalled = viewModel.packageStatus.value.containsKey(pkg.packageName),
                    onInstallClick = { viewModel.installPackage(pkg.packageName) },
                    onUninstallClick = { viewModel.uninstallPackage(pkg.packageName) }
                )
            }
        }
    }
}

@Composable
private fun PackageItem(
    pkg: PackageInfo,
    isInstalling: Boolean,
    isInstalled: Boolean,
    onInstallClick: () -> Unit,
    onUninstallClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pkg.displayName,
                        color = TextMain,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isInstalled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = pkg.description,
                    color = TextDim,
                    fontSize = 11.sp
                )
                Text(
                    text = "apk add ${pkg.packageName}",
                    color = AccentBlue.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isInstalling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = AccentGreen
                )
            } else if (isInstalled) {
                FilledTonalButton(
                    onClick = onUninstallClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF3A1A1A))
                ) {
                    Text("Remove", fontSize = 11.sp, color = Color(0xFFF85149))
                }
            } else {
                Button(
                    onClick = onInstallClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("Add", fontSize = 11.sp, color = Color.White)
                }
            }
        }
    }
}
