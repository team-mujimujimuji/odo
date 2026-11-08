package com.mujimujimuji.odo.ui.profile

import androidx.compose.ui.graphics.Color
import com.mujimujimuji.odo.engine.LevelTable

data class MinicarColor(val id: String, val name: String, val color: Color, val unlockLevel: Int)

/** 기본 1색 + 레벨 5 해금 5색(기획서 레벨 표). */
object MinicarPalette {
    const val SLOT = "color"
    val default = MinicarColor("sky", "하늘", Color(0xFF5AA9E6), 1)
    val all = listOf(
        default,
        MinicarColor("cherry", "체리", Color(0xFFE5484D), LevelTable.colorUnlockLevel),
        MinicarColor("mint", "민트", Color(0xFF3CC7A6), LevelTable.colorUnlockLevel),
        MinicarColor("lavender", "라벤더", Color(0xFF9B8CFF), LevelTable.colorUnlockLevel),
        MinicarColor("tangerine", "귤", Color(0xFFFF9F43), LevelTable.colorUnlockLevel),
        MinicarColor("pearl", "펄 화이트", Color(0xFFF1F3F6), LevelTable.colorUnlockLevel),
    )

    fun byId(id: String?): MinicarColor = all.firstOrNull { it.id == id } ?: default
}
