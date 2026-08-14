package com.borsapattern.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PreQueueBacktestWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private data class Book(
        val time:Int,val bidPrice:Double,val bidVol:Double,val askVol:Double
    )
    private val app get()=applicationContext as BorsaApp
    private val dao get()=app.db.dao()
    private val api=TsetmcClient()
    private val prefs get()=applicationContext.getSharedPreferences("prequeue_backtest",Context.MODE_PRIVATE)

    override suspend fun doWork():Result{
        // Algorithm v2 removes look-ahead. Old derived snapshots are invalid and
        // are rebuilt from the already-downloaded historical data.
        if(prefs.getInt("causal_model_version",0)<2){
            dao.deleteAllPreQueueSnapshots()
            prefs.edit()
                .clear()
                .putInt("causal_model_version",2)
                .putString(
                    "status",
                    "مدل علّی جدید فعال شد؛ Walk-Forward از داده تاریخی موجود بازسازی می‌شود"
                )
                .apply()
        }

        val batch=inputData.getInt("batch",24).coerceIn(8,40)
        val sql=app.db.openHelper.readableDatabase

        val c=sql.query("""
            SELECT e.insCode,e.date,e.eventTime,e.status
            FROM queue_events e
            WHERE e.status IN ('QUEUE_CONFIRMED','NOT_QUEUE','FRAGILE_QUEUE')
              AND NOT EXISTS(
                SELECT 1 FROM prequeue_snapshots p
                WHERE p.insCode=e.insCode AND p.date=e.date
              )
            ORDER BY e.date ASC
            LIMIT $batch
        """.trimIndent())

        data class WorkItem(
            val insCode:String,val date:Int,val eventTime:Int?,val status:String
        )
        val items=mutableListOf<WorkItem>()
        c.use{
            while(it.moveToNext()){
                items += WorkItem(
                    insCode=it.getString(0),
                    date=it.getInt(1),
                    eventTime=if(it.isNull(2)) null else it.getInt(2),
                    status=it.getString(3)
                )
            }
        }

        if(items.isEmpty()){
            rebuildMetrics()
            val done=dao.walkForwardProcessedCount()
            val total=dao.walkForwardEligibleCount()
            prefs.edit()
                .putBoolean("running",false)
                .putInt("events_done",done)
                .putInt("events_total",total)
                .putString("status","Walk-Forward کامل شد: $done از $total رخداد")
                .apply()
            return Result.success()
        }

        val total=dao.walkForwardEligibleCount()
        prefs.edit()
            .putBoolean("running",true)
            .putInt("events_total",total)
            .putInt("events_done",dao.walkForwardProcessedCount())
            .putString("status","Walk-Forward علّی: هر Snapshot فقط با داده همان لحظه و قبل از آن")
            .apply()

        for(item in items){
            try{
                analyzeDay(item.insCode,item.date,item.eventTime,item.status)
            }catch(_:Exception){
                markUnavailable(item.insCode,item.date)
            }
            val done=dao.walkForwardProcessedCount()
            prefs.edit()
                .putInt("events_done",done)
                .putInt("events_total",total)
                .putString("status","Walk-Forward بدون Look-Ahead: $done از $total رخداد")
                .apply()
            setProgress(workDataOf("done" to done,"total" to total))
        }

        val next=OneTimeWorkRequestBuilder<PreQueueBacktestWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .setInputData(workDataOf("batch" to batch))
            .setInitialDelay(1,TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            CHAIN,ExistingWorkPolicy.APPEND_OR_REPLACE,next
        )
        return Result.success()
    }

    private suspend fun analyzeDay(
        insCode:String,date:Int,eventTime:Int?,status:String
    ){
        val day=dao.dailyFor(insCode,date) ?: run{
            markUnavailable(insCode,date); return
        }
        // IMPORTANT: yesterday is known before today's session.
        // day.high is deliberately NOT used because it contains future information
        // relative to an intraday snapshot and would create look-ahead bias.
        val yesterday=day.yesterday ?: run{
            markUnavailable(insCode,date); return
        }
        if(yesterday<=0){
            markUnavailable(insCode,date); return
        }

        val arr=withTimeout(18_000L){
            api.jsonArrayFrom(
                api.bestLimitsRaw(insCode,date),
                "bestLimitsHistory","bestLimits"
            )
        }
        if(arr.length()==0){
            markUnavailable(insCode,date)
            return
        }

        val books=mutableListOf<Book>()
        for(i in 0 until arr.length()){
            val o=arr.optJSONObject(i)?:continue
            if((firstInt(o,"number","level")?:1)!=1) continue
            val t=firstInt(o,"hEven","time")?:continue
            if(t !in 90000..123000) continue
            val bp=firstDouble(o,"pMeDem","bidPrice")?:continue
            val bv=firstDouble(o,"qTitMeDem","bidVolume")?:0.0
            val av=firstDouble(o,"qTitMeOf","askVolume")?:0.0
            books += Book(t,bp,bv,av)
        }
        if(books.isEmpty()){
            markUnavailable(insCode,date)
            return
        }
        books.sortBy{it.time}

        val out=mutableListOf<PreQueueSnapshotEntity>()

        if(status=="QUEUE_CONFIRMED" && eventTime!=null){
            // TRUE FORWARD SIMULATION:
            // Start at 09:00 and move forward. At each moment the model sees only
            // BestLimits records up to that moment. eventTime is NOT used to choose
            // a "good-looking" historical snapshot; it is used only afterwards to
            // measure how early an already-issued alert was.
            var firstAlert:Pair<Book,Metrics>?=null
            var lastEvaluatedTime=0

            for(now in books){
                if(now.time>=eventTime) break
                // Roughly one evaluation per minute prevents repeated ticks from
                // dominating the backtest while preserving chronology.
                if(lastEvaluatedTime!=0 && secondsBetween(lastEvaluatedTime,now.time)<55) continue

                val base=books.lastOrNull{
                    it.time<=minusMinutes(now.time,5)
                } ?: books.first()

                val metrics=scoreSnapshot(now,base,yesterday)
                lastEvaluatedTime=now.time

                if(firstAlert==null && metrics.score>=70.0){
                    firstAlert=now to metrics
                }
            }

            // key=0 is the exact alert actually emitted by the chronological replay.
            firstAlert?.let{(alertBook,metrics)->
                out += PreQueueSnapshotEntity(
                    insCode=insCode,
                    date=date,
                    minutesBefore=0,
                    snapshotTime=alertBook.time,
                    score=metrics.score,
                    bidImbalance=metrics.imbalance,
                    bidGrowth=metrics.bidGrowth,
                    askDrop=metrics.askDrop,
                    pricePressure=metrics.pricePressure,
                    label=1,
                    detected=true
                )
            }

            // Standard lead-time checkpoints are evaluation-only. "detected" means
            // the forward replay had ALREADY emitted an alert by that checkpoint.
            val checkpoints=listOf(30,20,15,10,5)
            for(mins in checkpoints){
                val target=minusMinutes(eventTime,mins)
                val now=books.lastOrNull{it.time<=target} ?: continue
                val base=books.lastOrNull{
                    it.time<=minusMinutes(now.time,5)
                } ?: books.first()
                val metrics=scoreSnapshot(now,base,yesterday)
                val alertAlreadyIssued=
                    firstAlert?.first?.time?.let{it<=target} ?: false

                out += PreQueueSnapshotEntity(
                    insCode=insCode,
                    date=date,
                    minutesBefore=mins,
                    snapshotTime=now.time,
                    score=metrics.score,
                    bidImbalance=metrics.imbalance,
                    bidGrowth=metrics.bidGrowth,
                    askDrop=metrics.askDrop,
                    pricePressure=metrics.pricePressure,
                    label=1,
                    detected=alertAlreadyIssued
                )
            }
        }else if(status=="NOT_QUEUE" || status=="FRAGILE_QUEUE"){
            // Negative samples are evaluated at fixed intraday moments.
            val negatives=listOf(
                -100 to 100000,
                -200 to 110000,
                -300 to 120000
            )
            for((key,target) in negatives){
                val now=books.lastOrNull{it.time<=target} ?: continue
                val base=books.lastOrNull{it.time<=minusMinutes(now.time,5)} ?: books.first()
                val metrics=scoreSnapshot(now,base,yesterday)
                out += PreQueueSnapshotEntity(
                    insCode=insCode,
                    date=date,
                    minutesBefore=key,
                    snapshotTime=now.time,
                    score=metrics.score,
                    bidImbalance=metrics.imbalance,
                    bidGrowth=metrics.bidGrowth,
                    askDrop=metrics.askDrop,
                    pricePressure=metrics.pricePressure,
                    label=0,
                    detected=metrics.score>=70.0
                )
            }
        }

        if(out.isNotEmpty()){
            dao.upsertPreQueueSnapshots(out)
        }else{
            markUnavailable(insCode,date)
        }
    }

    private suspend fun markUnavailable(insCode:String,date:Int){
        dao.upsertPreQueueSnapshots(
            listOf(
                PreQueueSnapshotEntity(
                    insCode=insCode,date=date,minutesBefore=999,
                    snapshotTime=0,score=0.0,bidImbalance=0.0,
                    bidGrowth=0.0,askDrop=0.0,pricePressure=0.0,
                    label=-1,detected=false
                )
            )
        )
    }

    private data class Metrics(
        val score:Double,
        val imbalance:Double,
        val bidGrowth:Double,
        val askDrop:Double,
        val pricePressure:Double
    )

    private fun scoreSnapshot(now:Book,base:Book,yesterday:Double):Metrics{
        val nowBidPrice=now.bidPrice
        val nowBidVol=now.bidVol
        val nowAskVol=now.askVol
        val baseBidVol=base.bidVol
        val baseAskVol=base.askVol
        val imbalance=
            if(nowBidVol+nowAskVol>0) nowBidVol/(nowBidVol+nowAskVol) else 0.0
        val bidGrowth=
            if(baseBidVol>0) ((nowBidVol/baseBidVol)-1.0).coerceIn(-1.0,4.0)
            else if(nowBidVol>0) 1.0 else 0.0
        val askDrop=
            if(baseAskVol>0) (1.0-nowAskVol/baseAskVol).coerceIn(-2.0,1.0)
            else if(nowAskVol<=0) 1.0 else 0.0
        // Causal price pressure: only compare the current bid with yesterday's
        // close, which was already known before the session started.
        val intradayRise=((nowBidPrice/yesterday)-1.0).coerceIn(-0.20,0.20)
        val pricePressure=intradayRise
        val riseScore=((intradayRise-0.005)/0.045).coerceIn(0.0,1.0)
        val imbScore=((imbalance-0.50)/0.50).coerceIn(0.0,1.0)
        val growthScore=(bidGrowth/1.5).coerceIn(0.0,1.0)
        val supplyScore=((askDrop+0.15)/1.15).coerceIn(0.0,1.0)

        // Fixed coefficients: no parameter is learned from the future target day.
        val score=(
            riseScore*30.0 +
            imbScore*32.0 +
            growthScore*22.0 +
            supplyScore*16.0
        ).coerceIn(0.0,100.0)

        return Metrics(score,imbalance,bidGrowth,askDrop,pricePressure)
    }

    private fun secondsBetween(a:Int,b:Int):Int{
        fun sec(v:Int):Int{
            val h=v/10000
            val m=(v/100)%100
            val s=v%100
            return h*3600+m*60+s
        }
        return (sec(b)-sec(a)).coerceAtLeast(0)
    }

    private fun minusMinutes(time:Int,mins:Int):Int{
        val h=time/10000
        val m=(time/100)%100
        val total=(h*60+m-mins).coerceAtLeast(0)
        return (total/60)*10000 + (total%60)*100
    }

    private fun rebuildMetrics(){
        val db=app.db.openHelper.readableDatabase
        val checkpoints=listOf(30,20,15,10,5)
        val edit=prefs.edit()

        for(mins in checkpoints){
            val c=db.query("""
                SELECT COUNT(*),
                       SUM(CASE WHEN detected=1 THEN 1 ELSE 0 END)
                FROM prequeue_snapshots
                WHERE label=1 AND minutesBefore=$mins
            """.trimIndent())
            c.use{
                if(it.moveToFirst()){
                    val total=it.getInt(0)
                    val hit=if(it.isNull(1)) 0 else it.getInt(1)
                    edit.putInt("total_$mins",total)
                    edit.putInt("hit_$mins",hit)
                    edit.putFloat(
                        "rate_$mins",
                        if(total>0) hit.toFloat()/total else 0f
                    )
                }
            }
        }

        val n=db.query("""
            SELECT COUNT(*),
                   SUM(CASE WHEN detected=1 THEN 1 ELSE 0 END)
            FROM prequeue_snapshots WHERE label=0
        """.trimIndent())
        n.use{
            if(it.moveToFirst()){
                val total=it.getInt(0)
                val fp=if(it.isNull(1))0 else it.getInt(1)
                edit.putInt("negative_total",total)
                edit.putInt("false_positive",fp)
                edit.putFloat(
                    "false_positive_rate",
                    if(total>0)fp.toFloat()/total else 0f
                )
            }
        }
        val precisionCursor=db.query("""
            SELECT
              SUM(CASE WHEN label=1 AND detected=1 THEN 1 ELSE 0 END),
              SUM(CASE WHEN label=0 AND detected=1 THEN 1 ELSE 0 END)
            FROM prequeue_snapshots
            WHERE label IN (0,1)
        """.trimIndent())
        precisionCursor.use{
            if(it.moveToFirst()){
                val tp=if(it.isNull(0))0 else it.getInt(0)
                val fp=if(it.isNull(1))0 else it.getInt(1)
                edit.putInt("true_positive",tp)
                edit.putFloat(
                    "precision",
                    if(tp+fp>0) tp.toFloat()/(tp+fp).toFloat() else 0f
                )
            }
        }

        edit.putInt("snapshot_count",runCatching{daoCountSync()}.getOrDefault(0))
        edit.putLong("updated_at",System.currentTimeMillis())
        edit.apply()
    }

    private fun daoCountSync():Int{
        val c=app.db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM prequeue_snapshots"
        )
        return c.use{if(it.moveToFirst())it.getInt(0) else 0}
    }

    companion object{
        const val CHAIN="prequeue_walkforward_chain"
    }
}
