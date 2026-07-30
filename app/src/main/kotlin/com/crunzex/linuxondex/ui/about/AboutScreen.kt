package com.crunzex.linuxondex.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.crunzex.linuxondex.about.AboutRepository
import com.crunzex.linuxondex.about.DeveloperProfile
import com.crunzex.linuxondex.about.UpdateStatus
import com.crunzex.linuxondex.core.AppLog
import com.crunzex.linuxondex.ui.components.CapsuleButton
import com.crunzex.linuxondex.ui.components.GroupCard
import com.crunzex.linuxondex.ui.components.ListRow
import com.crunzex.linuxondex.ui.components.OneUiCollapsingScaffold
import com.crunzex.linuxondex.ui.components.OneUiMotion
import com.crunzex.linuxondex.ui.components.SectionCaption
import com.crunzex.linuxondex.ui.components.VerticalSpace
import com.crunzex.linuxondex.ui.theme.OneUiPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the screen knows; every field is optional so it renders while loading. */
private data class AboutContent(
    val profile: DeveloperProfile? = null,
    val avatar: ImageBitmap? = null,
    val updateStatus: UpdateStatus = UpdateStatus.Checking,
)

/**
 * "About this app", in the shape Samsung's Good Lock modules use: the app's
 * own identity at the top (icon, name, version, update state), then who made
 * it, then where to go next.
 *
 * Everything from the network is decoration: the screen is complete and
 * useful with no connection at all, showing the cached profile if there is
 * one and the installed version either way.
 */
@Composable
fun AboutScreen(
    repository: AboutRepository,
    /** Null in the two-pane layout, where the menu stays visible beside us. */
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val content by produceState(initialValue = AboutContent(), repository) {
        value = loadAboutContent(repository)
    }

    OneUiCollapsingScaffold(
        // No page title: One UI lets the app's own icon and name introduce
        // this screen, so a heading above them would only repeat it.
        title = null,
        onNavigateBack = onBack,
    ) {
        item {
            AppIdentityHeader(
                versionName = repository.installedVersionName(),
                updateStatus = content.updateStatus,
                onOpenRelease = { url -> context.openInBrowser(url) },
            )
        }

        item { SectionCaption("Developer") }
        item {
            DeveloperCard(
                profile = content.profile,
                avatar = content.avatar,
                onOpenProfile = { context.openInBrowser(AboutRepository.PROFILE_PAGE_URL) },
            )
        }

        item { SectionCaption("Project") }
        item {
            GroupCard {
                ListRow(
                    title = "Source code",
                    subtitle = AboutRepository.REPOSITORY_PAGE_URL.removePrefix("https://"),
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable {
                        context.openInBrowser(AboutRepository.REPOSITORY_PAGE_URL)
                    },
                )
            }
        }

        item {
            Text(
                text = "An independent project. Not affiliated with or endorsed by " +
                    "Samsung Electronics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            )
        }
        item { VerticalSpace(12) }
    }
}

/**
 * The Good Lock identity block: a large app icon, the name, and the version
 * with its update state directly underneath.
 */
@Composable
private fun AppIdentityHeader(
    versionName: String,
    updateStatus: UpdateStatus,
    onOpenRelease: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Linux on DeX",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Version $versionName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        UpdateState(updateStatus = updateStatus, onOpenRelease = onOpenRelease)
    }
}

/**
 * The app's own launcher icon, rounded the way One UI presents it.
 *
 * Drawn through the package manager rather than `painterResource`: the icon
 * is an adaptive icon, which Compose's resource painter cannot rasterize —
 * asking it to crashes the screen. This also guarantees the icon shown here
 * is exactly the one on the home screen.
 */
@Composable
private fun AppIcon() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { APP_ICON_SIZE_DP.dp.roundToPx() }
    val icon = remember(sizePx) { loadLauncherIcon(context, sizePx) }

    val shape = RoundedCornerShape(APP_ICON_CORNER_DP.dp)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier
                .size(APP_ICON_SIZE_DP.dp)
                .clip(shape),
        )
    } else {
        // A device that cannot hand us its own icon still gets a tidy tile.
        Box(
            modifier = Modifier
                .size(APP_ICON_SIZE_DP.dp)
                .clip(shape)
                .background(OneUiPalette.Blue),
        )
    }
}

private fun loadLauncherIcon(context: Context, sizePx: Int): ImageBitmap? = try {
    context.packageManager
        .getApplicationIcon(context.packageName)
        .toBitmap(width = sizePx, height = sizePx)
        .asImageBitmap()
} catch (unavailable: Exception) {
    AppLog.warn("About", "the launcher icon could not be drawn", unavailable)
    null
}

/**
 * Samsung shows either an Update button or a plain "latest version" line.
 * While the check runs, the line reads as checking, so the button can never
 * flash in and out.
 */
@Composable
private fun UpdateState(updateStatus: UpdateStatus, onOpenRelease: (String) -> Unit) {
    AnimatedContent(
        targetState = updateStatus,
        transitionSpec = {
            fadeIn(OneUiMotion.settleSpec()) togetherWith fadeOut(OneUiMotion.settleSpec())
        },
        label = "update-state",
    ) { status ->
        when (status) {
            is UpdateStatus.Available -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Version ${status.version} is available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OneUiPalette.Blue,
                )
                Spacer(Modifier.height(12.dp))
                CapsuleButton(
                    text = "Update",
                    onClick = { onOpenRelease(status.releasePageUrl) },
                    modifier = Modifier.width(UPDATE_BUTTON_WIDTH_DP.dp),
                )
            }

            UpdateStatus.Checking -> Text(
                text = "Checking for updates…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            UpdateStatus.Latest -> Text(
                text = "Latest version",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The developer, as GitHub describes them; tapping opens the profile. */
@Composable
private fun DeveloperCard(
    profile: DeveloperProfile?,
    avatar: ImageBitmap?,
    onOpenProfile: () -> Unit,
) {
    GroupCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenProfile)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeveloperAvatar(avatar)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile?.displayName ?: AboutRepository.DEVELOPER_LOGIN,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = profile?.handle ?: "@${AboutRepository.DEVELOPER_LOGIN}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OneUiPalette.Blue,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** Circular avatar, with a placeholder until (or unless) the image arrives. */
@Composable
private fun DeveloperAvatar(avatar: ImageBitmap?) {
    Box(
        modifier = Modifier
            .size(AVATAR_SIZE_DP.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(AVATAR_SIZE_DP.dp),
            )
        } else {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * Loads the profile first so the card fills in as soon as possible, then the
 * avatar, then the update check. Each step is independent: one failing never
 * blocks the others.
 */
private suspend fun loadAboutContent(repository: AboutRepository): AboutContent =
    withContext(Dispatchers.IO) {
        val profile = repository.loadDeveloperProfile()
        val avatar = profile?.avatarUrl
            ?.let { repository.loadAvatar(it) }
            ?.let(::decodeAvatar)
        AboutContent(
            profile = profile,
            avatar = avatar,
            updateStatus = repository.checkForUpdate(),
        )
    }

private fun decodeAvatar(bytes: ByteArray): ImageBitmap? = try {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (undecodable: Exception) {
    AppLog.warn("About", "avatar could not be decoded", undecodable)
    null
}

/** Opens a link in the user's browser; a device with none simply does nothing. */
private fun Context.openInBrowser(url: String) {
    try {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (noBrowser: ActivityNotFoundException) {
        AppLog.warn("About", "no app can open $url", noBrowser)
    }
}

private const val APP_ICON_SIZE_DP = 88
private const val APP_ICON_CORNER_DP = 22
private const val AVATAR_SIZE_DP = 56
private const val UPDATE_BUTTON_WIDTH_DP = 200
