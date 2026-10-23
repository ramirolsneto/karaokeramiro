package com.videoke.pro

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

class MainActivity:Activity(){
    private lateinit var web:WebView
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var file:File?=null
    private var instrumental:File?=null
    private var vocals:File?=null
    private var video:File?=null
    private var player:MediaPlayer?=null
    private var pitch=0
    private val busy=AtomicBoolean(false)
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        web=WebView(this)
        web.settings.javaScriptEnabled=true
        web.settings.domStorageEnabled=true
        web.settings.allowFileAccess=true
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?):Boolean {
                // Never give untrusted web content access to the native JS bridge.
                return request?.url?.scheme!="file"
            }
        };web.webChromeClient=WebChromeClient()
        web.addJavascriptInterface(Bridge(),"Native")
        setContentView(web);web.loadUrl("file:///android_asset/index.html")
    }
    override fun onDestroy(){player?.release();web.destroy();super.onDestroy()}
    private fun send(evt:String,value:Any){val serialized=JSONObject.quote(value.toString());runOnUiThread{web.evaluateJavascript("window.nativeEvent(${JSONObject.quote(evt)},$serialized)",null)}}
    private fun inform(s:String)=send("status",s)
    private fun baseDir():File=File(getExternalFilesDir(null),"projects").apply{mkdirs()}
    private fun modelDir():File=File(filesDir,"models").apply{mkdirs()}
    private fun copyAssetModel(name:String):File{
        val f=File(modelDir(),name)
        if(!f.exists()){assets.open("models/$name").use{input->f.outputStream().use{input.copyTo(it)}}}
        return f
    }
    inner class Bridge {
        @JavascriptInterface fun chooseFile(){runOnUiThread{val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="audio/*";addCategory(Intent.CATEGORY_OPENABLE)};startActivityForResult(i,71)}}
        @JavascriptInterface fun openYoutube(url:String){runOnUiThread{val u=runCatching{Uri.parse(url)}.getOrNull();if(u?.scheme!="https"||u.host==null||u.host !in listOf("youtube.com","www.youtube.com","m.youtube.com","youtu.be","music.youtube.com")){inform("Link do YouTube inválido");return@runOnUiThread};startActivity(Intent(Intent.ACTION_VIEW,u))}}
        @JavascriptInterface fun separate(){launchJob("Separação em andamento"){
            val source=file?:error("Selecione um arquivo próprio para começar")
            val work=baseDir();val input=File(work,"input-${UUID.randomUUID()}.wav")
            inform("Decodificando áudio no Android…")
            AudioTools.prepareDemucsWav(this@MainActivity,source,input)
            inform("Carregando Demucs no celular (CPU)…")
            val model=copyAssetModel("htdemucs_ft_vocals.onnx")
            val vc=File(work,"voz-${UUID.randomUUID()}.wav");val ac=File(work,"instrumental-${UUID.randomUUID()}.wav")
            DemucsSeparator().separate(input,model,vc,ac)
            check(vc.exists()&&ac.exists()){"Não foram geradas as faixas"}
            vocals=vc;instrumental=ac;video=null
            input.delete();send("separated","Faixas de voz e instrumental salvas no celular.")
        }}
        @JavascriptInterface fun whisper(){launchJob("Whisper em execução"){
            val source=vocals?:error("Primeiro separe a voz do instrumental")
            inform("Iniciando Whisper (modelo multilíngue)…")
            val model=copyAssetModel("ggml-base.bin")
            // Este AAR executa transcrição local em ARM64 e devolve tempos por segmento.
            val w=Whisper.loadModel(this@MainActivity,model.absolutePath)
            try{
                val result=Whisper.transcribe(w,source.absolutePath,WhisperConfig(language="pt"))
                val lrc=result.segments.joinToString("\n"){s->
                    val sec=s.startMs/1000.0
                    val mm=(sec/60).toInt();val ss=sec-mm*60
                    "[%02d:%05.2f] %s".format(java.util.Locale.US,mm,ss,s.text.trim())
                }
                send("lyrics",lrc)
            }finally{Whisper.releaseModel(w)}
        }}
        @JavascriptInterface fun listen(which:String,newPitch:Int){runOnUiThread{
            val chosen=if(which=="voz")vocals else instrumental
            if(chosen==null){inform("Separe a música primeiro");return@runOnUiThread}
            try{player?.release();player=MediaPlayer().apply{setDataSource(chosen.absolutePath);prepare();playbackParams=PlaybackParams().setSpeed(1f).setPitch(2.0.pow(newPitch.coerceIn(-6,6)/12.0).toFloat());start()};pitch=newPitch;inform("Reproduzindo ${if(which=="voz")"vocal" else "instrumental"} · Tom $newPitch")}
            catch(e:Exception){inform("Player: ${e.message}")}
        }}
        @JavascriptInterface fun stopAudio(){runOnUiThread{player?.pause()}}
        @JavascriptInterface fun exportMp4(lrc:String,tom:Int){launchJob("Exportação MP4"){
            val stem=instrumental?:error("Primeiro separe a música")
            inform("Preparando instrumental no tom solicitado…")
            val pitched=File(baseDir(),"export-audio-${UUID.randomUUID()}.wav")
            AudioTools.pitchFile(stem,pitched,tom.coerceIn(-6,6))
            val lines=Regex("\\[(\\d+):(\\d+[.,]\\d+)\\](.*)").findAll(lrc).map{m->VideoExporter.Line(m.groupValues[1].toInt()*60+m.groupValues[2].replace(',','.').toDouble(),m.groupValues[3].trim())}.toList()
            val out=File(baseDir(),"videoke-${UUID.randomUUID()}.mp4")
            inform("Codificando MP4 com letras: aguarde…")
            VideoExporter.export(pitched,out,lines){i->if(i%10==0)inform("Montando MP4: $i%")}
            pitched.delete();video=out;send("exported","Vídeo exportado no armazenamento privado do aplicativo.")
        }}
        @JavascriptInterface fun saveMp4(){runOnUiThread{
            if(video==null){inform("Crie primeiro o videokê MP4");return@runOnUiThread}
            val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="video/mp4";putExtra(Intent.EXTRA_TITLE,"videoke-ia-pro.mp4")}
            startActivityForResult(intent,72)
        }}
        @JavascriptInterface fun publish(site:String,key:String,artist:String,title:String){launchJob("Publicação"){
            val mp4=video?:error("Gere primeiro o MP4 no celular")
            val host=Uri.parse(site.trim());require(host.scheme=="https"&&host.host!=null&&!host.host!!.contains("/")){"Informe o domínio HTTPS da Vercel"}
            require(key.length>=24){"Informe a chave MOBILE_PUBLISH_KEY da Vercel"}
            fun req(url:String,method:String,body:ByteArray?,headers:Map<String,String>):Pair<Int,String>{
                val c=(URL(url).openConnection() as HttpURLConnection)
                c.connectTimeout=20000;c.readTimeout=120000;c.requestMethod=method;c.instanceFollowRedirects=false
                headers.forEach{(k,v)->c.setRequestProperty(k,v)}
                if(body!=null){c.doOutput=true;c.outputStream.use{it.write(body)}}
                val status=c.responseCode;val text=(if(status<400)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}?:"";c.disconnect();return status to text
            }
            val root="${host.scheme}://${host.authority}"
            inform("Solicitando autorização temporária de upload…")
            val payload=JSONObject().put("artist",artist).put("title",title).toString().toByteArray()
            val (status,ticket)=req("$root/api/mobile-ticket","POST",payload,mapOf("X-Publish-Key" to key,"Content-Type" to "application/json"))
            require(status in 200..299){"Ticket de upload: $status $ticket"}
            val t=JSONObject(ticket);val uploadUrl=t.getString("upload_url")
            require(URL(uploadUrl).host.endsWith(".supabase.co")){"URL de upload inválida"}
            inform("Enviando MP4 diretamente ao Supabase…")
            val c=URL(uploadUrl).openConnection() as HttpURLConnection
            c.requestMethod="PUT";c.setRequestProperty("Content-Type","video/mp4");c.doOutput=true
            c.setFixedLengthStreamingMode(mp4.length());c.connectTimeout=30000;c.readTimeout=180000
            mp4.inputStream().use{inp->c.outputStream.use{inp.copyTo(it)}}
            val up=c.responseCode;val upText=(if(up>=400)c.errorStream else c.inputStream)?.bufferedReader()?.use{it.readText()}?:"";c.disconnect()
            require(up in 200..299){"Upload para Supabase falhou: $up $upText"}
            inform("Registrando vídeo na biblioteca familiar…")
            val meta=JSONObject().put("path",t.getString("path")).put("artist",artist).put("title",title).toString().toByteArray()
            val (done,ret)=req("$root/api/mobile-finish","POST",meta,mapOf("X-Publish-Key" to key,"Content-Type" to "application/json"))
            require(done in 200..299){"Falha ao registrar na biblioteca: $done $ret"}
            send("published","Publicado na Vercel! Atualize a biblioteca no navegador.")
        }}
    }
    private fun launchJob(label:String,task:suspend ()->Unit){
        if(!busy.compareAndSet(false,true)){inform("Aguarde o processamento atual finalizar.");return}
        scope.launch{
            try{inform(label);withContext(Dispatchers.IO){task()}}
            catch(e:Throwable){inform("Erro: ${e.message?.take(200)?:e.javaClass.simpleName}")}
            finally{busy.set(false)}
        }
    }
    @Deprecated("SAF") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==72&&resultCode==RESULT_OK){val uri=data?.data?:return;val src=video?:return
            launchJob("Salvando MP4 no celular"){
                contentResolver.openOutputStream(uri,"w")!!.use{output->src.inputStream().use{it.copyTo(output)}}
                send("saved","MP4 salvo no local escolhido no Android")
            }
        }
        if(requestCode==71&&resultCode==RESULT_OK){val uri=data?.data?:return
            launchJob("Importando arquivo"){
                val dest=File(baseDir(),"audio-${UUID.randomUUID()}."+(contentResolver.getType(uri)?.substringAfter('/')?:"bin"))
                AudioTools.copy(this@MainActivity,uri,dest)
                file=dest;instrumental=null;vocals=null;video=null
                send("selected",dest.name+" • "+(dest.length()/1048576.0).toInt()+" MB")
            }
        }
    }
}
