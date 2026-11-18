package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class MapImeDiagnosticsTest {
    private val map = "com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity"
    private val displays = """
        Display: mDisplayId=0 rootTasks=2
        mCurrentFocus=null
        mImeWindow=null  Display: mDisplayId=12 (organized)
        mCurrentFocus=Window{123a u0 $map}
        mImeInputTarget=Window{123a u0 $map}
    """.trimIndent()

    private fun snapshot(imms: String, windows: String) =
        MapImeSnapshot.parse(12, "$displays\n#IMMS\n$imms\n#WINDOWS\n$windows")

    @Test fun android12ClientOnMapWithVisibleOemKeyboardOnPhone() {
        val sample = snapshot("""
            mCurMethodId=com.test.ime/.Selected
            mCurClient=ClientState{abcd uid=10223 pid=1234 displayId=12} mCurSeq=4
            mCurId=com.test.ime/.Bound mHaveConnection=true
            mShowRequested=true mShowExplicitlyRequested=false mInputShown=true
        """, """
            Window #0 Window{456 u0 OEM keyboard}:
                mDisplayId=0 mSession=...
                mAttrs={(0,0) ty=INPUT_METHOD fmt=TRANSLUCENT}
                isVisible=true
            Window #1 Window{457 u0 Private search text}:
                mDisplayId=12
                isVisible=true
        """)
        assertTrue(sample.visible)
        for (field in listOf("keyboardDisplay=0", "inputClientDisplay=12", "mapFocus=$map",
            "clientFocus=$map", "currentIme=com.test.ime/.Bound", "selectedIme=com.test.ime/.Selected",
            "inputShown=true")) assertTrue(sample.summary, field in sample.summary)
        assertFalse("Private search text" in sample.summary)
    }

    @Test fun modernShownRequestCanPrecedeVisibleMapWindow() {
        val imms = """
            mSelectedImeId=com.baidu.input_mi/.ImeService
            mCurImeId=com.baidu.input_mi/.ImeService
            mCurClient=ClientState{eeb9c4a mUid=10314 mPid=14563 mSelfReportedDisplayId=12}
            mInputShown=true
        """
        val window = """
            Window #0 Window{b5e7068 u0 InputMethod}:
                mDisplayId=12 mSession=...
                isVisible=false
            Window #1 Window{42 u0 Samsung IME dialog}:
                mDisplayId=0
                mAttrs={ty=INPUT_METHOD_DIALOG}
                isVisible=true
        """
        val early = snapshot(imms, window)
        assertFalse(early.visible)
        assertTrue("inputShown=true keyboardDisplay=hidden" in early.summary)
        val settled = snapshot(imms, window.replace("isVisible=false", "isVisible=true"))
        assertTrue(settled.visible)
        assertTrue("keyboardDisplay=12" in settled.summary)
        assertTrue("imeTarget=$map inputClientDisplay=12" in settled.summary)
        assertTrue("currentIme=com.baidu.input_mi/.ImeService" in settled.summary)
    }

    @Test fun missingVisibilityOrDeniedDumpNeverInventsActualDisplay() {
        val incomplete = snapshot("mInputShown=true", "Window #0 Window{888 u0 InputMethod}:\n mDisplayId=12")
        assertFalse(incomplete.visible)
        assertTrue("keyboardDisplay=unknown" in incomplete.summary)
        val denied = MapImeSnapshot.parse(12, "")
        assertFalse(denied.visible)
        for (field in listOf("mapFocus", "currentIme", "inputShown", "keyboardDisplay")) {
            assertTrue("$field=unknown" in denied.summary)
        }
    }
}
