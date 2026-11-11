package com.autotyper

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object TxtLoader {
    const val MAX_BYTES = 1_000_000

    class Result(val name: String, val text: String)

    fun load(ctx: Context, uri: Uri): Result {
        val out = ByteArrayOutputStream()
        val stream = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("no stream")
        stream.use {
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw IllegalArgumentException("too big")
            }
        }
        val data = out.toByteArray()

        var text: String
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
            text = String(data, 2, data.size - 2, Charsets.UTF_16LE)
        } else if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            text = String(data, 2, data.size - 2, Charsets.UTF_16BE)
        } else {
            var start = 0
            if (data.size >= 3 && data[0] == 0xEF.toByte() &&
                data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()
            ) start = 3
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            text = try {
                decoder.decode(ByteBuffer.wrap(data, start, data.size - start)).toString()
            } catch (e: Exception) {
                String(data, start, data.size - start, charset("windows-1251"))
            }
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n')

        var name = "файл"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
        }
        return Result(name, text)
    }
}
