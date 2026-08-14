package com.borsapattern.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.min

class HistoricalWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val api=TsetmcClient()
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val prefs get()=applicationContext.getSharedPreferences("sync",Context.MODE_PRIVATE)

    override suspend fun doWork():Result{
        val requestedOffset=inputData.getInt("offset",0)
        val batchSize=inputData.getInt("batchSize",12).coerceIn(8,24)
        val confirmed=inputData.getBoolean("userConfirmed",false)
        val mode=inputData.getString("mode") ?: "DEEP"
        val resumeOffset=prefs.getInt("resume_offset",0)
        val resumeMode=prefs.getString("resume_mode","")
        val offset=if(
            requestedOffset==0 &&
            prefs.getBoolean("resume_pending",false) &&
            resumeMode==mode
        ) resumeOffset else requestedOffset

        if(!confirmed){
            prefs.edit()
                .putBoolean("sync_running",false)
                .putString("sync_status","منتظر تایید شما برای شروع استخراج")
                .apply()
            return Result.success()
        }

        return try{
            // فقط اولین تکه، فهرست نمادها را تازه می‌کند.
            if(offset==0){
                prefs.edit()
                    .putString("sync_status","در حال به‌روزرسانی فهرست نمادها")
                    .putBoolean("sync_running",true)
                    .apply()

                val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
                val existing=dao.allSymbols().associateBy{it.insCode}
                val fresh=mutableListOf<SymbolEntity>()

                for(i in 0 until arr.length()){
                    val o=arr.optJSONObject(i)?:continue
                    val ins=firstString(o,"insCode","instrumentId")?:continue
                    val rawSymbol=firstString(o,"lVal18AFC","symbol","instrumentName")
                    val rawName=firstString(o,"lVal30","name","companyName","companyNamePersian")
                    val old=existing[ins]
                    val clean=cleanSymbol(rawSymbol,ins)
                    val resolvedSymbol=clean ?: old?.symbol
                    val resolvedName=rawName?.trim()?.takeIf{it.isNotBlank()} ?: old?.name
                    val flow=firstInt(o,"flow") ?: old?.flow
                    val board=firstString(o,"cgrValCotTitle","boardTitle") ?: old?.boardTitle
                    fresh += SymbolEntity(
                        insCode=ins,
                        symbol=resolvedSymbol,
                        name=resolvedName,
                        flow=flow,
                        segment=MarketPrefs.classify(flow,board).let{
                            if(it==MarketPrefs.OTHER) old?.segment ?: it else it
                        },
                        boardTitle=board,
                        instrumentType=MarketPrefs.classifyType(
                            resolvedSymbol,resolvedName,flow,board
                        )
                    )
                }
                if(fresh.isNotEmpty()) dao.upsertSymbols(fresh)
            }

            val wantedSegments=MarketPrefs.selectedSegments(applicationContext)
            val wantedTypes=MarketPrefs.selectedTypes(applicationContext)
            val extractPrefs=applicationContext.getSharedPreferences("extract",Context.MODE_PRIVATE)
            val years=when(mode){
                "QUICK" -> 1
                "DEEP" -> 5
                else -> extractPrefs.getInt("years",5).coerceIn(1,5)
            }
            val allStored=dao.allSymbols()
            val symbols=allStored.filter{
                val effectiveType=MarketPrefs.classifyType(
                    it.symbol,it.name,it.flow,it.boardTitle
                )
                val effectiveSegment=MarketPrefs.classify(it.flow,it.boardTitle).let{derived->
                    when{
                        derived!=MarketPrefs.OTHER -> derived
                        it.segment!=MarketPrefs.OTHER -> it.segment
                        else -> MarketPrefs.OTHER
                    }
                }

                val supportedType =
                    effectiveType==MarketPrefs.TYPE_STOCK ||
                    effectiveType==MarketPrefs.TYPE_BASE ||
                    (
                        effectiveType==MarketPrefs.TYPE_FUND &&
                        MarketPrefs.isLeveragedFund(it.symbol,it.name)
                    )

                val isLeveraged=
                    effectiveType==MarketPrefs.TYPE_FUND &&
                    MarketPrefs.isLeveragedFund(it.symbol,it.name)

                val segmentAllowed =
                    if(isLeveraged) true
                    else effectiveSegment!=MarketPrefs.OTHER &&
                        wantedSegments.contains(effectiveSegment)

                segmentAllowed &&
                wantedTypes.contains(effectiveType) &&
                supportedType &&
                !it.symbol.isNullOrBlank()
            }.sortedBy{it.insCode}

            if(symbols.isEmpty()){
                prefs.edit()
                    .putInt("sync_total",0)
                    .putInt("sync_done",0)
                    .putString(
                        "sync_status",
                        "Universe معتبر صفر شد؛ ${allStored.size} رکورد نماد در دیتابیس هست ولی هیچ‌کدام با فیلتر فعلی منطبق نشد"
                    )
                    .putBoolean("sync_running",false)
                    .apply()
                return Result.success()
            }

            val total=symbols.size
            val start=offset.coerceIn(0,total)
            val end=min(start+batchSize,total)
            val cutoff=LocalDate.now().minusDays((years*366L)+10L)
            val cutoffInt=cutoff.format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
            val freshCutoff=LocalDate.now().minusDays(12)
                .format(DateTimeFormatter.BASIC_ISO_DATE).toInt()

            prefs.edit()
                .putInt("sync_total",total)
                .putInt("sync_done",start)
                .putInt("resume_offset",start)
                .putString("resume_mode",mode)
                .putBoolean("resume_pending",true)
                .putString(
                    "sync_status",
                    "${if(mode=="QUICK") "استخراج سریع" else "تکمیل عمیق"}: $start از $total نماد"
                )
                .putBoolean("sync_running",true)
                .apply()

            val parallelism=if(mode=="QUICK") 6 else 3
            var chunkStart=start

            while(chunkStart<end){
                val chunkEnd=min(chunkStart+parallelism,end)
                try{
                    coroutineScope{
                        (chunkStart until chunkEnd).map{idx->
                            async(Dispatchers.IO){
                                downloadHistoryFor(
                                    symbols[idx],cutoffInt,freshCutoff
                                )
                            }
                        }.awaitAll()
                    }
                }catch(e:CancellationException){
                    prefs.edit()
                        .putInt("resume_offset",chunkStart)
                        .putBoolean("resume_pending",true)
                        .putBoolean("sync_running",false)
                        .putString(
                            "sync_status",
                            "استخراج موقتاً متوقف شد؛ ادامه از ${chunkStart+1} ذخیره شد"
                        )
                        .apply()
                    throw e
                }

                prefs.edit()
                    .putInt("sync_done",chunkEnd)
                    .putInt("resume_offset",chunkEnd)
                    .putBoolean("resume_pending",true)
                    .putString(
                        "sync_status",
                        "${if(mode=="QUICK") "استخراج سریع موازی" else "تکمیل عمیق"}: $chunkEnd از $total نماد"
                    )
                    .apply()

                setProgress(workDataOf(
                    "stage" to if(mode=="QUICK") "استخراج سریع موازی" else "تکمیل عمیق",
                    "done" to chunkEnd,
                    "total" to total
                ))
                chunkStart=chunkEnd
            }

            if(end<total){
                // ادامه کار به یک Worker کوتاه دیگر سپرده می‌شود.
                // این زنجیره بعد از خروج از اپ هم در WorkManager باقی می‌ماند.
                val next=OneTimeWorkRequestBuilder<HistoricalWorker>()
                    .setConstraints(networkConstraint())
                    .setInputData(workDataOf(
                        "offset" to end,
                        "batchSize" to batchSize,
                        "userConfirmed" to true,
                        "mode" to mode
                    ))
                    .setInitialDelay(2,TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(applicationContext)
                    .enqueueUniqueWork(
                        HISTORY_CHAIN,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        next
                    )
                return Result.success()
            }

            PatternEngine.seedInitialEvents((applicationContext as BorsaApp).db)

            prefs.edit()
                .putLong("last_sync",System.currentTimeMillis())
                .putInt("sync_done",total)
                .putInt("sync_total",total)
                .putBoolean("sync_running",false)
                .putBoolean("resume_pending",false)
                .putInt("resume_offset",0)
                .putString(
                    "sync_status",
                    "${if(mode=="QUICK") "استخراج سریع" else "تکمیل عمیق"} کامل شد؛ تحلیل صف‌ها شروع شد"
                )
                .apply()

            applicationContext.getSharedPreferences(
                "analysis_pipeline",Context.MODE_PRIVATE
            ).edit()
                .putBoolean("enabled",true)
                .putString("stage","DAY1")
                .apply()

            val analysis=OneTimeWorkRequestBuilder<QueueAnalysisWorker>()
                .setConstraints(networkConstraint())
                .setInputData(workDataOf(
                    "batchSize" to 160,
                    "parallelism" to 4,
                    "resetErrors" to true
                ))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                QueueAnalysisWorker.ANALYSIS_CHAIN,
                ExistingWorkPolicy.REPLACE,
                analysis
            )

            Result.success()
        }catch(e:CancellationException){
            prefs.edit()
                .putBoolean("sync_running",false)
                .putBoolean("resume_pending",true)
                .putString(
                    "sync_status",
                    "استخراج توسط Android متوقف شد؛ نقطه ادامه ذخیره شده است"
                )
                .apply()
            throw e
        }catch(e:Exception){
            prefs.edit()
                .putString(
                    "sync_status",
                    "خطای دریافت داده: ${e.message ?: "نامشخص"} — ادامه از checkpoint انجام می‌شود"
                )
                .putBoolean("sync_running",false)
                .putBoolean("resume_pending",true)
                .apply()
            Result.success()
        }
    }

    private suspend fun downloadHistoryFor(
        s:SymbolEntity,
        cutoffInt:Int,
        freshCutoff:Int
    ){
        try{
            val latest=dao.latestDateFor(s.insCode)?:0
            val earliest=dao.earliestDateFor(s.insCode)
            val coverageEnough=earliest!=null && earliest<=cutoffInt
            val recentEnough=latest>=freshCutoff
            if(coverageEnough && recentEnough) return

            val d=api.jsonArrayFrom(api.dailyRaw(s.insCode),"closingPriceDaily")
            val rows=mutableListOf<DailyEntity>()
            for(j in 0 until d.length()){
                val o=d.optJSONObject(j)?:continue
                val date=firstInt(o,"dEven","date")?:continue
                if(date<cutoffInt) continue
                rows += DailyEntity(
                    insCode=s.insCode,
                    date=date,
                    high=firstDouble(o,"priceMax","pmax","pMax"),
                    last=firstDouble(o,"pDrCotVal","pl","lastPrice"),
                    yesterday=firstDouble(o,"priceYesterday","py","yesterdayPrice"),
                    volume=firstDouble(o,"qTotTran5J","volume"),
                    value=firstDouble(o,"qTotCap","value")
                )
            }
            if(rows.isNotEmpty()) dao.upsertDaily(rows)
        }catch(e:CancellationException){
            throw e
        }catch(_:Exception){
            // یک نماد خراب نباید کل batch را متوقف کند.
        }
    }

    companion object{
        const val HISTORY_CHAIN="history_sync_chain"

        fun start(
            context:Context,
            replace:Boolean=false,
            mode:String="DEEP"
        ){
            val prefs=context.getSharedPreferences("sync",Context.MODE_PRIVATE)
            val sameMode=prefs.getString("resume_mode","")==mode
            val resume=if(
                prefs.getBoolean("resume_pending",false) && sameMode
            ) prefs.getInt("resume_offset",0) else 0

            if(!sameMode){
                prefs.edit()
                    .putInt("resume_offset",0)
                    .putString("resume_mode",mode)
                    .putBoolean("resume_pending",false)
                    .apply()
            }

            val req=OneTimeWorkRequestBuilder<HistoricalWorker>()
                .setConstraints(networkConstraint())
                .setInputData(workDataOf(
                    "offset" to resume,
                    "batchSize" to if(mode=="QUICK") 24 else 12,
                    "userConfirmed" to true,
                    "mode" to mode
                ))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                HISTORY_CHAIN,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun networkConstraint():Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
    }
}
