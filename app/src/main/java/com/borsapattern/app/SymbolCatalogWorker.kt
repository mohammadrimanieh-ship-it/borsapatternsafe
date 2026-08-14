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
                    else "به‌روزرسانی افزایشی فهرست نمادها"
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

            var rawExcludedThisRefresh=0
            var sourceErrorCount=0
            var duplicateCount=0
            val seenRawCodes=linkedSetOf<String>()
            val exclusionAudit=linkedMapOf(
                "OPTION" to 0,"FIXED_INCOME" to 0,"HOUSING" to 0,"RIGHT" to 0,
                "BOND" to 0,"FUTURE" to 0,"COMMODITY" to 0,"TAL" to 0,
                "ENERGY" to 0,"OTHER_FUND" to 0
            )
            val auditEntries=linkedMapOf<String,MutableSet<String>>()
            if(!finalizeOnly){
            val existing=dao.allSymbols().associateBy{it.insCode}
            val fresh=ArrayList<SymbolEntity>(arr.length())

            // مرحله ۱: همه نمادهای خام قابل شناسایی را ذخیره کن؛ هنوز Universe را فیلتر نکن.
            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)
                if(o==null){
                    sourceErrorCount++
                    continue
                }
                val ins=firstString(o,"insCode","instrumentId","instrumentCode")
                if(ins.isNullOrBlank()){
                    sourceErrorCount++
                    continue
                }
                if(!seenRawCodes.add(ins)){
                    duplicateCount++
                    continue
                }
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
                val auditLine=listOf(
                    ins,
                    symbol?:"",
                    name?:"",
                    flow?.toString()?:"",
                    board?:"",
                    type
                ).joinToString("\t")

                val exclusionReason=MarketPrefs.exclusionReason(symbol,name,flow,board)
                if(exclusionReason!=null){
                    rawExcludedThisRefresh++
                    exclusionAudit[exclusionReason]=(exclusionAudit[exclusionReason] ?: 0)+1
                    auditEntries.getOrPut(exclusionReason){linkedSetOf()}.add(auditLine)
                    continue
                }

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

            prefs.edit()
                .putStringSet("raw_codes",seenRawCodes)
                .putInt("source_error_count",sourceErrorCount)
                .putInt("duplicate_count",duplicateCount)
                .apply()
            for(key in exclusionAudit.keys){
                prefs.edit().putStringSet(
                    "audit_$key",
                    auditEntries[key] ?: emptySet()
                ).apply()
            }

            // Incremental cleanup: old non-target rows from previous builds are removed
            // together with their derived analysis rows. Historical rows of valid stocks
            // are untouched.
            val purgeCodes=dao.allSymbols()
                .filter{
                    MarketPrefs.isDefinitelyExcluded(
                        it.symbol,it.name,it.flow,it.boardTitle
                    )
                }
                .map{it.insCode}
            for(chunk in purgeCodes.chunked(200)){
                if(chunk.isEmpty()) continue
                dao.deletePreQueueByCodes(chunk)
                dao.deleteEventsByCodes(chunk)
                dao.deleteDailyByCodes(chunk)
                dao.deleteLiveByCodes(chunk)
                dao.deleteSymbolsByCodes(chunk)
            }
            }
            dao.repairLiveScoreNames()

            // مرحله ۲: روی دیتابیس ذخیره‌شده طبقه‌بندی کن.
            val all=dao.allSymbols()
            val currentCodes=prefs.getStringSet("raw_codes",emptySet())?.toSet() ?: emptySet()
            val current=if(currentCodes.isEmpty()) all else all.filter{currentCodes.contains(it.insCode)}
            val categoryEntries=linkedMapOf<String,MutableSet<String>>()
            var bourse=0
            var farabourse=0
            var base=0
            var leveraged=0
            var unknownStockLike=0
            var excluded=0

            for(s in current){
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

                val line=listOf(
                    s.insCode,
                    s.symbol?:"",
                    s.name?:"",
                    s.flow?.toString()?:"",
                    s.boardTitle?:"",
                    type
                ).joinToString("\t")

                if(!stockLike){
                    excluded++
                    categoryEntries.getOrPut("OTHER"){linkedSetOf()}.add(line)
                    continue
                }

                if(isLev){
                    leveraged++
                    categoryEntries.getOrPut("LEVERAGED"){linkedSetOf()}.add(line)
                }else when(segment){
                    MarketPrefs.BOURSE -> {
                        bourse++
                        categoryEntries.getOrPut("BOURSE"){linkedSetOf()}.add(line)
                    }
                    MarketPrefs.FARABOURSE -> {
                        farabourse++
                        categoryEntries.getOrPut("FARABOURSE"){linkedSetOf()}.add(line)
                    }
                    MarketPrefs.BASE_YELLOW,
                    MarketPrefs.BASE_ORANGE,
                    MarketPrefs.BASE_RED -> {
                        base++
                        categoryEntries.getOrPut("BASE"){linkedSetOf()}.add(line)
                    }
                    else -> {
                        unknownStockLike++
                        categoryEntries.getOrPut("UNKNOWN"){linkedSetOf()}.add(line)
                    }
                }
            }

            for(key in listOf("BOURSE","FARABOURSE","BASE","LEVERAGED","UNKNOWN","OTHER")){
                prefs.edit().putStringSet(
                    "audit_$key",
                    categoryEntries[key] ?: emptySet()
                ).apply()
            }

            // بازار نامشخص فقط برای عیب‌یابی است و تا تکمیل متادیتا وارد Universe نمی‌شود.
            val eligible=bourse+farabourse+base+leveraged
            val rawCount=if(finalizeOnly) prefs.getInt("raw_count",current.size) else arr.length()
            val excludedTotal=if(finalizeOnly) prefs.getInt("excluded_count",excluded) else rawExcludedThisRefresh+excluded
            val sourceErrors=if(finalizeOnly) prefs.getInt("source_error_count",0) else sourceErrorCount
            val duplicates=if(finalizeOnly) prefs.getInt("duplicate_count",0) else duplicateCount
            val reconciled=eligible+unknownStockLike+excludedTotal+sourceErrors+duplicates
            val reconciliationDelta=rawCount-reconciled

            prefs.edit()
                .putBoolean("running",false)
                .putLong("last_refresh",System.currentTimeMillis())
                .putInt("raw_count",rawCount)
                .putInt("eligible_count",eligible)
                .putInt("bourse_count",bourse)
                .putInt("farabourse_count",farabourse)
                .putInt("base_count",base)
                .putInt("leveraged_count",leveraged)
                .putInt("unknown_count",unknownStockLike)
                .putInt("excluded_count",excludedTotal)
                .putInt("reconciled_count",reconciled)
                .putInt("reconciliation_delta",reconciliationDelta)
                .putInt("source_error_count",sourceErrors)
                .putInt("duplicate_count",duplicates)
                .putInt("excluded_option",if(finalizeOnly) prefs.getInt("excluded_option",0) else exclusionAudit["OPTION"] ?: 0)
                .putInt("excluded_fixed_income",if(finalizeOnly) prefs.getInt("excluded_fixed_income",0) else exclusionAudit["FIXED_INCOME"] ?: 0)
                .putInt("excluded_housing",if(finalizeOnly) prefs.getInt("excluded_housing",0) else exclusionAudit["HOUSING"] ?: 0)
                .putInt("excluded_right",if(finalizeOnly) prefs.getInt("excluded_right",0) else exclusionAudit["RIGHT"] ?: 0)
                .putInt("excluded_bond",if(finalizeOnly) prefs.getInt("excluded_bond",0) else exclusionAudit["BOND"] ?: 0)
                .putInt("excluded_future",if(finalizeOnly) prefs.getInt("excluded_future",0) else exclusionAudit["FUTURE"] ?: 0)
                .putInt("excluded_commodity",if(finalizeOnly) prefs.getInt("excluded_commodity",0) else exclusionAudit["COMMODITY"] ?: 0)
                .putInt("excluded_other_fund",if(finalizeOnly) prefs.getInt("excluded_other_fund",0) else exclusionAudit["OTHER_FUND"] ?: 0)
                .putString(
                    "status",
                    if(finalizeOnly && reconciliationDelta!=0)
                        "Universe حسابرسی شد؛ اختلاف شمارش $reconciliationDelta مورد است و باید بررسی شود"
                    else if(finalizeOnly && unknownStockLike>0)
                        "به‌روزرسانی تمام شد: $eligible معتبر؛ $unknownStockLike نماد هنوز متادیتای کافی ندارند"
                    else if(finalizeOnly)
                        "به‌روزرسانی نمادها کامل شد: $eligible نماد معتبر؛ Reconciliation صحیح"
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
