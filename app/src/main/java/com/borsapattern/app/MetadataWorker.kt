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
        val quarantine=prefs.getStringSet("quarantine_codes",emptySet())?.toSet() ?: emptySet()
        val symbols=dao.symbolsNeedingMetadata(batch*4)
            .filterNot{quarantine.contains(it.insCode)}
            .take(batch)
        val live=dao.liveScoresNeedingName(batch)
            .filterNot{quarantine.contains(it.insCode)}
            .take(batch)

        if(symbols.isEmpty() && live.isEmpty()){
            dao.repairLiveScoreNames()
            val qCount=prefs.getStringSet("quarantine_codes",emptySet())?.size ?: 0
            prefs.edit()
                .putString(
                    "status",
                    if(qCount>0)
                        "به‌روزرسانی افزایشی کامل شد؛ $qCount مورد نامشخص در قرنطینه باقی ماند"
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

        if(unresolved.isNotEmpty() && round>=2){
            val merged=(quarantine + unresolved).take(2500).toSet()
            prefs.edit().putStringSet("quarantine_codes",merged).apply()
        }
        val updatedQuarantine=prefs.getStringSet("quarantine_codes",emptySet())?.toSet() ?: emptySet()
        val remaining=dao.symbolsNeedingMetadata(batch*4)
            .any{!updatedQuarantine.contains(it.insCode)}
        if(!remaining || round>=45){
            dao.repairLiveScoreNames()
            prefs.edit()
                .putString(
                    "status",
                    if(updatedQuarantine.isNotEmpty())
                        "تکمیل افزایشی تمام شد؛ ${updatedQuarantine.size} مورد نامشخص در قرنطینه متادیتا هستند"
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
            .putString("status","این مرحله ${fixed.get()} نماد جدید/ناقص تکمیل شد؛ موارد قبلی دوباره بررسی نمی‌شوند")
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
