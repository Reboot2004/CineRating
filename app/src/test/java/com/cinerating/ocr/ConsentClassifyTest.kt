package com.cinerating.ocr

import android.app.Activity
import com.cinerating.ocr.ScreenCaptureConsentActivity.Companion.ConsentOutcome
import com.cinerating.ocr.ScreenCaptureConsentActivity.Companion.classify
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsentClassifyTest {

    @Test
    fun okWithData_granted() {
        assertEquals(
            ConsentOutcome.GRANTED,
            classify(Activity.RESULT_OK, hasData = true)
        )
    }

    @Test
    fun cancelledWithoutData_dismissed() {
        assertEquals(
            ConsentOutcome.DISMISSED,
            classify(Activity.RESULT_CANCELED, hasData = false)
        )
    }

    @Test
    fun cancelledWithData_stillDismissed() {
        assertEquals(
            ConsentOutcome.DISMISSED,
            classify(Activity.RESULT_CANCELED, hasData = true)
        )
    }

    @Test
    fun okWithoutData_emptyQuirk() {
        // Some OEM skins return OK with no token: must be logged distinctly,
        // never treated as a grant (nothing to build a projection from).
        assertEquals(
            ConsentOutcome.EMPTY_DATA,
            classify(Activity.RESULT_OK, hasData = false)
        )
    }
}
