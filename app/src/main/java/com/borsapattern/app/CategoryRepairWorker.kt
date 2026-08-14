package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CategoryRepairWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result{
        return try{
            val dao=(applicationContext as BorsaApp).db.dao()
            val all=dao.allSymbols()
            for(s in all){
                val t=MarketPrefs.classifyType(s.symbol,s.name,s.flow,s.boardTitle)
                if(t!=s.instrumentType) dao.updateInstrumentType(s.insCode,t)
            }
            Result.success()
        }catch(_:Exception){
            Result.retry()
        }
    }
}
