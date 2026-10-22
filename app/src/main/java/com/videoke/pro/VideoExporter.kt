package com.videoke.pro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.max

/** Primeiro renderizador local experimental: fundo colorido, letras temporizadas e áudio AAC.
 * Não depende do FFmpeg/Modal. Codifica H.264/AAC via MediaCodec do próprio Android.
 * Requer suporte a entrada YUV420 semiplanar do encoder disponível no dispositivo.
 */
object VideoExporter {
    data class Line(val time:Double,val text:String)
    private data class Sample(val data:ByteArray,val pts:Long,val flags:Int)
    fun export(wav:File,target:File,lines:List<Line>,onProgress:(Int)->Unit){
        val wave=AudioTools.readWaveFormat(wav)?:error("WAV não suportado")
        val durationMs=wave.dataLength*1000/(wave.rate*wave.channels*2)
        require(durationMs in 1..(20*60*1000)) {"A exportação local aceita duração de até 20 minutos"}
        val width=640;val height=360;val fps=4
        val videoFormat=MediaFormat.createVideoFormat("video/avc",width,height).apply{
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE,850_000)
            setInteger(MediaFormat.KEY_FRAME_RATE,fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,4)
        }
        val audioFormat=MediaFormat.createAudioFormat("audio/mp4a-latm",wave.rate,wave.channels).apply{
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,128_000)
        }
        val v=MediaCodec.createEncoderByType("video/avc")
        val a=MediaCodec.createEncoderByType("audio/mp4a-latm")
        v.configure(videoFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
        a.configure(audioFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
        val mux=MediaMuxer(target.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack=-1;var audioTrack=-1;var started=false
        val pendingVideo=mutableListOf<Sample>();val pendingAudio=mutableListOf<Sample>()
        val info=MediaCodec.BufferInfo()
        fun drain(codec:MediaCodec,isVideo:Boolean):Boolean{
            var eos=false
            while(true){val index=codec.dequeueOutputBuffer(info,0)
                if(index==MediaCodec.INFO_TRY_AGAIN_LATER)break
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(isVideo)videoTrack=mux.addTrack(codec.outputFormat) else audioTrack=mux.addTrack(codec.outputFormat)
                    if(!started&&videoTrack>=0&&audioTrack>=0){mux.start();started=true
                        for(s in pendingVideo){val b=ByteBuffer.wrap(s.data);mux.writeSampleData(videoTrack,b,MediaCodec.BufferInfo().also{it.set(0,s.data.size,s.pts,s.flags)})};pendingVideo.clear()
                        for(s in pendingAudio){val b=ByteBuffer.wrap(s.data);mux.writeSampleData(audioTrack,b,MediaCodec.BufferInfo().also{it.set(0,s.data.size,s.pts,s.flags)})};pendingAudio.clear()
                    }
                }else if(index>=0){
                    if((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)eos=true
                    if(info.size>0&&(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                        val buffer=codec.getOutputBuffer(index)!!;buffer.position(info.offset);buffer.limit(info.offset+info.size)
                        val arr=ByteArray(info.size);buffer.get(arr)
                        if(started)mux.writeSampleData(if(isVideo)videoTrack else audioTrack,ByteBuffer.wrap(arr),MediaCodec.BufferInfo().also{it.set(0,arr.size,info.presentationTimeUs,info.flags)})
                        else (if(isVideo)pendingVideo else pendingAudio).add(Sample(arr,info.presentationTimeUs,info.flags))
                    }
                    codec.releaseOutputBuffer(index,false)
                }
            };return eos
        }
        v.start();a.start()
        try{
            val canvasBmp=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(canvasBmp)
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            val frames=max(1,((durationMs+249)/250).toInt())
            var last=-1
            // Prioritize audio track discovery early to allow muxer to start.
            RandomAccessFile(wav,"r").use{raf->
                raf.seek(wave.dataStart)
                val length=wave.dataLength
                var consumed=0L
                var frame=0
                while(frame<frames||consumed<length){
                    if(frame<frames){
                        val idx=v.dequeueInputBuffer(100000)
                        if(idx>=0){
                            val ms=frame*1000L/fps
                            canvas.drawColor(Color.rgb(13,13,29))
                            p.color=Color.rgb(115,88,210);p.textSize=28f;p.textAlign=Paint.Align.CENTER;canvas.drawText("VIDEOKE IA PRO",(width/2).toFloat(),50f,p)
                            p.color=Color.WHITE;p.textSize=30f
                            val active=lines.indexOfLast{it.time*1000<=ms}
                            val text=lines.getOrNull(active)?.text?:"♪ Instrumental ♪"
                            val wrapped=wrap(text,33)
                            for((n,t) in wrapped.take(3).withIndex())canvas.drawText(t,width/2f,145f+n*40,p)
                            p.color=Color.rgb(132,165,255);p.textSize=20f
                            lines.getOrNull(active+1)?.let{canvas.drawText(it.text.take(43),width/2f,285f,p)}
                            val pixels=IntArray(width*height);canvasBmp.getPixels(pixels,0,width,0,0,width,height)
                            val yuv=nv12(pixels,width,height);val input=v.getInputBuffer(idx)!!;input.clear();input.put(yuv)
                            v.queueInputBuffer(idx,0,yuv.size,frame*1_000_000L/fps,0);frame++
                        }
                        drain(v,true)
                    }
                    if(consumed<length){
                        val idx=a.dequeueInputBuffer(100000)
                        if(idx>=0){
                            val input=a.getInputBuffer(idx)!!;input.clear()
                            val chunk=ByteArray(minOf(input.remaining().toLong(),length-consumed).toInt());val read=raf.read(chunk)
                            if(read>0){input.put(chunk,0,read);a.queueInputBuffer(idx,0,read,consumed*1_000_000/(wave.rate*wave.channels*2),0);consumed+=read}
                            else consumed=length
                        }
                        drain(a,false)
                    }
                    val pct=(100*frame/frames).coerceIn(0,100)
                    if(pct!=last){onProgress(pct);last=pct}
                }
                val vi=v.dequeueInputBuffer(500000);if(vi>=0)v.queueInputBuffer(vi,0,0,frames*1_000_000L/fps,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val ai=a.dequeueInputBuffer(500000);if(ai>=0)a.queueInputBuffer(ai,0,0,durationMs*1000,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                repeat(200){val ve=drain(v,true);val ae=drain(a,false);if(ve&&ae)return@repeat;Thread.sleep(10)}
            }
            canvasBmp.recycle()
            if(!started)error("Codificador não iniciou as faixas de áudio e vídeo")
        }finally{try{v.stop()}catch(_:Exception){};try{a.stop()}catch(_:Exception){};v.release();a.release();try{if(started)mux.stop()}catch(_:Exception){};mux.release()}
    }
    private fun wrap(text:String,max:Int):List<String>{val lines=mutableListOf<String>();var part="";for(w in text.split(' ')){if((part+' '+w).trim().length>max){lines.add(part);part=w}else part=(part+' '+w).trim()};if(part.isNotEmpty())lines.add(part);return lines}
    private fun nv12(px:IntArray,w:Int,h:Int):ByteArray{
        val out=ByteArray(w*h*3/2);var yIndex=0;var uvIndex=w*h
        for(j in 0 until h)for(i in 0 until w){val c=px[j*w+i];val r=(c shr 16) and 255;val g=(c shr 8) and 255;val b=c and 255
            out[yIndex++]=((66*r+129*g+25*b+128 shr 8)+16).coerceIn(0,255).toByte()
            if(j%2==0&&i%2==0){out[uvIndex++]=((-38*r-74*g+112*b+128 shr 8)+128).coerceIn(0,255).toByte();out[uvIndex++]=((112*r-94*g-18*b+128 shr 8)+128).coerceIn(0,255).toByte()}
        };return out
    }
}
