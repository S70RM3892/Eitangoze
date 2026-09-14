package com.eitangoze.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.Knowledge
import com.eitangoze.data.MapNode
import com.eitangoze.data.RelationKind
import com.eitangoze.data.WordMap
import com.eitangoze.ui.AppViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val MAP_TAG = "word-map"

/**
 * つながりの地図: one word, the words around it, and which of them you can read.
 *
 * The dictionary has held these connections all along — derivations, synonyms,
 * opposites, confusable pairs, shared Latin roots — and the entry page lists
 * them. A list answers "what else is there". It cannot answer the question a
 * learner actually has, which is *where they are*: whether this corner of
 * English is theirs already, and which single word would close it.
 *
 * So every node carries the same memory state the reading screens use, and the
 * picture is not of English but of this learner's English. Tapping a word walks
 * the map to it, which is the other thing a list cannot do: vocabulary is a
 * graph, and the way to learn where you are in one is to move through it.
 */
@Composable
fun MapScreen(model: AppViewModel, onOpenEntry: (Long) -> Unit = {}) {
    val map = model.wordMap
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(MAP_TAG),
    ) {
        if (map == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp))
                Text("  地図を描いています", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        Text(map.center.lemma, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            map.center.ja.take(3).joinToString("、"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        map.family?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "${it.label}「${it.gloss}」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(12.dp))
        if (map.isEmpty) {
            Empty(map)
        } else {
            Constellation(map, onWalk = model::openMap)
            Spacer(Modifier.height(8.dp))
            Legend()
        }

        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(14.dp),
        ) {
            Text(
                map.verdict,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        val missing = map.nodes.filterNot { it.known }
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = model::takeMapGaps, modifier = Modifier.fillMaxWidth()) {
                Text("この ${missing.size} 語を学習に入れる")
            }
        }
        model.mapMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onOpenEntry(map.center.id) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("${map.center.lemma} のページを開く") }

        Spacer(Modifier.height(18.dp))
        Divider()
        map.byKind.forEach { (kind, nodes) ->
            Section("${kind.ja}  ${nodes.size}") {
                Column { nodes.forEach { NodeRow(it, onWalk = model::openMap) } }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "線は、辞書に行があるものだけです。同語根・派生・類義・対義・混同注意を" +
                "混ぜずに分けてあるのは、類義と対義が逆の主張で、混同注意は意味の" +
                "つながりですらないからです。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Empty(map: WordMap) {
    Text(
        "この語につながる語は、辞書の中にありません。",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "派生・類義・対義・混同注意のどれも登録がない語です。9,260 語のうち 1,275 語が" +
            "そうで、つながりが無いこと自体は珍しくありません。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The picture.
 *
 * Two rings rather than one: twelve words around a single circle on a phone is
 * a wheel of text nobody can read. The inner ring is the connections that are
 * nearest in kind — a shared root, then a derivation — so distance from the
 * middle means something instead of being a drawing convenience.
 */
@Composable
private fun Constellation(map: WordMap, onWalk: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val inner = map.nodes.take(INNER)
    val outer = map.nodes.drop(INNER)
    val label = TextStyle(fontSize = 11.sp)

    // Where each node sits, as a fraction of the box, so the hit test and the
    // drawing cannot drift apart.
    val places = remember(map.center.id, map.nodes.size) {
        buildList {
            inner.forEachIndexed { i, node ->
                add(node to polar(i, inner.size, 0.30f, startAt = -PI / 2))
            }
            outer.forEachIndexed { i, node ->
                add(node to polar(i, outer.size, 0.45f, startAt = -PI / 2 + PI / outer.size))
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .aspectRatio(1f)
            .pointerInput(map.center.id) {
                detectTapGestures { tap ->
                    val hit = places.minByOrNull { (_, place) ->
                        hypot(
                            tap.x - place.first * size.width,
                            tap.y - place.second * size.height,
                        )
                    } ?: return@detectTapGestures
                    val distance = hypot(
                        tap.x - hit.second.first * size.width,
                        tap.y - hit.second.second * size.height,
                    )
                    if (distance < size.width * 0.13f) onWalk(hit.first.entry.id)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val middle = Offset(size.width / 2, size.height / 2)
            places.forEach { (node, place) ->
                val at = Offset(place.first * size.width, place.second * size.height)
                drawLine(
                    color = edgeColour(node.kind, colors),
                    start = middle,
                    end = at,
                    strokeWidth = if (node.kind == RelationKind.ROOT) 2.5f else 1.5f,
                )
            }
            places.forEach { (node, place) ->
                val at = Offset(place.first * size.width, place.second * size.height)
                val fill = stateColour(node.knowledge, colors)
                val radius = size.width * 0.052f
                if (node.knowledge == Knowledge.NEW) {
                    drawCircle(colors.surface, radius, at)
                    drawCircle(fill, radius, at, style = Stroke(width = 2f))
                } else {
                    drawCircle(fill, radius, at)
                }
                val text = measurer.measure(node.entry.lemma, label)
                drawText(
                    text, colors.onSurface,
                    topLeft = Offset(
                        at.x - text.size.width / 2f,
                        at.y + radius + 2f,
                    ),
                )
            }
            // The middle last, so it sits over its own lines.
            val centre = size.width * 0.085f
            drawCircle(colors.primary, centre, middle)
            val name = measurer.measure(
                map.center.lemma,
                TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
            drawText(
                name, colors.onPrimary,
                topLeft = Offset(
                    middle.x - name.size.width / 2f,
                    middle.y - name.size.height / 2f,
                ),
            )
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(
            Knowledge.KNOWN to "読める",
            Knowledge.LEARNING to "あやしい",
            Knowledge.NEW to "未学習",
        ).forEach { (state, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stateColour(state, MaterialTheme.colorScheme)),
                )
                Text(
                    "  $text",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NodeRow(node: MapNode, onWalk: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onWalk(node.entry.id) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(stateColour(node.knowledge, MaterialTheme.colorScheme)),
        )
        Text(node.entry.lemma, style = MaterialTheme.typography.titleMedium)
        LevelChip(node.entry)
        Text(
            node.entry.ja.take(2).joinToString("、"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Eight o'clock, going clockwise: [index] of [count] at [radius] of the box. */
private fun polar(
    index: Int,
    count: Int,
    radius: Float,
    startAt: Double,
): Pair<Float, Float> {
    if (count <= 0) return 0.5f to 0.5f
    val angle = startAt + 2 * PI * index / count
    return (0.5f + radius * cos(angle)).toFloat() to (0.5f + radius * sin(angle)).toFloat()
}

private fun stateColour(
    knowledge: Knowledge,
    colors: androidx.compose.material3.ColorScheme,
): Color = when (knowledge) {
    Knowledge.KNOWN -> colors.primary
    Knowledge.LEARNING -> colors.tertiary
    else -> colors.outline
}

private fun edgeColour(
    kind: RelationKind,
    colors: androidx.compose.material3.ColorScheme,
): Color = when (kind) {
    RelationKind.ROOT -> colors.primary.copy(alpha = 0.55f)
    RelationKind.FAMILY -> colors.primary.copy(alpha = 0.35f)
    RelationKind.ANTONYM -> colors.error.copy(alpha = 0.35f)
    RelationKind.CONFUSE -> colors.error.copy(alpha = 0.22f)
    else -> colors.outlineVariant
}

/** How many nodes go on the inner ring. */
private const val INNER = 6
