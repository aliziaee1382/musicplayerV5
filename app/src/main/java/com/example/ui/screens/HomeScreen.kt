package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.model.GlassTheme
import com.example.model.Track
import com.example.model.TrackSortCriterion
import com.example.model.TrackSortOrder
import com.example.ui.glass.*
import androidx.compose.ui.res.painterResource
import com.example.R

@Composable
fun HomeScreen(
    tracks: List<Track>,
    recentlyPlayed: List<Track>,
    selectedCategory: String,
    searchQuery: String,
    currentTrack: Track?,
    isPlaying: Boolean,
    theme: GlassTheme,
    sortCriterion: TrackSortCriterion = TrackSortCriterion.DATE_ADDED,
    sortOrder: TrackSortOrder = TrackSortOrder.DESCENDING,
    onSelectCategory: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortCriterionChange: (TrackSortCriterion) -> Unit = {},
    onSortOrderChange: (TrackSortOrder) -> Unit = {},
    onPlayTrack: (Track, List<Track>?) -> Unit,
    onShufflePlay: ((List<Track>?) -> Unit)? = null,
    onToggleFavorite: (Track) -> Unit,
    onOpenThemeSelector: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenAddToPlaylist: ((Track) -> Unit)? = null,
    onScanLocalMusic: (() -> Unit)? = null
) {
    var showSortDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }

    if (showSortDialog) {
        SortTracksDialog(
            selectedCriterion = sortCriterion,
            selectedOrder = sortOrder,
            onSelectCriterion = { onSortCriterionChange(it) },
            onSelectOrder = { onSortOrderChange(it) },
            onDismiss = { showSortDialog = false },
            theme = theme
        )
    }

    if (showSearchDialog) {
        SearchTracksDialog(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            tracks = tracks,
            currentTrack = currentTrack,
            isPlaying = isPlaying,
            onPlayTrack = onPlayTrack,
            onToggleFavorite = onToggleFavorite,
            onDismiss = { showSearchDialog = false },
            theme = theme
        )
    }

    val categories = remember(tracks) {
        val detectedFolders = tracks.map { it.folderName }
            .filter { it.isNotBlank() }
            .distinct()
        listOf("All") + detectedFolders
    }

    val filteredTracks = remember(tracks, selectedCategory, searchQuery) {
        tracks.filter { track ->
            val matchesCategory = if (selectedCategory == "All" || selectedCategory.isBlank()) {
                true
            } else {
                track.folderName.equals(selectedCategory, ignoreCase = true) || track.category.equals(selectedCategory, ignoreCase = true)
            }
            val matchesSearch = searchQuery.isBlank() ||
                    track.title.contains(searchQuery, ignoreCase = true) ||
                    track.artist.contains(searchQuery, ignoreCase = true) ||
                    track.album.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    val sortedTracks = remember(filteredTracks, sortCriterion, sortOrder) {
        val comparator = when (sortCriterion) {
            TrackSortCriterion.DATE_ADDED -> compareBy<Track> { it.dateAddedTimestamp }
            TrackSortCriterion.FILE_DATE -> compareBy<Track> { it.dateModifiedTimestamp }
            TrackSortCriterion.TITLE -> compareBy<Track> { it.title.lowercase() }
            TrackSortCriterion.DURATION -> compareBy<Track> { it.durationSeconds }
        }
        if (sortOrder == TrackSortOrder.ASCENDING) {
            filteredTracks.sortedWith(comparator)
        } else {
            filteredTracks.sortedWith(comparator.reversed())
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_screen_column"),
        contentPadding = PaddingValues(bottom = 180.dp)
    ) {
        // Top User Header
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "0003",
                        color = theme.textColor,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("home_header_title")
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Scan Prompt if no songs exist
        if (tracks.isEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        GlassCard(
                            onClick = { onScanLocalMusic?.invoke() },
                            theme = theme,
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "empty_offline_card"
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderSpecial,
                                    contentDescription = "Scan Storage",
                                    tint = theme.accentColor,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No Local Music Found",
                                    color = theme.textColor,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap below to scan phone storage for MP3, FLAC, M4A & WAV audio files.",
                                    color = theme.subtextColor,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                GlassButton(
                                    text = "Scan Storage",
                                    onClick = { onScanLocalMusic?.invoke() },
                                    theme = theme,
                                    testTag = "home_scan_empty_cta"
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Pop-out Categories / Folders Chips
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "DETECTED FOLDERS & CATEGORIES",
                    color = theme.subtextColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp)
                ) {
                    items(categories, key = { it }, contentType = { "category_chip" }) { category ->
                        GlassChip(
                            text = category,
                            isSelected = selectedCategory == category,
                            onClick = { onSelectCategory(category) },
                            theme = theme,
                            testTag = "category_chip_$category"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Music Tracks Header with Sort Icon Button
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "All Songs",
                            color = theme.textColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${sortedTracks.size} tracks • ${sortCriterion.labelEn} (${sortOrder.labelEn})",
                            color = theme.accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassIconButton(
                            icon = Icons.Default.Shuffle,
                            contentDescription = "Quick Shuffle Play",
                            onClick = { onShufflePlay?.invoke(sortedTracks) },
                            theme = theme,
                            size = 40.dp,
                            testTag = "quick_shuffle_button"
                        )

                        GlassIconButton(
                            icon = Icons.Default.Search,
                            contentDescription = "Search Songs & Artists",
                            onClick = { showSearchDialog = true },
                            isActive = searchQuery.isNotEmpty(),
                            theme = theme,
                            size = 40.dp,
                            testTag = "open_search_dialog_button"
                        )

                        GlassIconButton(
                            icon = Icons.Default.Sort,
                            contentDescription = "Sort Songs",
                            onClick = { showSortDialog = true },
                            theme = theme,
                            size = 40.dp,
                            testTag = "open_sort_dialog_button"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // Lazy recycling items for high-performance compact track list
        itemsIndexed(
            items = sortedTracks,
            key = { _, track -> track.id },
            contentType = { _, _ -> "track_item" }
        ) { index, track ->
            val isCurrent = currentTrack?.id == track.id
            val isFirst = index == 0
            val isLast = index == sortedTracks.lastIndex
            val itemShape = when {
                isFirst && isLast -> RoundedCornerShape(16.dp)
                isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                else -> RectangleShape
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(itemShape)
                        .background(theme.glassFill)
                        .clickable { onPlayTrack(track, sortedTracks) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassArtworkCard(
                            gradientIndex = track.coverGradientIndex,
                            isPlaying = isCurrent && isPlaying,
                            imageUrl = track.albumArtUri,
                            theme = theme,
                            modifier = Modifier.size(42.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = if (isCurrent) theme.accentColor else theme.textColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${track.artist} • ${track.album}",
                                color = theme.subtextColor,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = track.formattedDuration(),
                            color = theme.subtextColor,
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        if (onOpenAddToPlaylist != null) {
                            GlassIconButton(
                                icon = Icons.Default.PlaylistAdd,
                                contentDescription = "Add to Playlist",
                                onClick = { onOpenAddToPlaylist(track) },
                                theme = theme,
                                size = 32.dp,
                                testTag = "add_playlist_button_${track.id}"
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        GlassIconButton(
                            icon = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            onClick = { onToggleFavorite(track) },
                            isActive = track.isFavorite,
                            theme = theme,
                            size = 32.dp,
                            testTag = "favorite_button_${track.id}"
                        )
                    }
                }

                if (!isLast) {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 0.5.dp,
                        color = theme.textColor.copy(alpha = 0.15f)
                    )
                }
            }
        }
    }
}
