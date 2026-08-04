package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.*

@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit,
    currentRoute: String
) {
    ModalDrawerSheet(
        drawerContainerColor = SurfaceDarker,
        drawerContentColor = TextLight,
        modifier = Modifier.width(310.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(modifier = Modifier.height(24.dp))
            
            // Header Profile Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "MAX Logo",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .shadow(8.dp, spotColor = NeonBlue)
                )
                Column {
                    Text(
                        text = "MAX AI Agent",
                        color = TextLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Live Assistant v2.0",
                        color = NeonBlue,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = BorderDark, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
            
            DrawerItem(
                icon = Icons.Filled.Home,
                label = "Home Chat",
                selected = currentRoute == "home",
                onClick = { onNavigate("home") }
            )
            
            DrawerItem(
                icon = Icons.Filled.Star,
                label = "Memory Vault",
                selected = currentRoute == "memory",
                onClick = { onNavigate("memory") }
            )
            
            DrawerItem(
                icon = Icons.Filled.Favorite,
                label = "Period Tracker",
                selected = currentRoute == "period_tracker",
                onClick = { onNavigate("period_tracker") }
            )
            
            DrawerItem(
                icon = Icons.Filled.Notifications,
                label = "Reminders",
                selected = currentRoute == "reminders",
                onClick = { onNavigate("reminders") }
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
                tint = if (selected) NeonBlue else TextLight.copy(alpha = 0.7f)
            ) 
        },
        label = { 
            Text(
                text = label, 
                color = if (selected) NeonBlue else TextLight,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            ) 
        },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = NeonBlue.copy(alpha = 0.15f),
            unselectedContainerColor = SurfaceDarker
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}
