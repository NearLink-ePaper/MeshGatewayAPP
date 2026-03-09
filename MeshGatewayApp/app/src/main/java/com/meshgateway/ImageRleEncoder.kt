package com.meshgateway

/**
 * RLE (游程编码) 编码器 —— 用于 1bpp 二值图像压缩
 *
 * 编码格式与固件端 image_rle.c 解码器完全匹配:
 *   Literal (0xxxxxxx): 7 个原始像素
 *   RLE     (1vNccccc): 连续 ≥8 个相同像素, varint 编码长度
 *
 * 像素按 MSB-first 从字节中提取 (bit7 = 第一个像素),
 * 与 C 端解码器写入顺序一致, 保证编解码 round-trip 字节一致。
 */
object ImageRleEncoder {

    /**
     * 对 1bpp 像素数据进行 RLE 压缩
     *
     * @param rawBytes    原始 1bpp 数据 (每字节 8 像素, MSB first)
     * @param totalPixels 总像素数 (width × height)
     * @return 压缩后的字节数组
     */
    fun encode(rawBytes: ByteArray, totalPixels: Int): ByteArray {
        // 展开为像素数组 (MSB first)
        val pixels = IntArray(totalPixels)
        var idx = 0
        for (byte in rawBytes) {
            val b = byte.toInt() and 0xFF
            for (bit in 7 downTo 0) {
                if (idx >= totalPixels) break
                pixels[idx++] = (b shr bit) and 1
            }
            if (idx >= totalPixels) break
        }

        val output = mutableListOf<Int>()
        var i = 0

        while (i < pixels.size) {
            val v = pixels[i]
            var run = 1
            while (i + run < pixels.size && pixels[i + run] == v) run++

            if (run >= 8) {
                // RLE: 连续 ≥ 8 个相同像素
                val count = run - 8
                val low5 = count and 0x1F
                val hasMore = (count shr 5) > 0
                output.add(0x80 or (v shl 6) or ((if (hasMore) 1 else 0) shl 5) or low5)
                if (hasMore) {
                    var remaining = count shr 5
                    while (remaining > 0) {
                        val part = remaining and 0x7F
                        remaining = remaining shr 7
                        output.add(if (remaining > 0) (part or 0x80) else part)
                    }
                }
                i += run
            } else {
                // Literal: 打包 7 个像素
                var literal = 0
                for (k in 0 until 7) {
                    if (i + k < pixels.size) {
                        literal = literal or (pixels[i + k] shl (6 - k))
                    }
                }
                output.add(literal) // bit7 = 0
                i += 7
            }
        }

        return ByteArray(output.size) { output[it].toByte() }
    }
}
