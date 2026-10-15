package com.example.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.example.ui.theme.*

/**
 * Modernized Text Field with MaxType typography and MaxSemanticColors tokens.
 */
@Composable
fun MaxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    isPassword: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = 4,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        label = if (label != null) { { Text(label, style = MaxType.Caption, fontWeight = FontWeight.Medium, color = MaxSemanticColors.textSecondary) } } else null,
        placeholder = { Text(placeholder, color = MaxSemanticColors.textMuted, style = MaxType.Body) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        isError = isError,
        supportingText = if (isError && errorMessage != null) { { Text(errorMessage, color = MaxSemanticColors.destructive, style = MaxType.Caption) } } else null,
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(MaxDimensions.radiusLg),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaxSemanticColors.textPrimary,
            unfocusedTextColor = MaxSemanticColors.textPrimary,
            focusedContainerColor = MaxSemanticColors.surfaceElevated,
            unfocusedContainerColor = MaxSemanticColors.surface,
            disabledContainerColor = MaxSemanticColors.background,
            focusedBorderColor = MaxSemanticColors.primaryRoyal,
            unfocusedBorderColor = MaxSemanticColors.border,
            cursorColor = MaxSemanticColors.accentCyan,
            errorBorderColor = MaxSemanticColors.destructive
        )
    )
}

