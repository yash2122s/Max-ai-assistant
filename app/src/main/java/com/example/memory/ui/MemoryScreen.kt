package com.example.memory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memory.viewmodel.MemoryViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    onNavigateBack: () -> Unit
) {
    val initialMarkdown by viewModel.memoriesMarkdown.collectAsState()
    var markdownText by remember(initialMarkdown) { mutableStateOf(initialMarkdown) }
    
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    val hasUnsavedChanges = markdownText != initialMarkdown

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Permanent Memories", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("memories.md", color = TextLight.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextLight)
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            viewModel.saveMemoriesMarkdown(markdownText)
                            scope.launch {
                                snackbarHostState.showSnackbar("Saved memories.md successfully!")
                            }
                        },
                        enabled = hasUnsavedChanges,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonBlue,
                            contentColor = OnPrimaryDark,
                            disabledContainerColor = BorderDark.copy(alpha = 0.5f),
                            disabledContentColor = TextLight.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Save",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BgDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Helper guidelines box
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDarker),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "💡 AI Instructions Integration",
                        color = NeonBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "The AI reads this file directly. Write facts, preferences, or rules in Markdown list format (- text) for the best results. E.g., '- My name is Yaswanth.'",
                        color = TextLight.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // Monospace Editor Card
            OutlinedTextField(
                value = markdownText,
                onValueChange = { markdownText = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = TextLight,
                    lineHeight = 20.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight,
                    cursorColor = NeonBlue,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark
                ),
                shape = RoundedCornerShape(12.dp),
                placeholder = { 
                    Text(
                        "# Permanent Memories\n\n- Write something here...", 
                        color = TextLight.copy(alpha = 0.3f),
                        fontFamily = FontFamily.Monospace
                    ) 
                }
            )
            
            // Bottom stats bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${markdownText.length} characters",
                    color = TextLight.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
                if (hasUnsavedChanges) {
                    Text(
                        text = "● Unsaved changes",
                        color = NeonPink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "✓ Up to date",
                        color = NeonGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
