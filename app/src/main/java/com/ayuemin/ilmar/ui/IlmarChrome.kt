package com.ayuemin.ilmar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Adapted from Ayuemin/Umnik's UmnikChrome and EmptyChatWelcome.
val IlmarPanelShape = RoundedCornerShape(22.dp)
val IlmarFieldShape = RoundedCornerShape(18.dp)

@Composable
fun IlmarCircleAction(
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp),
        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun IlmarWelcome(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("✦", style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Чем могу помочь?", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
    }
}
