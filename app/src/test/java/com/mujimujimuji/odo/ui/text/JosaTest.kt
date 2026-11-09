package com.mujimujimuji.odo.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test

class JosaTest {
    @Test
    fun `받침 유무로 이가를 고른다`() {
        assertEquals("업적이", josa("업적", "이/가"))
        assertEquals("미니카가", josa("미니카", "이/가"))
    }

    @Test
    fun `숫자는 읽는 소리의 받침을 따른다`() {
        assertEquals("레벨 5가", josa("레벨 5", "이/가"))
        assertEquals("Lv 10이", josa("Lv 10", "이/가"))
    }

    @Test
    fun `ㄹ 받침은 으로 대신 로`() {
        assertEquals("서울로", josa("서울", "으로/로"))
        assertEquals("집으로", josa("집", "으로/로"))
    }
}
