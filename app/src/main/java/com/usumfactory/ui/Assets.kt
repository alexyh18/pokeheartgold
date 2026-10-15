package com.usumfactory.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Decodes and caches sprite PNGs from assets/sprites. */
class Sprites(private val open: (String) -> java.io.InputStream) {
    constructor(assets: AssetManager) : this({ path: String -> assets.open(path) })

    private val cache = HashMap<String, ImageBitmap?>()

    private fun load(path: String): ImageBitmap? = cache.getOrPut(path) {
        runCatching { open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }

    fun front(species: Int) = load("sprites/front/$species.png")
    fun back(species: Int) = load("sprites/back/$species.png")
    /** 32x64 icon sheet (two animation frames stacked) */
    fun icon(species: Int) = load("sprites/icon/$species.png")
    fun trainer(cell: Int) = load("sprites/trainer/$cell.png")
    fun battleBackground(name: String) = load("sprites/battle_bg/$name.png")
}
