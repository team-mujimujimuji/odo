package com.mujimujimuji.odo.ui.text

/**
 * 한글 조사 선택(설계서 §9). `josa("레벨 5", "이/가")` → "레벨 5가", `josa("업적", "을/를")` → "업적을".
 * 마지막 글자가 한글이면 받침으로, 숫자면 읽는 소리의 받침(0·1·3·6·7·8 받침 있음, 그중 일·칠·팔 1·7·8은 ㄹ)으로,
 * 그 밖(영문 등)은 받침 없음으로 본다. "로/으로"는 ㄹ 받침을 받침 없음처럼 다룬다("레벨 1로", "레벨 3으로").
 */
fun josa(word: String, pair: String): String {
    val parts = pair.split('/')
    require(parts.size == 2) { "조사는 '이/가' 꼴이어야 해요: $pair" }
    val withBatchim = parts[0]
    val withoutBatchim = parts[1]
    val last = word.lastOrNull { !it.isWhitespace() } ?: return word + withoutBatchim
    val batchim = batchimOf(last)
    val suffix = when {
        batchim == NO_BATCHIM -> withoutBatchim
        batchim == RIEUL && withoutBatchim == "로" -> withoutBatchim
        else -> withBatchim
    }
    return word + suffix
}

/** 받침 코드: 0 = 없음, 1..27 = 종성 인덱스(8 = ㄹ). 한글이 아니면 없음으로 본다. */
private fun batchimOf(c: Char): Int = when {
    c in '가'..'힣' -> (c - '가') % 28
    c.isDigit() -> when (c) {
        '1', '7', '8' -> RIEUL // 일·칠·팔
        '0', '3', '6' -> 1 // 영(십·백·천…)·삼·육: ㄹ이 아닌 받침
        else -> NO_BATCHIM
    }
    else -> NO_BATCHIM
}

private const val NO_BATCHIM = 0
private const val RIEUL = 8
