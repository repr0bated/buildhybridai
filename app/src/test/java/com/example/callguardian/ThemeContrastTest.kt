package com.example.callguardian

import androidx.compose.ui.graphics.Color
import com.example.callguardian.ui.theme.Brand
import com.example.callguardian.ui.theme.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private val surfaces = listOf(
        "Void" to Brand.Void,
        "Glass" to Brand.Glass,
        "Glass2" to Brand.Glass2,
        "Line" to Brand.Line,
    )

    @Test fun inkMeetsAaaOnDarkSurfaces() {
        for ((name, bg) in surfaces) {
            val r = contrastRatio(Brand.Ink, bg)
            assertTrue("Ink on $name is $r, need ≥ 7:1 (AAA)", r + 1e-6 >= 7.0)
        }
    }

    @Test fun inkDimMeetsAaBodyOnDarkSurfaces() {
        for ((name, bg) in surfaces) {
            val r = contrastRatio(Brand.InkDim, bg)
            assertTrue("InkDim on $name is $r, need ≥ 4.5:1 (AA body)", r + 1e-6 >= 4.5)
        }
    }

    @Test fun blackOnGlassFailsAa() {
        // Documents the bug we fixed: default LocalContentColor (black) on a glass card.
        val r = contrastRatio(Color.Black, Brand.Glass2)
        assertTrue("sanity: black on Glass2 should fail AA, was $r", r < 4.5)
    }
}
