package com.bcova.namplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bcova.namplayer.R
import com.bcova.namplayer.t3k.Tone
import com.bcova.namplayer.t3k.T3kUser

/** Full TONE3000 logo: show this first, at entry points (TONE3000 design rule 7). */
@Composable
fun T3kLogo(modifier: Modifier = Modifier, height: Dp = 24.dp) {
    Image(
        painter = painterResource(R.drawable.t3k_logo_full),
        contentDescription = "TONE3000",
        modifier = modifier.height(height),
        contentScale = ContentScale.Fit,
    )
}

/** Compact T3K mark: for buttons and signal-block markers, after the full logo has been seen. */
@Composable
fun T3kMark(modifier: Modifier = Modifier, height: Dp = 14.dp) {
    Image(
        painter = painterResource(R.drawable.t3k_logo_mark),
        contentDescription = "From TONE3000",
        modifier = modifier.height(height),
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun ToneImage(url: String?, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun Avatar(url: String?, size: Dp = 20.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(size * 0.7f))
        }
    }
}

/** "@username" with avatar: creators are always credited (TONE3000 design rule 4). */
@Composable
fun CreatorRow(user: T3kUser, avatarSize: Dp = 18.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(user.avatarUrl, avatarSize)
        Spacer(Modifier.width(6.dp))
        Text(
            "@${user.username}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Chip(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Surface(color = color, shape = RoundedCornerShape(4.dp)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** One row in a tone list: image, title, gear type, format, creator. */
@Composable
fun ToneRow(tone: Tone, favorite: Boolean? = null, onFavorite: () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToneImage(tone.imageUrl, Modifier.size(64.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tone.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tone.gearLabel.isNotEmpty()) Chip(tone.gearLabel)
                Chip(
                    tone.formatLabel,
                    if (tone.isSupported) MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                if (!tone.isSupported) Chip("Not supported", MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
            }
            CreatorRow(tone.user)
        }
        if (favorite != null) FavoriteButton(favorite, onFavorite)
    }
}

/** Heart button: filled when the tone is in your TONE3000 favorites. */
@Composable
fun FavoriteButton(favorite: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
            tint = if (favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Opens TONE3000. Always reachable wherever tones are shown.
 * [fullLogo]: use the full TONE3000 logo at entry points (e.g. the signal chain), and the compact
 * T3K mark inside list views where the user has already seen the full logo (design rule 7).
 */
@Composable
fun BrowseT3kButton(onClick: () -> Unit, modifier: Modifier = Modifier, fullLogo: Boolean = false) {
    Button(onClick = onClick, modifier = modifier.height(52.dp)) {
        Text("Browse", fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        // The logo sits on a dark pill so its yellow stays readable on the yellow button.
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black).padding(horizontal = 6.dp, vertical = 4.dp)) {
            if (fullLogo) T3kLogo(height = 16.dp) else T3kMark(height = 16.dp)
        }
    }
}
