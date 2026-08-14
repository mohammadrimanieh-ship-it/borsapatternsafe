package com.borsapattern.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MetadataWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val api=TsetmcClient()
    private val prefs get()=applicationContext.getSharedPreferences("metadata",Context.MODE_PRIVATE)
    private val catalogPrefs get()=applicationContext.getSharedPreferences("catalog",Context.MODE_PRIVATE)

    override suspend fun doWork():Result{
        if(prefs.getInt("resolver_version",0)<3){
            prefs.edit()
                .remove("quarantine_codes")
                .putInt("resolver_version",3)
                .apply()
        }
        val batch=inputData.getInt("batch",80).coerceIn(20,120)
        val round=inputData.getInt("round",0)

        // Resolver v4: unresolved instruments are never permanently suppressed.
        // We rotate through the whole unresolved pool so the first difficult rows
        // cannot block later genuine stocks.
        val pool=dao.symbolsNeedingMetadata(5000)
        val symbols=if(pool.isEmpty()) emptyList() else{
            val start=((round*batch)%pool.size).coerceAtLeast(0)
            (pool.drop(start)+pool.take(start)).take(batch)
        }
        val livePool=dao.liveScoresNeedingName(1200)
        val live=if(livePool.isEmpty()) emptyList() else{
            val start=((round*batch)%livePool.size).coerceAtLeast(0)
            (livePool.drop(start)+livePool.take(start)).take(batch)
        }

        if(symbols.isEmpty() && live.isEmpty()){
            dao.repairLiveScoreNames()
            prefs.edit()
                .remove("quarantine_codes")
                .putString("status","نام و بازار همه موارد قابل‌حل کامل شد")
                .putBoolean("running",false)
                .apply()

            val catalog=OneTimeWorkRequestBuilder<SymbolCatalogWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(workDataOf("finalizeOnly" to true))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                SymbolCatalogWorker.CHAIN,
                ExistingWorkPolicy.REPLACE,
                catalog
            )
            return Result.success()
        }

        prefs.edit()
            .putBoolean("running",true)
            .putString("status","در حال تکمیل نام و بازار نمادها")
            .apply()
        catalogPrefs.edit()
            .putBoolean("running",true)
            .putString("status","در حال تکمیل خودکار نام و بازار نمادها")
            .apply()

        val codes=LinkedHashSet<String>()
        symbols.forEach{codes+=it.insCode}
        live.forEach{codes+=it.insCode}

        val fixed=AtomicInteger(0)
        val unresolved=java.util.Collections.synchronizedSet(mutableSetOf<String>())
        coroutineScope{
            for(chunk in codes.take(batch).chunked(6)){
                chunk.map{code->
                    async(Dispatchers.IO){
                        try{
                            val current=dao.symbolByCode(code)
                            val entity=SymbolResolver.ensure(
                                dao=dao,api=api,insCode=code,
                                rawSymbol=current?.symbol,rawName=current?.name,
                                flow=current?.flow,board=current?.boardTitle
                            )
                            val complete=
                                !entity.symbol.isNullOrBlank() &&
                                !entity.name.isNullOrBlank() &&
                                entity.flow!=null &&
                                entity.segment!=MarketPrefs.OTHER
                            if(complete) fixed.incrementAndGet()
                            else unresolved += code
                        }catch(_:Exception){
                            // Network/transient errors are NOT quarantined; they can retry
                            // in a later incremental pass.
                        }
                    }
                }.awaitAll()
                yield()
            }
        }

        dao.repairLiveScoreNames()

        val unresolvedNow=dao.symbolsNeedingMetadata(5000)
        val remaining=unresolvedNow.isNotEmpty()
        val previousRemaining=prefs.getInt("previous_remaining",-1)
        val stagnant=if(previousRemaining==unresolvedNow.size)
            prefs.getInt("stagnant_rounds",0)+1 else 0
        prefs.edit()
            .putInt("previous_remaining",unresolvedNow.size)
            .putInt("stagnant_rounds",stagnant)
            .apply()

        // At least two full passes are allowed. We stop only after repeated
        // no-progress rounds, then keep the unresolved list as a diagnostic set.
        if(!remaining || round>=60 || stagnant>=12){
            dao.repairLiveScoreNames()
            val unresolvedCodes=unresolvedNow.map{it.insCode}.take(3000).toSet()
            prefs.edit()
                .putStringSet("quarantine_codes",unresolvedCodes)
                .putString(
                    "status",
                    if(unresolvedCodes.isNotEmpty())
                        "Resolver کامل شد؛ ${unresolvedCodes.size} مورد هنوز پاسخ Metadata کافی ندارند و حذف نشده‌اند"
                    else "نام و بازار نمادها کامل شد"
                )
                .putBoolean("running",false)
                .apply()

            val catalog=OneTimeWorkRequestBuilder<SymbolCatalogWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(workDataOf("finalizeOnly" to true))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                SymbolCatalogWorker.CHAIN,
                ExistingWorkPolicy.REPLACE,
                catalog
            )
            return Result.success()
        }

        prefs.edit()
            .putString("status","مرحله ${round+1}: ${fixed.get()} مورد تکمیل شد؛ Resolver در کل فهرست نامشخص‌ها گردش می‌کند")
            .putBoolean("running",true)
            .apply()
        catalogPrefs.edit()
            .putString("status","تکمیل خودکار نمادها — مرحله ${round+1}، این مرحله ${fixed.get()} مورد")
            .putBoolean("running",true)
            .apply()

        val next=OneTimeWorkRequestBuilder<MetadataWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .setInputData(workDataOf("batch" to batch,"round" to round+1))
            .setInitialDelay(1,TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            CHAIN,ExistingWorkPolicy.APPEND_OR_REPLACE,next
        )
        return Result.success()
    }

    companion object{ const val CHAIN="metadata_name_repair" }
}
