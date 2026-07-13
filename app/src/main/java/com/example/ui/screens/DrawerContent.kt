package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit,
    currentRoute: String
) {
    ModalDrawerSheet(
        drawerContainerColor = BgDark,
        drawerContentColor = TextLight,
        modifier = Modifier.width(300.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "MAX",
                color = NeonBlue,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = BorderDark, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
            
            DrawerItem(
                icon = Icons.Filled.Home,
                label = "Home",
                selected = currentRoute == "home",
                onClick = { onNavigate("home") }
            )
            
            DrawerItem(
                icon = Icons.Filled.Settings,
                label = "Settings",
                selected = currentRoute == "settings",
                onClick = { onNavigate("settings") }
            )
            
            DrawerItem(
                icon = Icons.Filled.Security,
                label = "Permissions",
                selected = currentRoute == "permissions",
                onClick = { onNavigate("permissions") }
            )
            
            DrawerItem(
                icon = Icons.Filled.Lock,
                label = "Privacy Policy",
                selected = currentRoute == "privacy",
                onClick = { onNavigate("privacy") }
            )
            
            DrawerItem(
                icon = Icons.Filled.Info,
                label = "About",
                selected = currentRoute == "about",
                onClick = { onNavigate("about") }
            )
        }
    }
}

@Composable
fun DrawerItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon = { 
            Icon(
                imageVector = icon, 
                contentDescription = null,
                tint = if (selected) NeonPink else TextLight.copy(alpha = 0.7f)
            ) 
        },
        label = { 
            Text(
                text = label, 
                color = if (selected) NeonPink else TextLight,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            ) 
        },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = NeonPink.copy(alpha = 0.1f),
            unselectedContainerColor = BgDark
        ),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}
