package com.borsapattern.app

import android.content.Context

object PaperTradingEngine {
    private const val ENTRY_SCORE=82.0
    private const val STOP_LOSS=-3.0
    private const val TAKE_PROFIT=5.0
    private const val EXIT_SCORE=45.0

    suspend fun process(context:Context,scores:List<LiveScoreEntity>){
        val dao=(context.applicationContext as BorsaApp).db.dao()
        val now=System.currentTimeMillis()

        for(s in scores){
            if(s.lastPrice<=0) continue
            val open=dao.openPaperTrade(s.insCode)

            if(open==null){
                if(s.score>=ENTRY_SCORE && !s.symbol.isNullOrBlank()){
                    dao.insertPaperTrade(
                        PaperTradeEntity(
                            insCode=s.insCode,
                            symbol=s.symbol,
                            entryPrice=s.lastPrice,
                            currentPrice=s.lastPrice,
                            entryTime=now,
                            exitTime=null,
                            exitPrice=null,
                            status="OPEN",
                            entryScore=s.score,
                            pnlPct=0.0
                        )
                    )
                }
            }else{
                val pnl=((s.lastPrice/open.entryPrice)-1.0)*100.0
                if(pnl<=STOP_LOSS || pnl>=TAKE_PROFIT || s.score<EXIT_SCORE){
                    dao.closePaperTrade(open.id,s.lastPrice,now,pnl)
                }else{
                    dao.updatePaperTrade(open.id,s.lastPrice,pnl)
                }
            }
        }
    }
}
