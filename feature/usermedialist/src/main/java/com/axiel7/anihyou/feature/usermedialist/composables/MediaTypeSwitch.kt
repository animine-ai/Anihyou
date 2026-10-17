package com.axiel7.anihyou.feature.usermedialist.composables

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.resources.R

/**
 * The two-segment Anime / Manga switch of the library, in the manner of AniSync: connected segments, the selected one
 * round and filled and carrying its label, the other one a tight-cornered icon that gives way. A press squeezes the
 * segment, the change of selection animates colour, corners and width. [animate] off makes every change instant.
 */
@Composable
fun MediaTypeSwitch(
    selected: MediaType,
    animate: Boolean,
    onSelect: (MediaType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Segment(R.drawable.live_tv_24, stringResource(R.string.anime), selected == MediaType.ANIME, true, animate) { onSelect(MediaType.ANIME) }
        Segment(R.drawable.book_24, stringResource(R.string.manga), selected == MediaType.MANGA, false, animate) { onSelect(MediaType.MANGA) }
    }
}

@Composable
private fun Segment(icon: Int, label: String, selected: Boolean, leading: Boolean, animate: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val seam by animateDpAsState(if (selected) 20.dp else 7.dp, if (animate) spring(stiffness = Spring.StiffnessMediumLow) else spring(stiffness = Spring.StiffnessHigh), label = "seam")
    val outer = 20.dp
    val shape = if (selected) RoundedCornerShape(20.dp) else if (leading) {
        RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = seam, bottomEnd = seam)
    } else RoundedCornerShape(topStart = seam, bottomStart = seam, topEnd = outer, bottomEnd = outer)
    val container by animateColorAsState(if (selected) colors.primary else colors.surfaceContainer, label = "container")
    val content by animateColorAsState(if (selected) colors.onPrimary else colors.onSurfaceVariant, label = "content")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val squeeze by animateFloatAsState(if (pressed && animate) 0.94f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "squeeze")
    Surface(
        color = container, shape = shape,
        modifier = Modifier.height(40.dp).scale(squeeze)
            .then(if (animate) Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)) else Modifier)
            .semantics { role = Role.Tab; this.selected = selected; contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (selected) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            if (selected) Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, softWrap = false)
        }
    }
}
