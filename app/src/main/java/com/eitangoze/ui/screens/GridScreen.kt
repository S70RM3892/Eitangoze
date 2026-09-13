package com.eitangoze.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.Morpheme
import com.eitangoze.data.Repository

/**
 * The grid: one piece of a word held still, and everything that piece builds.
 *
 * A paper etymology book can only ever list a stem's children, because paper
 * has one dimension. Here the axis flips. Hold `duce` still and the prefixes
 * line up beside reduce, deduce, produce; tap `re-` in the left column and the
 * grid turns over, so the same `re-` is now standing beside reduce, reject,
 * report, resist. That second view is the one that pays: it is what makes the
 * next unknown `re-` word guessable, and it is invisible on paper.
 *
 * Words not yet met are drawn faint. The row of blanks is the point — a family
 * two words short of complete is a specific, finishable piece of work, and
 * finishing it is what buys the guessing.
 */
@Composable
fun GridScreen(
    grid: Repository.Grid,
    onOpenEntry: (Long) -> Unit,
    onFlipToAffix: (Long) -> Unit,
    onFlipToStem: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(grid.held, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Chip(grid.heldKind.ja, colors.primaryContainer, colors.onPrimaryContainer)
                }
                if (grid.heldJa.isNotBlank()) {
                    Text(
                        "「${grid.heldJa}」",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (grid.heldGloss.isNotBlank()) {
                    Text(
                        grid.heldGloss,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val remaining = grid.cells.size - grid.met.size
                Text(
                    if (remaining > 0) {
                        "${grid.cells.size} 語 ・ あと $remaining 語でこの系列が埋まります"
                    } else {
                        "${grid.cells.size} 語 ・ この系列は埋まっています"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Divider()
        }

        items(grid.cells) { cell ->
            val met = cell.entry.id in grid.met
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The varying piece, and the handle that turns the grid over.
                val flippable = cell.varying.hasPage || cell.varying.kind == Morpheme.Kind.STEM
                Text(
                    cell.varying.form,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (flippable) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier
                        .width(84.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .then(
                            when {
                                cell.varying.hasPage ->
                                    Modifier.clickable { onFlipToAffix(cell.varying.affixId) }
                                cell.varying.kind == Morpheme.Kind.STEM ->
                                    Modifier.clickable { onFlipToStem(cell.varying.form) }
                                else -> Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onOpenEntry(cell.entry.id) }
                        .padding(vertical = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            cell.entry.lemma,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (met) colors.onSurface else colors.onSurface.copy(alpha = 0.45f),
                        )
                        LevelChip(cell.entry)
                    }
                    Text(
                        // Not yet met: the meaning is withheld. Showing it would
                        // answer the very question the grid is teaching you to
                        // answer for yourself.
                        if (met) cell.entry.ja.joinToString("、") else "░░░░░░",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = if (met) 1f else 0.45f),
                    )
                }
            }
            Divider(color = colors.surfaceVariant)
        }

        item {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "左の列を押すと、軸が入れ替わります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "分け方は Wiktionary の語源記述そのままで、綴りから推測はしていません。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AffixListScreen(
    affixes: List<com.eitangoze.data.Affix>,
    onOpen: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize()) {
        items(affixes) { affix ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(affix.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    affix.form,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(96.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        affix.jaLine.ifBlank { affix.glossLine },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "${affix.uses} 語",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Divider(color = colors.surfaceVariant)
        }
    }
}
