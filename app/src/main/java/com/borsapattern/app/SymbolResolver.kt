package com.borsapattern.app

object SymbolResolver {
    suspend fun ensure(
        dao:BorsaDao,
        api:TsetmcClient,
        insCode:String,
        rawSymbol:String?,
        rawName:String?,
        flow:Int?,
        board:String?
    ):SymbolEntity {
        val existing=dao.symbolByCode(insCode)
        var symbol=cleanSymbol(rawSymbol,insCode) ?: existing?.symbol
        var name=rawName?.trim()?.takeIf{it.isNotBlank()} ?: existing?.name
        var f=flow ?: existing?.flow
        var b=board ?: existing?.boardTitle

        val currentSegment=MarketPrefs.classify(f,b).let{
            if(it==MarketPrefs.OTHER) existing?.segment ?: it else it
        }

        val needsRemote =
            symbol.isNullOrBlank() ||
            name.isNullOrBlank() ||
            f==null ||
            b.isNullOrBlank() ||
            currentSegment==MarketPrefs.OTHER

        if(needsRemote){
            try{
                val root=api.jsonObjectFrom(
                    api.instrumentInfoRaw(insCode),
                    "instrumentInfo","instrument","data","result"
                )
                if(root!=null){
                    symbol=cleanSymbol(
                        firstString(
                            root,
                            "lVal18AFC","symbol","instrumentName","lVal18",
                            "instrumentCode","symbolFa"
                        ),
                        insCode
                    ) ?: symbol

                    name=firstString(
                        root,
                        "lVal30","name","companyName","companyNamePersian",
                        "companyNameFa","instrumentTitle"
                    )?.trim()?.takeIf{it.isNotBlank()} ?: name

                    f=firstInt(root,"flow","market","marketCode") ?: f

                    b=firstString(
                        root,
                        "cgrValCotTitle","boardTitle","marketTitle","flowTitle",
                        "marketName","boardName"
                    ) ?: b
                }
            }catch(_:Exception){}
        }

        val segment=MarketPrefs.classify(f,b).let{
            if(it==MarketPrefs.OTHER) existing?.segment ?: it else it
        }
        val type=MarketPrefs.classifyType(symbol,name,f,b)

        val entity=SymbolEntity(
            insCode=insCode,
            symbol=symbol,
            name=name,
            flow=f,
            segment=segment,
            boardTitle=b,
            instrumentType=type
        )
        dao.upsertSymbols(listOf(entity))
        return entity
    }
}
