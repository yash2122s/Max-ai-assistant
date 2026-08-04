package com.example.memory.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.memory.config.MemoryConfig
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, MemoryType, Boolean, String) -> Unit,
    isLoading: Boolean,
    error: String?
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(MemoryCategory.PERSONAL.name) }
    var selectedType by remember { mutableStateOf(MemoryType.FACT) }
    var pinned by remember { mutableStateOf(false) }
    var customCategory by remember { mutableStateOf("") }
    var showCategoryDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = { Text("Add Memory", color = TextLight, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title", color = TextLight.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedLabelColor = NeonBlue,
                        unfocusedLabelColor = TextLight.copy(alpha = 0.6f),
                        cursorColor = NeonBlue
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { if (it.length <= MemoryConfig.MAX_CONTENT_LENGTH) content = it },
                    label = { Text("Content", color = TextLight.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedLabelColor = NeonBlue,
                        unfocusedLabelColor = TextLight.copy(alpha = 0.6f),
                        cursorColor = NeonBlue
                    ),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2,
                    maxLines = 4
                )

                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags (e.g. hardware, person:jay)", color = TextLight.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedLabelColor = NeonBlue,
                        unfocusedLabelColor = TextLight.copy(alpha = 0.6f),
                        cursorColor = NeonBlue
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Text(
                    text = "${content.length}/${MemoryConfig.MAX_CONTENT_LENGTH}",
                    color = if (content.length > MemoryConfig.MAX_CONTENT_LENGTH - 100) NeonPink else TextLight.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )

                Text("Category", color = TextLight, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                ExposedDropdownMenuBox(
                    expanded = showCategoryDropdown,
                    onExpandedChange = { showCategoryDropdown = it }
                ) {
                    OutlinedTextField(
                        value = if (selectedCategory == MemoryCategory.CUSTOM.name && customCategory.isNotEmpty()) customCategory else MemoryCategory.valueOf(selectedCategory).displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryDropdown) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonBlue,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedLabelColor = NeonBlue,
                            unfocusedLabelColor = TextLight.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = showCategoryDropdown,
                        onDismissRequest = { showCategoryDropdown = false },
                        modifier = Modifier.background(SurfaceDarker)
                    ) {
                        MemoryCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.displayName, color = TextLight) },
                                onClick = {
                                    selectedCategory = category.name
                                    showCategoryDropdown = false
                                }
                            )
                        }
                    }
                }

                if (selectedCategory == MemoryCategory.CUSTOM.name) {
                    OutlinedTextField(
                        value = customCategory,
                        onValueChange = { customCategory = it },
                        label = { Text("Custom Category", color = TextLight.copy(alpha = 0.6f)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonBlue,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedLabelColor = NeonBlue,
                            unfocusedLabelColor = TextLight.copy(alpha = 0.6f),
                            cursorColor = NeonBlue
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }

                Text("Type", color = TextLight, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemoryType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.name, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonBlue.copy(alpha = 0.2f),
                                selectedLabelColor = NeonBlue,
                                containerColor = SurfaceDark,
                                labelColor = TextLight.copy(alpha = 0.6f)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (selectedType == type) NeonBlue else BorderDark
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pin this memory", color = TextLight, fontSize = 14.sp)
                    Switch(
                        checked = pinned,
                        onCheckedChange = { pinned = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonBlue,
                            checkedTrackColor = NeonBlue.copy(alpha = 0.4f),
                            uncheckedThumbColor = TextLight.copy(alpha = 0.6f),
                            uncheckedTrackColor = BorderDark
                        )
                    )
                }

                error?.let {
                    Text(text = it, color = NeonPink, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(title, content, if (selectedCategory == MemoryCategory.CUSTOM.name && customCategory.isNotEmpty()) customCategory else selectedCategory, selectedType, pinned, tags) },
                enabled = !isLoading && title.isNotBlank() && content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextLight, strokeWidth = 2.dp)
                } else {
                    Text("Add", color = OnPrimaryDark, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextLight)
            }
        },
        containerColor = SurfaceDarker
    )
}
