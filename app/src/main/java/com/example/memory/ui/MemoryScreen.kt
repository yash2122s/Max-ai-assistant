package com.example.memory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.viewmodel.MemoryViewModel
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    onNavigateBack: () -> Unit
) {
    val allMemories by viewModel.allMemories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    
    val showAddDialog by viewModel.showAddDialog.collectAsState()
    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editingMemory by viewModel.editingMemory.collectAsState()
    
    var selectedCategoryTitle by remember { mutableStateOf<String?>(null) }
    var newMemoryInput by remember { mutableStateOf("") }
    
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.US) }
    
    // Default system categories if empty
    val defaultYouTopics = listOf("Preferences", "Profile")
    val defaultGeneralTopics = listOf(
        "Content Creation", "Hardware", "Learning", 
        "Network Devices", "Past Projects", "Recent Work", "Tech Interests"
    )

    // Grouping memories by topic / category / tag
    val groupedMemories = remember(allMemories) {
        val map = mutableMapOf<String, MutableList<PermanentMemory>>()
        for (mem in allMemories) {
            val key = when {
                mem.tags.isNotBlank() -> mem.tags.split(",").firstOrNull()?.trim()?.capitalize(Locale.ROOT) ?: "General"
                mem.category.isNotBlank() -> mem.category
                else -> mem.type.name.capitalize(Locale.ROOT)
            }
            map.getOrPut(key) { mutableListOf() }.add(mem)
        }
        map
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text("Memory files", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextLight)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showAddDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Memory", tint = NeonBlue)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF141416))
            )
        },
        containerColor = Color(0xFF141416)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp)
                ) {
                    // SECTION 1: YOU
                    item {
                        Column {
                            Text(
                                text = "You",
                                color = TextLight.copy(alpha = 0.7f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22))
                            ) {
                                Column {
                                    defaultYouTopics.forEachIndexed { index, topicName ->
                                        val topicMems = groupedMemories[topicName] ?: emptyList()
                                        val latestDate = topicMems.maxOfOrNull { it.updatedAt } ?: System.currentTimeMillis()
                                        val dateStr = dateFormat.format(Date(latestDate))
                                        
                                        MemoryFileCardRow(
                                            title = topicName,
                                            subtitle = "Updated $dateStr",
                                            count = topicMems.size,
                                            onClick = { selectedCategoryTitle = topicName }
                                        )
                                        
                                        if (index < defaultYouTopics.size - 1) {
                                            HorizontalDivider(color = Color(0xFF2A2A30), thickness = 1.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 2: TOPICS
                    item {
                        Column {
                            Text(
                                text = "Topics",
                                color = TextLight.copy(alpha = 0.7f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22))
                            ) {
                                Column {
                                    // Merge default topic list with user-created dynamic tags/topics
                                    val dynamicTopicNames = (defaultGeneralTopics + groupedMemories.keys)
                                        .distinct()
                                        .filter { it !in defaultYouTopics }
                                        .sorted()
                                        
                                    dynamicTopicNames.forEachIndexed { index, topicName ->
                                        val topicMems = groupedMemories[topicName] ?: emptyList()
                                        val latestDate = topicMems.maxOfOrNull { it.updatedAt } ?: System.currentTimeMillis()
                                        val dateStr = dateFormat.format(Date(latestDate))
                                        
                                        MemoryFileCardRow(
                                            title = topicName,
                                            subtitle = "Updated $dateStr",
                                            count = topicMems.size,
                                            onClick = { selectedCategoryTitle = topicName }
                                        )
                                        
                                        if (index < dynamicTopicNames.size - 1) {
                                            HorizontalDivider(color = Color(0xFF2A2A30), thickness = 1.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // BOTTOM CAPSULE INPUT BAR ("Tell MAX what to remember")
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF24242A)),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newMemoryInput,
                        onValueChange = { newMemoryInput = it },
                        placeholder = { 
                            Text("Tell MAX what to remember", color = TextLight.copy(alpha = 0.5f), fontSize = 14.sp) 
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            cursorColor = NeonBlue,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (newMemoryInput.isNotBlank()) NeonBlue else Color(0xFF383842))
                            .clickable(enabled = newMemoryInput.isNotBlank()) {
                                val textToSave = newMemoryInput.trim()
                                newMemoryInput = ""
                                val hashtags = Regex("#([\\w:]+)").findAll(textToSave).map { it.groupValues[1] }.toList()
                                val tagStr = hashtags.joinToString(",")
                                viewModel.addMemory(
                                    title = if (textToSave.length > 25) textToSave.take(25) + "..." else textToSave,
                                    content = textToSave,
                                    category = if (tagStr.isNotBlank()) tagStr.split(",").first().capitalize(Locale.ROOT) else MemoryCategory.PERSONAL.displayName,
                                    type = MemoryType.FACT,
                                    tags = tagStr
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Remember",
                            tint = if (newMemoryInput.isNotBlank()) Color.Black else TextLight.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    // Detail Modal Sheet when tapping a Memory File topic card
    selectedCategoryTitle?.let { topicName ->
        val topicMemories = groupedMemories[topicName] ?: emptyList()
        AlertDialog(
            onDismissRequest = { selectedCategoryTitle = null },
            title = { Text(topicName, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (topicMemories.isEmpty()) {
                        Text(
                            "No saved entries under '$topicName' yet.\nType below to add facts or preferences!",
                            color = TextLight.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(topicMemories) { _, memory ->
                                MemoryItemRow(
                                    memory = memory,
                                    onEdit = { 
                                        selectedCategoryTitle = null
                                        viewModel.showEditDialog(memory)
                                    },
                                    onDelete = { viewModel.deleteMemory(memory.memoryId) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedCategoryTitle = null }) {
                    Text("Close", color = NeonBlue, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF1E1E22),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showAddDialog) {
        AddMemoryDialog(
            onDismiss = { viewModel.hideAddDialog() },
            onAdd = { title, content, category, type, pinned, tags ->
                viewModel.addMemory(title, content, category, type, pinned, tags)
            },
            isLoading = isLoading,
            error = error
        )
    }

    if (showEditDialog && editingMemory != null) {
        EditMemoryDialog(
            memory = editingMemory!!,
            onDismiss = { viewModel.hideEditDialog() },
            onSave = { updatedMemory -> viewModel.updateMemory(updatedMemory) },
            isLoading = isLoading,
            error = error
        )
    }
}

@Composable
fun MemoryFileCardRow(
    title: String,
    subtitle: String,
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                color = TextLight,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextLight.copy(alpha = 0.5f),
                fontSize = 13.sp
            )
        }
        if (count > 0) {
            Surface(
                shape = CircleShape,
                color = NeonBlue.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "$count",
                    color = NeonBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun MemoryItemRow(
    memory: PermanentMemory,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF282830))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(memory.title, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(memory.content, color = TextLight.copy(alpha = 0.8f), fontSize = 13.sp)
                if (memory.tags.isNotBlank()) {
                    Text("Tags: ${memory.tags}", color = NeonBlue, fontSize = 11.sp)
                }
            }
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextLight.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = NeonPink, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
