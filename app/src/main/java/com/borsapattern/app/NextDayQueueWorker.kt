package com.borsapattern.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class NextDayQueueWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val api=TsetmcClient()
    private val prefs get()=applicationContext.getSharedPreferences("nextday",Context.MODE_PRIVATE)

    override suspend fun doWork():Result=coroutineScope{
        val totalPendingBefore=dao.nextDayPendingCount()
        val completedBefore=dao.nextDayCompletedCount()
        val items=dao.pendingNextDayChecks(48)

        if(items.isEmpty()){
            finishNextDayStage()
            return@coroutineScope Result.success()
        }

        prefs.edit()
            .putBoolean("running",true)
            .putInt("total",totalPendingBefore+completedBefore)
            .putInt("done",completedBefore)
            .putString(
                "status",
                "نتیجه روز کاری بعد: $completedBefore از ${totalPendingBefore+completedBefore}"
            )
            .apply()

        var processed=0
        for(chunk in items.chunked(4)){
            chunk.map{e->
                async(Dispatchers.IO){
                    checkOne(e)
                }
            }.awaitAll()
            processed += chunk.size

            val pending=dao.nextDayPendingCount()
            val done=dao.nextDayCompletedCount()
            prefs.edit()
                .putInt("done",done)
                .putInt("total",done+pending)
                .putString("status","نتیجه روز کاری بعد: $done از ${done+pending}")
                .apply()
            setProgress(workDataOf("done" to done,"total" to done+pending))
        }

        if(dao.nextDayPendingCount()>0){
            val req=OneTimeWorkRequestBuilder<NextDayQueueWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInitialDelay(2,TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                CHAIN,ExistingWorkPolicy.APPEND_OR_REPLACE,req
            )
        }else{
            finishNextDayStage()
        }
        Result.success()
    }

    private suspend fun finishNextDayStage(){
        val done=dao.nextDayCompletedCount()
        prefs.edit()
            .putBoolean("running",false)
            .putInt("done",done)
            .putInt("total",done)
            .putString("status","نتیجه روز کاری بعد کامل شد؛ Walk-Forward شروع شد")
            .apply()

        QueuePatternLearningEngine.rebuild(applicationContext)

        val pre=OneTimeWorkRequestBuilder<PreQueueBacktestWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .setInputData(workDataOf("batch" to 24))
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            PreQueueBacktestWorker.CHAIN,
            ExistingWorkPolicy.REPLACE,
            pre
        )
    }

    private suspend fun checkOne(e:QueueEventEntity){
        val next=dao.nextTradingDaily(e.insCode,e.date) ?: run {
            dao.updateNextDayResult(e.insCode,e.date,null,"NO_NEXT_DAY",null)
            return
        }

        val nextLast=next.last
        val nextYesterday=next.yesterday
        val returnPct=if(
            nextLast!=null && nextYesterday!=null && nextYesterday>0
        ) (nextLast/nextYesterday-1.0)*100.0 else null

        if(PatternEngine.isLikelySpecialReopen(dao,e.insCode,next.date)){
            dao.updateNextDayResult(
                e.insCode,e.date,next.date,"NEXT_DAY_SPECIAL_REOPEN",returnPct
            )
            return
        }

        var preopenOk=false
        var intradayOk=false
        var bookAvailable=false

        try{
            val arr=withTimeout(15_000L){
                api.jsonArrayFrom(
                    api.bestLimitsRaw(e.insCode,next.date),
                    "bestLimitsHistory","bestLimits"
                )
            }
            bookAvailable=arr.length()>0
            val high=next.high ?: 0.0

            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val rowTime=firstInt(o,"hEven","time") ?: continue
                if((firstInt(o,"number","level")?:1)!=1) continue

                val p=firstDouble(o,"pMeDem","bidPrice") ?: continue
                val b=firstDouble(o,"qTitMeDem","bidVolume") ?: 0.0
                val a=firstDouble(o,"qTitMeOf","askVolume") ?: 0.0
                if(b<=0) continue

                val imb=if(b+a>0)b/(b+a) else 0.0
                val realQueue=high>0 && p>=high*0.9995 && (a<=0.0 || imb>=0.92)

                if(rowTime in 84500..85959 && realQueue){
                    preopenOk=true
                    break
                }
                if(rowTime in 90000..123000 && realQueue){
                    intradayOk=true
                }
            }
        }catch(_:Exception){
            // Important: a historical BestLimits outage must not keep this row
            // PENDING forever. Daily return is still enough for the integrated outcome.
        }

        val result=when{
            preopenOk -> "PREOPEN_QUEUE_NEXT_DAY"
            intradayOk -> "QUEUE_AGAIN"
            returnPct!=null && returnPct>=2.0 -> "POSITIVE_STRONG_NEXT_DAY"
            returnPct!=null && returnPct>0.0 -> "POSITIVE_NEXT_DAY"
            returnPct!=null && returnPct>=-0.5 -> "FLAT_NEXT_DAY"
            returnPct!=null -> "NEGATIVE_NEXT_DAY"
            bookAvailable -> "NOT_QUEUE_NEXT_DAY"
            else -> "NEXT_DAY_DAILY_ONLY"
        }

        dao.updateNextDayResult(
            e.insCode,e.date,next.date,result,returnPct
        )
    }

    companion object{const val CHAIN="next_day_queue_chain"}
}
