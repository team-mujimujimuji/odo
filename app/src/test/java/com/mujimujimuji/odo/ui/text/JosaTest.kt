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

    @Test
    fun `숫자 1·7·8은 ㄹ 받침이라 로`() {
        assertEquals("레벨 1로", josa("레벨 1", "으로/로"))
        assertEquals("Lv 7로", josa("Lv 7", "으로/로"))
        assertEquals("Lv 8로", josa("Lv 8", "으로/로"))
        assertEquals("레벨 3으로", josa("레벨 3", "으로/로"))
        assertEquals("Lv 10으로", josa("Lv 10", "으로/로"))
        // 이/가·을/를은 ㄹ도 받침 있음으로 고른다(바뀌지 않음).
        assertEquals("레벨 1이", josa("레벨 1", "이/가"))
        assertEquals("Lv 8을", josa("Lv 8", "을/를"))
    }
}
