package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncKickoffWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result{
        HistoricalWorker.start(applicationContext, replace=false)
        return Result.success()
    }
}
