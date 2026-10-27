package com.telecomprofi.japanesewalk.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.TimeMath
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.ui.theme.JwColors

/**
 * One line under the gauge saying where in the session you are: which set, of
 * how many, and how long this bout has been running.
 *
 * Both the Walk and Data screens show it, so the two never disagree about which
 * set you are on.
 */
@Composable
fun SetLine(
    phase: Phase?,
    setNumber: Int,
    totalSets: Int,
    segmentElapsedSec: Int,
    segmentTotalSec: Int,
    isCooling: Boolean = false,
    coolDownSec: Int = 0,
    mode: WorkoutMode = WorkoutMode.WALK,
    modifier: Modifier = Modifier,
) {
    // The set number is the one thing on this line you look for mid-walk, so
    // it is twice the size of everything around it and in the heaviest weight
    // the face has. The rest stays small: a line of uniformly large text is
    // just as hard to scan as a line of uniformly small text.
    val big = SpanStyle(
        fontSize = MaterialTheme.typography.labelMedium.fontSize * 2.2f,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.sp,
    )
    val text = when {
        isCooling -> AnnotatedString("COOL-DOWN · ${TimeMath.mmss(coolDownSec)} extra")
        phase == null -> AnnotatedString("READY · $totalSets sets")
        phase == Phase.WARMUP -> AnnotatedString("WARM-UP · ${TimeMath.mmss(segmentElapsedSec)}")
        phase == Phase.COOLDOWN -> AnnotatedString("COOL-DOWN · ${TimeMath.mmss(segmentElapsedSec)}")
        else -> buildAnnotatedString {
            append("SET ")
            withStyle(big) { append("$setNumber") }
            append(" OF $totalSets")
            append(" · ")
            append(mode.label(phase))
            append(" · ")
            append(TimeMath.mmss(segmentElapsedSec))
            if (segmentTotalSec > 0) append(" / ${TimeMath.mmss(segmentTotalSec)}")
        }
    }
    val colour = when {
        isCooling -> JwColors.Accent
        phase == Phase.FAST -> JwColors.Fast
        phase == Phase.SLOW -> JwColors.Slow
        phase == null -> JwColors.Muted
        else -> JwColors.Accent
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        // Room for the oversized span without the line jumping as it changes.
        lineHeight = MaterialTheme.typography.labelMedium.fontSize * 2.4f,
        color = colour,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier.fillMaxWidth(),
    )
}
