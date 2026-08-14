package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SymbolCatalogWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val api=TsetmcClient()
    private val prefs get()=applicationContext.getSharedPreferences("catalog",Context.MODE_PRIVATE)

    override suspend fun doWork():Result{
        val finalizeOnly=inputData.getBoolean("finalizeOnly",false)
        return try{
            prefs.edit()
                .putBoolean("running",true)
                .putString(
                    "status",
                    if(finalizeOnly) "در حال ساخت Universe نهایی"
                    else "در حال دریافت و تکمیل فهرست نمادها"
                )
                .apply()

            val arr=if(finalizeOnly)
                org.json.JSONArray()
            else
                api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")

            if(!finalizeOnly && arr.length()==0){
                prefs.edit()
                    .putBoolean("running",false)
                    .putString("status","پاسخ MarketWatch خالی بود")
                    .apply()
                return Result.success()
            }

            if(!finalizeOnly){
            val existing=dao.allSymbols().associateBy{it.insCode}
            val fresh=ArrayList<SymbolEntity>(arr.length())

            // مرحله ۱: همه نمادهای خام قابل شناسایی را ذخیره کن؛ هنوز Universe را فیلتر نکن.
            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val ins=firstString(o,"insCode","instrumentId","instrumentCode")?:continue
                val old=existing[ins]

                val rawSymbol=firstString(
                    o,"lVal18AFC","symbol","instrumentName","lVal18"
                )
                val rawName=firstString(
                    o,"lVal30","name","companyName","companyNamePersian","companyNameFa"
                )

                val symbol=cleanSymbol(rawSymbol,ins)?.takeIf{it.isNotBlank()} ?: old?.symbol
                val name=rawName?.trim()?.takeIf{it.isNotBlank()} ?: old?.name
                val flow=firstInt(o,"flow","market","marketCode") ?: old?.flow
                val board=firstString(
                    o,"cgrValCotTitle","boardTitle","marketTitle","flowTitle"
                ) ?: old?.boardTitle

                val derivedSegment=MarketPrefs.classify(flow,board)
                val segment=when{
                    derivedSegment!=MarketPrefs.OTHER -> derivedSegment
                    old!=null && old.segment!=MarketPrefs.OTHER -> old.segment
                    else -> MarketPrefs.OTHER
                }

                val type=MarketPrefs.classifyType(symbol,name,flow,board)

                fresh += SymbolEntity(
                    insCode=ins,
                    symbol=symbol,
                    name=name,
                    flow=flow,
                    segment=segment,
                    boardTitle=board,
                    instrumentType=type
                )
            }

            if(fresh.isNotEmpty()) dao.upsertSymbols(fresh)
            }
            dao.repairLiveScoreNames()

            // مرحله ۲: روی دیتابیس ذخیره‌شده طبقه‌بندی کن.
            val all=dao.allSymbols()
            var bourse=0
            var farabourse=0
            var base=0
            var leveraged=0
            var unknownStockLike=0
            var excluded=0

            for(s in all){
                val type=MarketPrefs.classifyType(
                    s.symbol,s.name,s.flow,s.boardTitle
                )
                val derived=MarketPrefs.classify(s.flow,s.boardTitle)
                val segment=if(derived==MarketPrefs.OTHER) s.segment else derived

                val isLev=type==MarketPrefs.TYPE_FUND &&
                    MarketPrefs.isLeveragedFund(s.symbol,s.name)

                val stockLike=
                    type==MarketPrefs.TYPE_STOCK ||
                    type==MarketPrefs.TYPE_BASE ||
                    isLev

                if(!stockLike){
                    excluded++
                    continue
                }

                if(isLev){
                    leveraged++
                }else when(segment){
                    MarketPrefs.BOURSE -> bourse++
                    MarketPrefs.FARABOURSE -> farabourse++
                    MarketPrefs.BASE_YELLOW,
                    MarketPrefs.BASE_ORANGE,
                    MarketPrefs.BASE_RED -> base++
                    else -> unknownStockLike++
                }
            }

            // بازار نامشخص فقط برای عیب‌یابی است و تا تکمیل متادیتا وارد Universe نمی‌شود.
            val eligible=bourse+farabourse+base+leveraged

            prefs.edit()
                .putBoolean("running",false)
                .putLong("last_refresh",System.currentTimeMillis())
                .putInt("raw_count",all.size)
                .putInt("eligible_count",eligible)
                .putInt("bourse_count",bourse)
                .putInt("farabourse_count",farabourse)
                .putInt("base_count",base)
                .putInt("leveraged_count",leveraged)
                .putInt("unknown_count",unknownStockLike)
                .putInt("excluded_count",excluded)
                .putString(
                    "status",
                    if(finalizeOnly && unknownStockLike>0)
                        "به‌روزرسانی تمام شد: $eligible معتبر؛ $unknownStockLike نماد هنوز متادیتای کافی ندارند"
                    else if(finalizeOnly)
                        "به‌روزرسانی نمادها کامل شد: $eligible نماد معتبر"
                    else if(unknownStockLike>0)
                        "در حال تکمیل خودکار نام/بازار؛ $eligible نماد فعلاً معتبر"
                    else
                        "فهرست اولیه آماده شد: $eligible نماد معتبر"
                )
                .apply()

            if(!finalizeOnly && (unknownStockLike>0 || dao.symbolsNeedingMetadata(1).isNotEmpty())){
                prefs.edit()
                    .putString(
                        "status",
                        "فهرست خام آماده شد؛ تکمیل نام و بازار نمادها به‌صورت خودکار ادامه دارد"
                    )
                    .apply()

                val enrich=androidx.work.OneTimeWorkRequestBuilder<MetadataWorker>()
                    .setConstraints(HistoricalWorker.networkConstraint())
                    .setInputData(androidx.work.workDataOf("batch" to 60))
                    .build()
                androidx.work.WorkManager.getInstance(applicationContext)
                    .enqueueUniqueWork(
                        MetadataWorker.CHAIN,
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        enrich
                    )
            }

            Result.success()
        }catch(e:Exception){
            prefs.edit()
                .putBoolean("running",false)
                .putString("status","خطای فهرست نمادها: ${e.message ?: "نامشخص"}")
                .apply()
            Result.success()
        }
    }

    companion object{ const val CHAIN="symbol_catalog_refresh" }
}
