package com.kang.ubcm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 本地头像：裁剪 1:1 → 缩到 256×256 → 存 `filesDir/avatar.png`。
 *
 * 🔴 **不上传、不联网、不需要存储权限**（Photo Picker 返回的是受控 Uri，只读一次）。
 */
object AvatarStore {

    private const val FILE = "avatar.png"
    private const val SIZE = 256

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun has(ctx: Context): Boolean = file(ctx).exists()

    fun load(ctx: Context): Bitmap? = try {
        val f = file(ctx)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (e: Exception) {
        null
    }

    /** 从相册 Uri 导入：居中裁正方形、缩 256、覆盖保存。 */
    fun importFrom(ctx: Context, uri: Uri): Boolean = try {
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return false
        save(ctx, bmp)
    } catch (e: Exception) {
        false
    }

    /** 从拍照缩略图导入。 */
    fun importBitmap(ctx: Context, bmp: Bitmap): Boolean = try {
        save(ctx, bmp)
    } catch (e: Exception) {
        false
    }

    private fun save(ctx: Context, source: Bitmap): Boolean {
        val side = minOf(source.width, source.height)
        if (side <= 0) return false
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        val square = Bitmap.createBitmap(source, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        FileOutputStream(file(ctx)).use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (square !== source) square.recycle()
        if (scaled !== square) scaled.recycle()
        return true
    }

    fun clear(ctx: Context): Boolean = try {
        file(ctx).delete()
    } catch (e: Exception) {
        false
    }
}
