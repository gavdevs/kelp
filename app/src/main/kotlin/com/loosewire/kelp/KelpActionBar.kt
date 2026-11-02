package com.loosewire.kelp

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

// The SDK bottom bar pads and spaces intrinsic buttons; these slots make the
// whole available width touchable, including the gaps between the icons.
@Composable
internal fun KelpActionBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.25f.gridUnitsAsDp())
            .height(4f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun RowScope.KelpActionIcon(
    icon: LightIconConfiguration,
    contentDescription: String,
    alpha: Float = 1f,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(
            icon = icon,
            size = 2f,
            contentDescription = contentDescription,
            modifier = Modifier.alpha(alpha),
        )
    }
}
