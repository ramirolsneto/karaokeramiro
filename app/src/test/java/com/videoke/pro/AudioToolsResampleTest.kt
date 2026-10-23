package com.videoke.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class AudioToolsResampleTest {
    @Test fun monoEightKhzPcmIsResampledToStereo44100() {
        val dir = createTempDir(prefix="resample-test")
        try {
            val input = File(dir, "in.wav")
            val output = File(dir, "out.wav")
            RandomAccessFile(input, "rw").use { f ->
                f.write(ByteArray(44))
                val pcm = ByteArray(8000 * 2)
                for (i in 0 until 8000) { pcm[2*i] = (i % 120).toByte(); pcm[2*i+1] = 0 }
                f.write(pcm)
                AudioTools.header(f, 8000, 1, pcm.size.toLong())
            }
            AudioTools.resampleToStereo44100(input, output)
            val wav = AudioTools.readWaveFormat(output)
            assertNotNull(wav)
            assertEquals(44100, wav!!.rate)
            assertEquals(2, wav.channels)
            assertEquals(16, wav.encoding)
            assertEquals(44100L * 4, wav.dataLength)
        } finally { dir.deleteRecursively() }
    }
}
