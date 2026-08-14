package com.borsapattern.app

import kotlin.math.max
import kotlin.math.min

data class TechnicalResult(
    val score:Double,
    val rsi:Double?,
    val macd:Double?,
    val sma20:Double?,
    val sma50:Double?,
    val summary:String
)

object TechnicalEngine {
    fun calculate(rowsDesc:List<DailyEntity>, livePrice:Double?):TechnicalResult {
        if(rowsDesc.size < 20) {
            return TechnicalResult(35.0,null,null,null,null,"داده تکنیکال هنوز کافی نیست")
        }

        val rows=rowsDesc.reversed()
        val closes=rows.mapNotNull{it.last ?: it.yesterday}.toMutableList()
        if(closes.size < 20) {
            return TechnicalResult(35.0,null,null,null,null,"داده تکنیکال ناقص")
        }

        if(livePrice!=null && livePrice>0) {
            if(closes.isNotEmpty()) closes[closes.lastIndex]=livePrice
        }

        val sma20=sma(closes,20)
        val sma50=if(closes.size>=50) sma(closes,50) else null
        val rsi=rsi(closes,14)
        val macd=macdHistogram(closes)

        val price=closes.last()
        var score=45.0
        val reasons=mutableListOf<String>()

        if(sma20!=null){
            if(price>sma20){ score+=10; reasons+="بالای MA20" }
            else score-=7
        }
        if(sma50!=null){
            if(price>sma50){ score+=10; reasons+="بالای MA50" }
            else score-=5
            if(sma20!=null && sma20>sma50){ score+=8; reasons+="روند میانگین‌ها مثبت" }
        }

        if(rsi!=null){
            when{
                rsi in 50.0..68.0 -> {score+=12; reasons+="RSI مثبت"}
                rsi in 40.0..<50.0 -> score+=2
                rsi>78 -> {score-=10; reasons+="RSI داغ"}
                rsi<35 -> score-=6
            }
        }

        if(macd!=null){
            if(macd>0){score+=10; reasons+="MACD مثبت"}
            else score-=5
        }

        val recentHigh=rows.takeLast(min(21,rows.size-1))
            .dropLast(1).mapNotNull{it.high}.maxOrNull()
        if(recentHigh!=null && price>recentHigh){
            score+=10
            reasons+="شکست سقف ۲۰روزه"
        }

        return TechnicalResult(
            score=score.coerceIn(0.0,100.0),
            rsi=rsi,
            macd=macd,
            sma20=sma20,
            sma50=sma50,
            summary=if(reasons.isEmpty()) "تکنیکال خنثی" else reasons.joinToString("، ")
        )
    }

    private fun sma(v:List<Double>,period:Int):Double? =
        if(v.size<period) null else v.takeLast(period).average()

    private fun rsi(v:List<Double>,period:Int):Double?{
        if(v.size<=period) return null
        var gains=0.0
        var losses=0.0
        val start=v.size-period
        for(i in start until v.size){
            val d=v[i]-v[i-1]
            if(d>=0) gains+=d else losses-=d
        }
        val avgGain=gains/period
        val avgLoss=losses/period
        if(avgLoss==0.0) return 100.0
        val rs=avgGain/avgLoss
        return 100.0-(100.0/(1.0+rs))
    }

    private fun emaSeries(v:List<Double>,period:Int):List<Double>{
        if(v.isEmpty()) return emptyList()
        val k=2.0/(period+1.0)
        val out=ArrayList<Double>(v.size)
        var e=v.first()
        out+=e
        for(i in 1 until v.size){
            e=v[i]*k+e*(1-k)
            out+=e
        }
        return out
    }

    private fun macdHistogram(v:List<Double>):Double?{
        if(v.size<35) return null
        val e12=emaSeries(v,12)
        val e26=emaSeries(v,26)
        val line=v.indices.map{i->e12[i]-e26[i]}
        val signal=emaSeries(line,9)
        return line.last()-signal.last()
    }
}
