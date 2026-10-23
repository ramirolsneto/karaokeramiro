package com.videoke.pro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.io.RandomAccessFile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Bounded song-memory Demucs runner: one fixed 7.8s input/output tensor and a 1.95s overlap buffer. */
class DemucsSeparator {
    companion object {
        const val SAMPLE_RATE = 44100
        const val SEGMENT = 343980
        const val OVERLAP = SEGMENT / 4
        const val STRIDE = SEGMENT - OVERLAP
        const val STEM_COUNT = 4
        const val VOCALS_INDEX = 3
        const val INPUT_NAME = "mix"
        const val OUTPUT_NAME = "stems"
        const val REVISION = "2ef0d757d3e226d0da85fb8c71514f464fcabdd0"
    }

    fun separate(input: File, model: File, vocals: File, instrumental: File) {
        val wave = AudioTools.readWaveFormat(input) ?: throw IOException("WAV de entrada inválido")
        if (wave.rate != SAMPLE_RATE || wave.channels != 2 || wave.encoding != 16)
            throw IOException("Demucs requer WAV PCM 16-bit, estéreo, 44,1 kHz")
        val frames = wave.dataLength / 4
        if (frames <= 0) throw IOException("Áudio vazio")
        val chunks = max(1, ceil(frames.toDouble() / STRIDE).toInt())
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        val session = env.createSession(model.absolutePath, opts)
        try {
            RandomAccessFile(input, "r").use { mixFile ->
                RandomAccessFile(vocals, "rw").use { vocalFile ->
                    RandomAccessFile(instrumental, "rw").use { instFile ->
                        vocalFile.setLength(0); instFile.setLength(0)
                        vocalFile.write(ByteArray(44)); instFile.write(ByteArray(44))
                        val previousTail = Array(2) { FloatArray(OVERLAP) }
                        var haveTail = false
                        var written = 0L
                        val window = DemucsMath.window()
                        for (ci in 0 until chunks) {
                            val start = ci.toLong() * STRIDE
                            val valid = min(SEGMENT.toLong(), frames - start).toInt()
                            val inputData = FloatArray(2 * SEGMENT)
                            readMix(mixFile, wave.dataStart, start, valid, inputData)
                            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), longArrayOf(1, 2, SEGMENT.toLong()))
                            val result = try { session.run(mapOf(INPUT_NAME to tensor)) } finally { tensor.close() }
                            val outTensor = result.get(OUTPUT_NAME).orElse(null) as? OnnxTensor ?: throw IOException("Demucs: tensor '$OUTPUT_NAME' ausente")
                            if (!outTensor.getInfo().shape.contentEquals(longArrayOf(1, STEM_COUNT.toLong(), 2, SEGMENT.toLong()))) {
                                result.close(); throw IOException("Demucs: formato de saída inesperado ${outTensor.getInfo().shape.contentToString()}")
                            }
                            val output = outTensor.floatBuffer
                            val vocalsOffset = VOCALS_INDEX * 2 * SEGMENT
                            val chunkVocals = FloatArray(2 * SEGMENT)
                            for (i in chunkVocals.indices) chunkVocals[i] = output.get(vocalsOffset + i)
                            result.close()
                            var local = 0
                            if (haveTail) {
                                val n = min(OVERLAP, valid)
                                val v = Array(2) { FloatArray(n) }
                                for (ch in 0..1) for (j in 0 until n) {
                                    val w = window[j]
                                    val priorW = window[STRIDE + j]
                                    val curW = w
                                    val den = priorW + curW
                                    v[ch][j] = (previousTail[ch][j] * priorW + chunkVocals[ch * SEGMENT + j] * curW) / max(den, 1e-8f)
                                }
                                writeSegment(mixFile, wave.dataStart, start, 0, n, v, vocalFile, instFile)
                                written += n; local = n
                            }
                            val bodyEnd = if (ci == chunks - 1) valid else min(valid, STRIDE)
                            if (bodyEnd > local) {
                                val n = bodyEnd - local
                                val v = Array(2) { FloatArray(n) }
                                for (ch in 0..1) for (j in 0 until n) {
                                    val idx = local + j
                                    v[ch][j] = chunkVocals[ch * SEGMENT + idx]
                                }
                                writeSegment(mixFile, wave.dataStart, start, local, bodyEnd, v, vocalFile, instFile)
                                written += n
                            }
                            if (ci < chunks - 1) {
                                for (ch in 0..1) for (j in 0 until OVERLAP)
                                    previousTail[ch][j] = chunkVocals[ch * SEGMENT + STRIDE + j]
                                haveTail = true
                            } else haveTail = false
                        }
                        if (written != frames) throw IOException("Demucs: saída incompleta ($written/$frames amostras)")
                        AudioTools.header(vocalFile, SAMPLE_RATE, 2, frames * 4)
                        AudioTools.header(instFile, SAMPLE_RATE, 2, frames * 4)
                    }
                }
            }
        } finally { session.close(); opts.close() }
    }

    private fun readMix(file: RandomAccessFile, dataStart: Long, start: Long, valid: Int, dst: FloatArray) {
        file.seek(dataStart + start * 4)
        val bytes = ByteArray(min(16384, valid * 4)); var done = 0
        while (done < valid) {
            val count = min(bytes.size / 4, valid - done)
            file.readFully(bytes, 0, count * 4)
            for (i in 0 until count) {
                val l = ((bytes[i*4].toInt() and 255) or (bytes[i*4+1].toInt() shl 8)).toShort().toInt() / 32768f
                val r = ((bytes[i*4+2].toInt() and 255) or (bytes[i*4+3].toInt() shl 8)).toShort().toInt() / 32768f
                dst[done+i] = l; dst[SEGMENT+done+i] = r
            }
            done += count
        }
    }

    private fun writeSegment(mix: RandomAccessFile, dataStart: Long, chunkStart: Long, from: Int, to: Int,
                             vocal: Array<FloatArray>, vf: RandomAccessFile, af: RandomAccessFile) {
        mix.seek(dataStart + (chunkStart + from) * 4)
        val bufV = ByteArray(min(16384, (to-from)*4)); val bufA = ByteArray(bufV.size)
        var pos = from
        while (pos < to) {
            val count = min(bufV.size / 4, to-pos); mix.readFully(bufV, 0, count*4)
            for (i in 0 until count) for (ch in 0..1) {
                val at = i*4+ch*2
                val m = ((bufV[at].toInt() and 255) or (bufV[at+1].toInt() shl 8)).toShort().toInt() / 32768f
                val v = vocal[ch][pos-from+i].coerceIn(-1f,1f)
                val a = (m-v).coerceIn(-1f,1f)
                val vi = (v*32767f).toInt().coerceIn(-32768,32767); val ai=(a*32767f).toInt().coerceIn(-32768,32767)
                bufV[at]=vi.toByte();bufV[at+1]=(vi shr 8).toByte();bufA[at]=ai.toByte();bufA[at+1]=(ai shr 8).toByte()
            }
            vf.write(bufV,0,count*4);af.write(bufA,0,count*4);pos+=count
        }
    }
}

/** Small pure helpers kept testable without an Android runtime. */
internal object DemucsMath {
    fun window(): FloatArray {
        val w=FloatArray(DemucsSeparator.SEGMENT){1f}; val n=DemucsSeparator.OVERLAP
        for(i in 0 until n){ val f=i.toFloat()/(n-1).coerceAtLeast(1);w[i]=f;w[DemucsSeparator.SEGMENT-n+i]=1f-f }
        return w
    }
    fun chunkCount(frames:Long):Int= max(1, ceil(frames.toDouble()/DemucsSeparator.STRIDE).toInt())
    fun vocalOffset(channel:Int,sample:Int)=DemucsSeparator.VOCALS_INDEX*2*DemucsSeparator.SEGMENT+channel*DemucsSeparator.SEGMENT+sample
}
