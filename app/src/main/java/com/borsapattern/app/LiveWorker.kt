package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LiveWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result{
        return try{
            LiveScanEngine.scanOnce(applicationContext)
            Result.success()
        }catch(_:Exception){
            Result.retry()
        }
    }
}
