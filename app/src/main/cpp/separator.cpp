#include <jni.h>
#include <cstring>
#include <string>
#include "sherpa-onnx/c-api/c-api.h"
// Verificado contra os exemplos oficiais do sherpa-onnx C API.
extern "C" JNIEXPORT jstring JNICALL
Java_com_videoke_pro_NativeSeparator_run(JNIEnv *env, jobject, jstring input, jstring vocModel,
                                          jstring accModel, jstring vocOutput, jstring accOutput) {
    const char *in = env->GetStringUTFChars(input,nullptr);
    const char *vm = env->GetStringUTFChars(vocModel,nullptr);
    const char *am = env->GetStringUTFChars(accModel,nullptr);
    const char *vo = env->GetStringUTFChars(vocOutput,nullptr);
    const char *ao = env->GetStringUTFChars(accOutput,nullptr);
    std::string error;
    SherpaOnnxOfflineSourceSeparationConfig cfg;
    std::memset(&cfg,0,sizeof(cfg));
    cfg.model.spleeter.vocals=vm;
    cfg.model.spleeter.accompaniment=am;
    cfg.model.num_threads=4;
    cfg.model.provider="cpu";
    const auto *engine=SherpaOnnxCreateOfflineSourceSeparation(&cfg);
    if(!engine) {error="Não foi possível iniciar o modelo Spleeter";} else {
        const auto *wav=SherpaOnnxReadWaveMultiChannel(in);
        if(!wav){error="Áudio inválido: requer WAV PCM (conversão necessária)";}else{
            const auto *out=SherpaOnnxOfflineSourceSeparationProcess(engine,wav->samples,wav->num_channels,wav->num_samples,wav->sample_rate);
            if(!out || out->num_stems<2)error="Falha ao executar separação de vocais";
            else {
                const char *files[]={vo,ao};
                for(int i=0;i<2;i++){
                    const auto &stem=out->stems[i];
                    SherpaOnnxWriteWaveMultiChannel((const float *const *)stem.samples,stem.n,out->sample_rate,stem.num_channels,files[i]);
                }
            }
            if(out)SherpaOnnxDestroySourceSeparationOutput(out);
            SherpaOnnxFreeMultiChannelWave(wav);
        }
        SherpaOnnxDestroyOfflineSourceSeparation(engine);
    }
    env->ReleaseStringUTFChars(input,in);env->ReleaseStringUTFChars(vocModel,vm);
    env->ReleaseStringUTFChars(accModel,am);env->ReleaseStringUTFChars(vocOutput,vo);
    env->ReleaseStringUTFChars(accOutput,ao);
    return env->NewStringUTF(error.c_str());
}
