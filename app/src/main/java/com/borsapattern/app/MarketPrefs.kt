package com.borsapattern.app

import android.content.Context

object MarketPrefs {
    // Market/board segments (used especially for Base Market details)
    const val BOURSE="BOURSE"
    const val FARABOURSE="FARABOURSE"
    const val BASE_YELLOW="BASE_YELLOW"
    const val BASE_ORANGE="BASE_ORANGE"
    const val BASE_RED="BASE_RED"
    const val OTHER="OTHER"

    val allSegments=setOf(BOURSE,FARABOURSE,BASE_YELLOW,BASE_ORANGE,BASE_RED)
    val baseSegments=setOf(BASE_YELLOW,BASE_ORANGE,BASE_RED)

    // Instrument categories, matching the requested TSETMC-style layout
    const val TYPE_STOCK="TYPE_STOCK"
    const val TYPE_BASE="TYPE_BASE"
    const val TYPE_HOUSING="TYPE_HOUSING"
    const val TYPE_RIGHT="TYPE_RIGHT"
    const val TYPE_BOND="TYPE_BOND"
    const val TYPE_OPTION="TYPE_OPTION"
    const val TYPE_FUTURE="TYPE_FUTURE"
    const val TYPE_FUND="TYPE_FUND"
    const val TYPE_COMMODITY="TYPE_COMMODITY"
    const val TYPE_TAL="TYPE_TAL"
    const val TYPE_ENERGY="TYPE_ENERGY"

    val allTypes=linkedSetOf(
        TYPE_STOCK,
        TYPE_BASE,
        TYPE_FUND
    )

    fun selected(ctx:Context):Set<String> = selectedSegments(ctx)

    fun selectedSegments(ctx:Context):Set<String>{
        val p=ctx.getSharedPreferences("market_filters",Context.MODE_PRIVATE)
        val saved=p.getStringSet("segments",null)?.toSet()
        val clean=saved?.intersect(allSegments) ?: allSegments
        return if(clean.isEmpty()) allSegments else clean
    }

    fun selectedTypes(ctx:Context):Set<String>{
        val p=ctx.getSharedPreferences("market_filters",Context.MODE_PRIVATE)
        val saved=p.getStringSet("instrument_types",null)?.toSet()
        val clean=saved?.intersect(allTypes) ?: allTypes
        return if(clean.isEmpty()) allTypes else clean
    }

    fun save(ctx:Context,segments:Set<String>){
        ctx.getSharedPreferences("market_filters",Context.MODE_PRIVATE)
            .edit().putStringSet("segments",segments).apply()
    }

    fun saveFilters(ctx:Context,types:Set<String>,segments:Set<String>){
        val cleanTypes=types.intersect(allTypes)
        val cleanSegments=segments.intersect(allSegments)
        ctx.getSharedPreferences("market_filters",Context.MODE_PRIVATE)
            .edit()
            .putStringSet("instrument_types",if(cleanTypes.isEmpty()) allTypes else cleanTypes)
            .putStringSet("segments",if(cleanSegments.isEmpty()) allSegments else cleanSegments)
            .apply()
    }

    fun label(s:String)=when(s){
        BOURSE -> "بورس"
        FARABOURSE -> "فرابورس"
        BASE_YELLOW -> "پایه زرد"
        BASE_ORANGE -> "پایه نارنجی"
        BASE_RED -> "پایه قرمز"
        else -> "سایر"
    }

    fun typeLabel(s:String)=when(s){
        TYPE_STOCK -> "سهام"
        TYPE_BASE -> "فرابورس - بازار پایه"
        TYPE_HOUSING -> "تسهیلات مسکن"
        TYPE_RIGHT -> "حق تقدم"
        TYPE_BOND -> "اوراق بدهی"
        TYPE_OPTION -> "اختیار معامله"
        TYPE_FUTURE -> "آتی"
        TYPE_FUND -> "صندوق اهرمی"
        TYPE_COMMODITY -> "بورس کالا"
        TYPE_TAL -> "معاملات پایانی TAL"
        TYPE_ENERGY -> "انرژی"
        else -> "سایر"
    }


    fun isFixedIncomeInstrument(
        symbol:String?,
        name:String?,
        board:String?
    ):Boolean{
        val s=(symbol?:"").trim().replace(" ","").replace("\u200c","")
        val n=(name?:"").trim()
        val b=(board?:"").trim()
        val text="$s $n $b"

        return text.contains("درآمد ثابت") ||
            text.contains("درآمدثابت") ||
            text.contains("با درآمد ثابت") ||
            text.contains("اوراق بهادار با درآمد") ||
            text.contains("صندوق سرمایه گذاری در اوراق") ||
            text.contains("صندوق سرمایه‌گذاری در اوراق") ||
            text.contains("اسناد خزانه") ||
            text.contains("صکوک") ||
            text.contains("مرابحه") ||
            text.contains("اجاره") ||
            text.contains("مشارکت") ||
            text.contains("منفعت")
    }

    fun isOptionInstrument(
        symbol:String?,
        name:String?,
        board:String?
    ):Boolean{
        val s=(symbol?:"").trim().replace(" ","").replace("\u200c","")
        val n=(name?:"").trim()
        val b=(board?:"").trim()
        val text="$s $n $b"

        return text.contains("اختیار") ||
            text.contains("option",ignoreCase=true) ||
            s.startsWith("ض") ||
            s.startsWith("ط")
    }

    fun isLeveragedFund(symbol:String?,name:String?):Boolean{
        val s=(symbol?:"").trim()
            .replace(" ","")
            .replace("\u200c","")
        val n=(name?:"").trim()
        val text="$s $n"

        // Fallback aliases are used when TSETMC metadata is incomplete.
        // Name-based detection remains the primary rule.
        val knownAliases=setOf(
            "اهرم","توان","شتاب","موج","جهش","بیدار","دوایکس"
        )

        return text.contains("اهرمی") ||
            text.contains("اهرم") ||
            text.contains("دو برابر") ||
            text.contains("2x",ignoreCase=true) ||
            knownAliases.contains(s)
    }

    fun exclusionReason(
        symbol:String?,
        name:String?,
        flow:Int?,
        board:String?
    ):String?{
        if(isOptionInstrument(symbol,name,board)) return "OPTION"
        if(isFixedIncomeInstrument(symbol,name,board)) return "FIXED_INCOME"

        val type=classifyType(symbol,name,flow,board)
        val leveraged=type==TYPE_FUND && isLeveragedFund(symbol,name)
        if(leveraged) return null

        return when(type){
            TYPE_HOUSING -> "HOUSING"
            TYPE_RIGHT -> "RIGHT"
            TYPE_BOND -> "BOND"
            TYPE_OPTION -> "OPTION"
            TYPE_FUTURE -> "FUTURE"
            TYPE_COMMODITY -> "COMMODITY"
            TYPE_TAL -> "TAL"
            TYPE_ENERGY -> "ENERGY"
            TYPE_FUND -> "OTHER_FUND"
            else -> null
        }
    }

    fun isDefinitelyExcluded(
        symbol:String?,
        name:String?,
        flow:Int?,
        board:String?
    ):Boolean{
        return exclusionReason(symbol,name,flow,board)!=null
    }

    fun isRawTargetCandidate(
        symbol:String?,
        name:String?,
        flow:Int?,
        board:String?
    ):Boolean{
        // Stage-1 must be conservative: only instruments that are DEFINITELY
        // irrelevant are discarded here. Ambiguous rows are kept only for
        // lightweight metadata resolution and never enter heavy analysis until
        // their market/type is confirmed.
        return !isDefinitelyExcluded(symbol,name,flow,board)
    }

    fun isSignalUniverse(
        segment:String,
        instrumentType:String,
        symbol:String?,
        name:String?
    ):Boolean{
        val allowedMarket=
            segment==BOURSE || segment==FARABOURSE ||
            segment==BASE_YELLOW || segment==BASE_ORANGE || segment==BASE_RED

        if(instrumentType==TYPE_OPTION) return false

        val leveraged=
            instrumentType==TYPE_FUND && isLeveragedFund(symbol,name)

        // Leveraged ETFs are allowed even if TSETMC board metadata is temporarily missing;
        // their fund identity is independently recognized.
        if(leveraged) return true
        if(!allowedMarket) return false

        return instrumentType==TYPE_STOCK ||
            instrumentType==TYPE_BASE
    }

    fun classify(flow:Int?, board:String?):String{
        val b=(board?:"").trim()
        return when{
            b.contains("زرد") -> BASE_YELLOW
            b.contains("نارنجی") -> BASE_ORANGE
            b.contains("قرمز") -> BASE_RED
            flow==1 -> BOURSE
            flow==2 -> FARABOURSE
            flow==4 -> OTHER
            else -> OTHER
        }
    }

    fun classifyType(
        symbol:String?,
        name:String?,
        flow:Int?,
        board:String?
    ):String{
        val s=(symbol?:"").trim()
        val n=(name?:"").trim()
        val b=(board?:"").trim()
        val text="$s $n $b"

        return when{
            isLeveragedFund(s,n) -> TYPE_FUND

            isOptionInstrument(s,n,b) -> TYPE_OPTION
            isFixedIncomeInstrument(s,n,b) -> TYPE_BOND

            b.contains("زرد") || b.contains("نارنجی") || b.contains("قرمز") ||
                text.contains("بازار پایه") -> TYPE_BASE

            text.contains("تسهیلات مسکن") ||
                s.startsWith("تسه") || s.startsWith("تملی") -> TYPE_HOUSING

            text.contains("حق تقدم") ||
                n.contains("حق تقدم") ||
                (s.endsWith("ح") && s.length>2) -> TYPE_RIGHT

            text.contains("آتی") || text.contains("قرارداد آتی") -> TYPE_FUTURE

            text.contains("صندوق") ||
                text.contains("ETF",ignoreCase=true) ||
                text.contains("سرمایه گذاری قابل معامله") -> TYPE_FUND

            text.contains("بورس کالا") ||
                text.contains("گواهی سپرده") ||
                text.contains("کالایی") -> TYPE_COMMODITY

            text.contains("TAL",ignoreCase=true) ||
                text.contains("معاملات پایانی") -> TYPE_TAL

            text.contains("انرژی") ||
                text.contains("بورس انرژی") -> TYPE_ENERGY

            text.contains("اوراق") ||
                text.contains("صکوک") ||
                text.contains("مرابحه") ||
                text.contains("اجاره") ||
                text.contains("مشارکت") ||
                text.contains("منفعت") ||
                text.contains("اسناد خزانه") -> TYPE_BOND

            else -> TYPE_STOCK
        }
    }
}
