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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * iOS Inset Grouped Text Field with refined dark background, hairline border, and active focus state.
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
        label = if (label != null) { { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaxTextSecondary) } } else null,
        placeholder = { Text(placeholder, color = MaxTextTertiary, fontSize = 15.sp) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        isError = isError,
        supportingText = if (isError && errorMessage != null) { { Text(errorMessage, color = MaxDestructiveApple, fontSize = 12.sp) } } else null,
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(MaxDimensions.radiusLg),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaxTextPrimary,
            unfocusedTextColor = MaxTextPrimary,
            focusedContainerColor = MaxSurfaceElevated,
            unfocusedContainerColor = MaxSurfaceCard,
            disabledContainerColor = MaxBaseDark,
            focusedBorderColor = MaxPrimaryRoyal,
            unfocusedBorderColor = MaxBorderHairline,
            cursorColor = MaxAccentCyan,
            errorBorderColor = MaxDestructiveApple
        )
    )
}

