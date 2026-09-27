package com.freebuff.android.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StrReplaceTest {
    @Test fun `single match applied`() {
        val r = StrReplace.replace("val x = 1", "1", "2")
        assertIs<StrReplace.Outcome.Applied>(r)
        assertEquals("val x = 2", (r as StrReplace.Outcome.Applied).content)
    }

    @Test fun `missing match fails`() {
        assertIs<StrReplace.Outcome.NotFound>(StrReplace.replace("abc", "z", "y"))
    }

    @Test fun `ambiguous match fails without flag`() {
        assertIs<StrReplace.Outcome.Ambiguous>(StrReplace.replace("aaa", "a", "b"))
    }

    @Test fun `ambiguous allowed with flag`() {
        assertIs<StrReplace.Outcome.Applied>(StrReplace.replace("aaa", "a", "b", expectMultiple = true))
    }
}
