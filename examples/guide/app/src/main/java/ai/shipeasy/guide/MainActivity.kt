package ai.shipeasy.guide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single-Activity, single-screen "entity guide". The whole UI is one
 * [LazyColumn] of styled cards — a hero header, the placeholder banner, one
 * card per Shipeasy entity (see [GUIDE_ENTITIES]), and a footer.
 *
 * The app makes ZERO network calls and has no Shipeasy dependency — every
 * value is a hardcoded placeholder. See [Entity] / [GUIDE_ENTITIES].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ShipeasyGuideTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SeColors.Window,
                ) {
                    GuideScreen()
                }
            }
        }
    }
}

@Composable
fun GuideScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SeColors.Window),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HeroHeader() }
        item { PlaceholderBanner() }
        items(GUIDE_ENTITIES) { entity -> EntityCard(entity) }
        item { Footer() }
    }
}

@Composable
private fun HeroHeader() {
    Column(modifier = Modifier.widthIn(max = 560.dp)) {
        Text(
            text = "Shipeasy · Kotlin Entity Guide",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Every Shipeasy entity you can read from the Kotlin SDK, one card at a time. " +
                "Each card shows the entity, a sample value, and the exact SDK call that returns it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun PlaceholderBanner() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .border(1.dp, SeColors.Amber.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SeColors.Amber.copy(alpha = 0.10f)),
    ) {
        Text(
            text = "⚠ SDK not wired yet — every value below is a placeholder. " +
                "Add ai.shipeasy:shipeasy-kotlin and replace the TODOs to make them live.",
            color = SeColors.Amber,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun EntityCard(entity: Entity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .border(1.dp, SeColors.Hairline, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SeColors.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Row: [type-label chip] ... [value chip]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AccentChip(text = entity.label, accent = entity.accent, strong = true)
                Spacer(Modifier.height(0.dp))
                AccentChip(
                    text = entity.value,
                    accent = entity.accent,
                    strong = false,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            // Key (monospace title)
            Text(
                text = entity.key,
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(Modifier.height(8.dp))

            // Description
            Text(
                text = entity.description,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(14.dp))

            // Code block
            CodeBlock(entity.call)

            Spacer(Modifier.height(10.dp))

            // Faint meta line
            Text(
                text = entity.meta,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AccentChip(
    text: String,
    accent: Color,
    strong: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = accent,
            fontFamily = SeMono,
            fontSize = if (strong) 11.sp else 12.sp,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CodeBlock(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SeColors.Code, RoundedCornerShape(10.dp))
            .border(1.dp, SeColors.Hairline, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = code,
            fontFamily = SeMono,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            color = SeColors.TextPrimary,
        )
    }
}

@Composable
private fun Footer() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .padding(top = 8.dp),
    ) {
        Text(
            text = "Run note: this sample has no Shipeasy dependency and makes zero network calls. " +
                "Install ai.shipeasy:shipeasy-kotlin, wire a client (val c = Shipeasy.client(...)), " +
                "and replace each // TODO to go live.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "https://docs.shipeasy.ai",
            color = SeColors.Green,
            fontFamily = SeMono,
            fontSize = 12.sp,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0B, heightDp = 1600)
@Composable
private fun GuidePreview() {
    ShipeasyGuideTheme {
        Surface(color = SeColors.Window) {
            GuideScreen()
        }
    }
}
