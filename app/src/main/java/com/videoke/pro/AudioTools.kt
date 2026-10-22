package com.videoke.pro
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

object AudioTools {
    fun copy(context:Context,uri:Uri,dest:File){context.contentResolver.openInputStream(uri)!!.use{input->FileOutputStream(dest).use{input.copyTo(it)}}}
    fun prepareWav(context:Context,src:File,dst:File){
        if(src.name.endsWith(".wav",true)&&readWaveFormat(src)?.let {it.channels in 1..2 && it.encoding==16}==true){src.copyTo(dst,true);return}
        decodeToWav(src,dst)
    }
    data class Wave(val rate:Int,val channels:Int,val encoding:Int,val dataStart:Long,val dataLength:Long)
    fun readWaveFormat(f:File):Wave?{
        RandomAccessFile(f,"r").use{a->if(a.length()<44)return null
            fun id():String{val b=ByteArray(4);a.readFully(b);return String(b,Charsets.US_ASCII)}
            fun leInt():Int=Integer.reverseBytes(a.readInt())
            fun leShort():Int=java.lang.Short.reverseBytes(a.readShort()).toInt() and 0xffff
            if(id()!="RIFF"){return null};a.skipBytes(4);if(id()!="WAVE")return null
            var sampleRate=0;var channels=0;var bits=0;var format=0;var start=0L;var size=0L
            while(a.filePointer+8<=a.length()){
                val chunk=id();val len=leInt().toLong() and 0xffffffffL;val next=a.filePointer+len+(len and 1)
                if(chunk=="fmt "&&len>=16){format=leShort();channels=leShort();sampleRate=leInt();a.skipBytes(6);bits=leShort()}
                if(chunk=="data"){start=a.filePointer;size=len;break}
                if(next>a.length())break;a.seek(next)
            }
            return if(format==1&&start>0)Wave(sampleRate,channels,bits,start,size) else null
        }
    }
    fun header(out:RandomAccessFile,rate:Int,channels:Int,dataLength:Long){
        fun w(s:String)=out.write(s.toByteArray(Charsets.US_ASCII))
        fun i(v:Int)=out.writeInt(Integer.reverseBytes(v))
        fun h(v:Int)=out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        out.seek(0);w("RIFF");i((36+dataLength).toInt());w("WAVEfmt ");i(16);h(1);h(channels);i(rate);i(rate*channels*2);h(channels*2);h(16);w("data");i(dataLength.toInt())
    }
    // MediaCodec decodes MP3/M4A/FLAC to PCM. For compressed input, codec output
    // must be PCM16; explicit error avoids interpreting floating PCM as signed 16 bit.
    fun decodeToWav(src:File,dst:File){
        val ex=MediaExtractor();ex.setDataSource(src.absolutePath)
        var selected=-1
        for(i in 0 until ex.trackCount){if(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true){selected=i;break}}
        if(selected<0){ex.release();throw IOException("Arquivo sem faixa de áudio")}
        ex.selectTrack(selected)
        val fmt=ex.getTrackFormat(selected);val mime=fmt.getString(MediaFormat.KEY_MIME)!!
        val decoder=MediaCodec.createDecoderByType(mime)
        decoder.configure(fmt,null,null,0);decoder.start()
        val out=RandomAccessFile(dst,"rw");out.setLength(0);out.write(ByteArray(44));var length=0L
        var rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);var chans=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info=MediaCodec.BufferInfo();var sentEnd=false;var gotEnd=false
        try{
            while(!gotEnd){
                if(!sentEnd){val idx=decoder.dequeueInputBuffer(10000);if(idx>=0){val input=decoder.getInputBuffer(idx)!!;val count=ex.readSampleData(input,0);if(count<0){decoder.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);sentEnd=true}else{decoder.queueInputBuffer(idx,0,count,ex.sampleTime,0);ex.advance()}}}
                val ix=decoder.dequeueOutputBuffer(info,10000)
                if(ix>=0){val output=decoder.getOutputBuffer(ix)!!;if(info.size>0){
                    val format=decoder.outputFormat
                    if(format.containsKey(MediaFormat.KEY_PCM_ENCODING)&&format.getInteger(MediaFormat.KEY_PCM_ENCODING)!=android.media.AudioFormat.ENCODING_PCM_16BIT)throw IOException("Codec retornou PCM não suportado")
                    val b=ByteArray(info.size);output.position(info.offset);output.limit(info.offset+info.size);output.get(b);out.write(b);length+=b.size
                };gotEnd=(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;decoder.releaseOutputBuffer(ix,false)
                }else if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){val of=decoder.outputFormat;rate=of.getInteger(MediaFormat.KEY_SAMPLE_RATE);chans=of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}
            }
            header(out,rate,chans,length)
        }finally{out.close();decoder.stop();decoder.release();ex.release()}
    }
    fun pitchFile(src:File,dst:File,semitones:Int){
        if(semitones==0){src.copyTo(dst,true);return}
        val w=readWaveFormat(src)?:throw IOException("Tom: arquivo WAV não suportado")
        val sonic=SonicAudioProcessor();sonic.setSpeed(1f);sonic.setPitch(2.0.pow(semitones/12.0).toFloat())
        sonic.configure(AudioProcessor.AudioFormat(w.rate,w.channels,C.ENCODING_PCM_16BIT));sonic.flush()
        RandomAccessFile(src,"r").use{read->RandomAccessFile(dst,"rw").use{out->
            out.write(ByteArray(44));var produced=0L;read.seek(w.dataStart);var remain=w.dataLength
            while(remain>0){val bytes=ByteArray(minOf(remain,16384L).toInt());val n=read.read(bytes);if(n<=0)break
                var input=ByteBuffer.wrap(bytes,0,n).order(ByteOrder.nativeOrder());while(input.hasRemaining()){sonic.queueInput(input);while(true){val b=sonic.getOutput();if(!b.hasRemaining())break;val chunk=ByteArray(b.remaining());b.get(chunk);out.write(chunk);produced+=chunk.size}}
                remain-=n
            }
            sonic.queueEndOfStream();while(!sonic.isEnded){val b=sonic.getOutput();if(b.hasRemaining()){val chunk=ByteArray(b.remaining());b.get(chunk);out.write(chunk);produced+=chunk.size}else Thread.yield()}
            header(out,w.rate,w.channels,produced)
        }}
        sonic.reset()
    }
}
