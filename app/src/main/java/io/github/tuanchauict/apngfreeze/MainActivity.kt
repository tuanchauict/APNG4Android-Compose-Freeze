package io.github.tuanchauict.apngfreeze

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Bundled APNG, so the demo has no network/token dependency. The image source makes
 * no difference to the freeze — URL or asset both feed the same
 * decode → APNGDrawable → Coil painter pipeline.
 */
private const val ASSET_APNG = "file:///android_asset/elephant.png"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ApngFreezeDemo(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * The two ways a recomposition stops penfeizhou's drawable in production. Both leave
 * the drawable on screen and being drawn — only the decoder is torn down.
 */
private enum class StressMode(
    val label: String,
    val explanation: String,
) {
    OFF(
        "Off",
        "Baseline: just play the animation, no interference.",
    ),
    VISIBILITY(
        "setVisible(false)",
        "Every 0.8s, calls setVisible(false) on the on-screen drawable — exactly " +
            "what Coil's DrawablePainter.onForgotten() does when a recomposition " +
            "forgets it.",
    ),
    DECODER_STOP(
        "decoder.stop()",
        "Every 0.8s, calls frameSeqDecoder.stop() on the on-screen drawable — the " +
            "underlying teardown that visibility/lifecycle churn triggers.",
    ),
}

@Composable
fun ApngFreezeDemo(modifier: Modifier = Modifier) {
    var stress by remember { mutableStateOf(StressMode.OFF) }

    // Apply the selected stress to whatever drawable is currently on screen. This
    // simulates what a host recomposition does to the drawable while it stays
    // visible. The image is never removed from the tree.
    LaunchedEffect(stress) {
        if (stress == StressMode.OFF) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(800)
            val drawable = ApngDemoState.live?.get() ?: continue
            when (stress) {
                StressMode.VISIBILITY -> drawable.setVisible(false, false)
                StressMode.DECODER_STOP -> drawable.frameSeqDecoder.stop()
                StressMode.OFF -> Unit
            }
        }
    }

    // Poll the frame counter so a stuck count visibly proves a freeze.
    var rendered by remember { mutableIntStateOf(0) }
    var lastChangeAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val count = ApngDemoState.renderCount.get()
            now = System.currentTimeMillis()
            if (count != rendered) {
                rendered = count
                lastChangeAt = now
            }
            kotlinx.coroutines.delay(100)
        }
    }
    val frozen = rendered > 0 && (now - lastChangeAt) > 1_200L

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = "APNGDrawable recompose-freeze",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = BuildVariantLabel,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Recompose stress:",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StressMode.entries.forEach { mode ->
                val selected = stress == mode
                if (selected) {
                    Button(
                        onClick = { stress = mode },
                        modifier = Modifier.weight(1f),
                    ) { Text(mode.label, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = { stress = mode },
                        modifier = Modifier.weight(1f),
                    ) { Text(mode.label, maxLines = 1) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stress.explanation,
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "frames rendered: $rendered",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text =
                when {
                    stress == StressMode.OFF -> "status: ▶ animating (no stress applied)"
                    frozen -> "status: ❄️ FROZEN — counter is stuck"
                    else -> "status: ▶ animating — counter is climbing"
                },
            style = MaterialTheme.typography.titleMedium,
            color =
                if (frozen) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
        )

        Spacer(Modifier.height(16.dp))

        AsyncImage(
            model = ASSET_APNG,
            contentDescription = "Animated elephant APNG",
            modifier = Modifier.size(240.dp),
        )
    }
}

/**
 * Shown in the UI so it's obvious which branch you're running. The `solution` branch
 * overrides this string.
 */
private const val BuildVariantLabel =
    "Branch: main — stock APNGDrawable (reproduces the freeze)"
