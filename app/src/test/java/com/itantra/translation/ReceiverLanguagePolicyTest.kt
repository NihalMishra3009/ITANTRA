package com.itantra.translation

import com.itantra.translation.ReceiverLanguagePolicy.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverLanguagePolicyTest {
    private val langs = listOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en")

    @Test fun sameLanguageIsSpokenAsIs() {
        for (l in langs) assertEquals(l, Plan.SPEAK_AS_IS, ReceiverLanguagePolicy.plan(l, l, false))
        assertEquals(Plan.SPEAK_AS_IS, ReceiverLanguagePolicy.plan("EN", "en", false))
    }

    @Test fun everyDifferentPairIsTranslatedOnTheReceiver() {
        var n = 0
        for (s in langs) for (t in langs) if (s != t) {
            assertEquals("$s -> $t", Plan.TRANSLATE, ReceiverLanguagePolicy.plan(s, t, false)); n++
        }
        assertEquals(90, n) // 10 x 9 directed pairs
    }

    @Test fun emergencyIsNeverTranslated() {
        for (s in langs) for (t in langs) assertEquals(Plan.SPEAK_AS_IS, ReceiverLanguagePolicy.plan(s, t, true))
    }

    @Test fun unknownLanguageIsNotTranslated() {
        assertEquals(Plan.SPEAK_AS_IS, ReceiverLanguagePolicy.plan("", "hi", false))
        assertEquals(Plan.SPEAK_AS_IS, ReceiverLanguagePolicy.plan("hi", "", false))
    }
}
