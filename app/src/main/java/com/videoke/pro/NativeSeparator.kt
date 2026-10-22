package com.videoke.pro
class NativeSeparator {
    external fun run(wave:String,vocalModel:String,instrumentalModel:String,vocals:String,instrumental:String):String
    companion object { init { System.loadLibrary("videoke") } }
}
