---
name: Accessibility
description: Building accessible apps for all users.
compliance_level: MANDATORY
tags: [a11y, accessibility, talkback, semantic-properties]
version: 2.2.0
---

# Accessibility

## Context
Accessibility is a legal and ethical requirement. Apps must be usable by people with disabilities, including those using TalkBack, Switch Access, and other assistive technologies.

**Related Guides:**
- [05-jetpack-compose.md](./05-jetpack-compose.md) - Compose best practices
- [22-checklists.md](./22-checklists.md) - Pre-release checklists

---

## 🎯 AI Quick Reference

```
CONTENT DESCRIPTIONS:
• Informational icons: contentDescription = "Add item"
• Decorative images: contentDescription = null
• Buttons: Derive from text content

TOUCH TARGETS:
• Minimum 48x48dp for all interactive elements
• Use Modifier.minimumInteractiveComponentSize()

SEMANTICS:
• Modifier.semantics { } for custom components
• Role.Button, Role.Checkbox, etc.
• mergeDescendants for grouped content
```

---

## 1. Content Descriptions

### ✅ DO: Proper Content Descriptions
```kotlin
// ════════════════════════════════════════════════════════════════
// Informational icons - MUST have description
// ════════════════════════════════════════════════════════════════
@Composable
fun AddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = stringResource(R.string.add_item), // ✅ Descriptive
        )
    }
}

// ════════════════════════════════════════════════════════════════
// Decorative images - Use null
// ════════════════════════════════════════════════════════════════
@Composable
fun DecorativeBackground() {
    Image(
        painter = painterResource(R.drawable.background_pattern),
        contentDescription = null, // ✅ Decorative only
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
}

// ════════════════════════════════════════════════════════════════
// Complex icons with state
// ════════════════════════════════════════════════════════════════
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(
                if (isFavorite) R.string.remove_from_favorites
                else R.string.add_to_favorites
            ), // ✅ State-aware description
            tint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ════════════════════════════════════════════════════════════════
// Images with meaningful content
// ════════════════════════════════════════════════════════════════
@Composable
fun ProductImage(
    product: Product,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = product.imageUrl,
        contentDescription = stringResource(
            R.string.product_image_description,
            product.name, // e.g., "Image of Blue Running Shoes"
        ),
        modifier = modifier,
    )
}
```

### ❌ DON'T: Poor Content Descriptions
```kotlin
// ❌ BAD: Empty string (confuses screen readers)
Icon(
    imageVector = Icons.Default.Delete,
    contentDescription = "", // ❌ Don't use empty string
)

// ❌ BAD: Generic description
Icon(
    imageVector = Icons.Default.Delete,
    contentDescription = "Icon", // ❌ Not helpful
)

// ❌ BAD: Missing description for important icon
Icon(
    imageVector = Icons.Default.Warning,
    contentDescription = null, // ❌ Warning icon should be announced
)
```

---

## 2. Touch Targets

### ✅ DO: Adequate Touch Targets
```kotlin
// ════════════════════════════════════════════════════════════════
// Using minimumInteractiveComponentSize
// ════════════════════════════════════════════════════════════════
@Composable
fun SmallIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize() // ✅ Ensures 48dp minimum
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.more_options),
            modifier = Modifier.size(24.dp), // Visual size can be smaller
        )
    }
}

// ════════════════════════════════════════════════════════════════
// Explicit minimum size
// ════════════════════════════════════════════════════════════════
@Composable
fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp) // ✅ Minimum height
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null, // Handled by row click
        )
        Spacer(Modifier.width(16.dp))
        Text(label)
    }
}
```

### ❌ DON'T: Tiny Touch Targets
```kotlin
// ❌ BAD: Icon button too small
Icon(
    imageVector = Icons.Default.Close,
    contentDescription = "Close",
    modifier = Modifier
        .size(24.dp) // ❌ Only 24dp, hard to tap
        .clickable { onClose() },
)
```

---

## 3. Semantics

### ✅ DO: Proper Semantics
```kotlin
// ════════════════════════════════════════════════════════════════
// Custom clickable component with role
// ════════════════════════════════════════════════════════════════
@Composable
fun CustomCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .semantics {
                role = Role.Button
                contentDescription = "$title. $subtitle" // Combined for TalkBack
            }
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Merge descendants for grouped content
// ════════════════════════════════════════════════════════════════
@Composable
fun ProductItem(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { // ✅ Read as one unit
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = product.imageUrl,
            contentDescription = null, // ✅ Merged, not read separately
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(product.name)
            Text("$${product.price}")
        }
    }
}

// ════════════════════════════════════════════════════════════════
// State descriptions
// ════════════════════════════════════════════════════════════════
@Composable
fun ExpandableSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                }
                .clickable(onClick = onToggle)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title)
            Icon(
                imageVector = if (isExpanded) 
                    Icons.Default.ExpandLess 
                else 
                    Icons.Default.ExpandMore,
                contentDescription = null, // State already described
            )
        }
        
        AnimatedVisibility(visible = isExpanded) {
            content()
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Heading for screen structure
// ════════════════════════════════════════════════════════════════
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier
            .semantics { heading() } // ✅ Marks as heading for navigation
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
```

---

## 4. Focus Management

### ✅ DO: Logical Focus Order
```kotlin
@Composable
fun LoginForm(
    onSubmit: (email: String, password: String) -> Unit,
) {
    val (emailFocus, passwordFocus, submitFocus) = remember { FocusRequester.createRefs() }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    
    Column {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(emailFocus),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { passwordFocus.requestFocus() }, // ✅ Move to next field
            ),
        )
        
        Spacer(Modifier.height(16.dp))
        
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSubmit(email, password) }, // ✅ Submit on done
            ),
        )
        
        Spacer(Modifier.height(24.dp))
        
        Button(
            onClick = { onSubmit(email, password) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(submitFocus),
        ) {
            Text("Sign In")
        }
    }
    
    // Auto-focus email on composition
    LaunchedEffect(Unit) {
        emailFocus.requestFocus()
    }
}
```

---

## 5. Color & Contrast

### ✅ DO: Sufficient Contrast
```kotlin
// ════════════════════════════════════════════════════════════════
// Use semantic colors from theme
// ════════════════════════════════════════════════════════════════
@Composable
fun StatusBadge(
    status: Status,
    modifier: Modifier = Modifier,
) {
    val (backgroundColor, contentColor) = when (status) {
        Status.SUCCESS -> MaterialTheme.colorScheme.primaryContainer to 
            MaterialTheme.colorScheme.onPrimaryContainer
        Status.ERROR -> MaterialTheme.colorScheme.errorContainer to 
            MaterialTheme.colorScheme.onErrorContainer
        Status.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to 
            MaterialTheme.colorScheme.onTertiaryContainer
    }
    
    Surface(
        color = backgroundColor,
        contentColor = contentColor, // ✅ Ensures proper contrast
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = status.label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
```

---

## 6. Testing Accessibility

### ✅ DO: Accessibility Tests
```kotlin
@Test
fun `product card has proper accessibility`() {
    composeTestRule.setContent {
        ProductCard(
            product = testProduct,
            onClick = {},
        )
    }
    
    // Check content description
    composeTestRule
        .onNodeWithContentDescription("Product: Test Product")
        .assertExists()
    
    // Check clickable
    composeTestRule
        .onNodeWithRole(Role.Button)
        .assertExists()
    
    // Check minimum touch target
    composeTestRule
        .onNodeWithRole(Role.Button)
        .assertHeightIsAtLeast(48.dp)
}
```

---

## 7. Verification Checklist

### Content Descriptions
- [ ] All informational icons have descriptions
- [ ] Decorative images use `null`
- [ ] State-aware descriptions where needed

### Touch Targets
- [ ] All interactive elements ≥ 48dp
- [ ] `minimumInteractiveComponentSize()` used

### Semantics
- [ ] Custom components have proper roles
- [ ] `mergeDescendants` for grouped content
- [ ] Headings marked with `heading()`

### Testing
- [ ] TalkBack tested manually
- [ ] Accessibility scanner run
- [ ] UI tests verify semantics
