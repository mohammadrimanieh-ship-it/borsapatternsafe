package com.borsapattern.app

import kotlin.math.max
import kotlin.math.min

object PatternEngine {
    private const val MODEL_VERSION=3

    suspend fun rebuildCandidates(db:AppDatabase) {
        val sql=db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM queue_events")

        val c=sql.query("""
          SELECT d.insCode,d.date,d.high,d.last,d.yesterday,d.volume,d.value
          FROM daily d
          INNER JOIN symbols s ON s.insCode=d.insCode
          WHERE d.yesterday>0 AND d.volume>0 AND d.value>0
            AND (
              (
                s.segment IN ('BOURSE','FARABOURSE','BASE_YELLOW','BASE_ORANGE','BASE_RED')
                AND s.instrumentType IN ('TYPE_STOCK','TYPE_BASE')
              )
              OR (
                s.instrumentType='TYPE_FUND' AND
                (
                    COALESCE(s.symbol,'') LIKE '%اهرم%'
                    OR COALESCE(s.name,'') LIKE '%اهرم%'
                    OR COALESCE(s.name,'') LIKE '%اهرمی%'
                    OR COALESCE(s.symbol,'') IN ('توان','شتاب','موج','جهش','بیدار','دوایکس')
                )
              )
            )
          ORDER BY d.insCode ASC,d.date ASC
        """.trimIndent())

        val events=mutableListOf<QueueEventEntity>()
        var currentIns:String?=null
        val recentPositive=ArrayDeque<Double>()
        val recentValues=ArrayDeque<Double>()

        c.use {
            while(it.moveToNext()){
                val ins=it.getString(0)
                val date=it.getInt(1)
                val high=if(it.isNull(2)) null else it.getDouble(2)
                val last=if(it.isNull(3)) null else it.getDouble(3)
                val y=if(it.isNull(4)) null else it.getDouble(4)
                val vol=if(it.isNull(5)) 0.0 else it.getDouble(5)
                val value=if(it.isNull(6)) 0.0 else it.getDouble(6)

                if(currentIns!=ins){
                    currentIns=ins
                    recentPositive.clear()
                    recentValues.clear()
                }

                if(high==null || y==null || y<=0 || vol<=0 || value<=0){
                    continue
                }

                val rise=high/y-1.0
                val closeToHigh=
                    if(last!=null && high>0) (last/high).coerceIn(0.0,1.2)
                    else 0.0

                if(rise>0){
                    val special=isLikelySpecialRise(rise,recentPositive.toList())

                    if(special){
                        events += QueueEventEntity(
                            insCode=ins,
                            date=date,
                            eventTime=null,
                            queueValue=null,
                            score=0.0,
                            status="SPECIAL_REOPEN",
                            signalTime=null,
                            nextTradingDate=null,
                            nextDayQueueStatus="SKIPPED_SPECIAL_REOPEN"
                        )
                    }else{
                        // Cheap pre-filter before requesting historical BestLimits.
                        // A real persistent queue day should normally reach the upper
                        // tail of this symbol's own recent positive range and trade
                        // close to the daily high.
                        val positives=recentPositive
                            .filter{it>0.0 && it<0.095}
                            .sorted()

                        val p85=if(positives.size>=12){
                            positives[((positives.size-1)*0.85).toInt()]
                        }else 0.03

                        val dynamicRiseThreshold=
                            max(0.018,min(0.060,p85*0.92))

                        val values=recentValues.filter{it>0}.sorted()
                        val medianValue=if(values.isNotEmpty()){
                            values[values.size/2]
                        }else 0.0
                        val liquidEnough=
                            medianValue<=0.0 || value>=medianValue*0.35

                        val likelyQueueDay=
                            rise>=dynamicRiseThreshold &&
                            closeToHigh>=0.985 &&
                            liquidEnough

                        if(likelyQueueDay){
                            val riseQuality=
                                ((rise-dynamicRiseThreshold)/0.04)
                                    .coerceIn(0.0,1.0)
                            val closeQuality=
                                ((closeToHigh-0.985)/0.015)
                                    .coerceIn(0.0,1.0)
                            val seed=(
                                52.0 +
                                riseQuality*10.0 +
                                closeQuality*8.0
                            ).coerceIn(52.0,70.0)

                            events += QueueEventEntity(
                                insCode=ins,
                                date=date,
                                eventTime=null,
                                queueValue=null,
                                score=seed,
                                status="CANDIDATE"
                            )
                        }
                    }

                    recentPositive.addLast(rise)
                    while(recentPositive.size>60){
                        recentPositive.removeFirst()
                    }
                }

                recentValues.addLast(value)
                while(recentValues.size>40){
                    recentValues.removeFirst()
                }
            }
        }

        if(events.isNotEmpty()) db.dao().upsertEvents(events)
    }

    suspend fun seedInitialEvents(db:AppDatabase) {
        val sql=db.openHelper.writableDatabase
        val c=sql.query("SELECT COUNT(*) FROM queue_events")
        val count=c.use{ if(it.moveToFirst()) it.getInt(0) else 0 }
        if(count==0) rebuildCandidates(db)
    }

    suspend fun isLikelySpecialReopen(
        dao:BorsaDao,
        insCode:String,
        date:Int
    ):Boolean{
        val day=dao.dailyFor(insCode,date) ?: return false
        val high=day.high ?: return false
        val y=day.yesterday ?: return false
        if(y<=0) return false
        val rise=high/y-1.0
        if(rise<=0.055) return false

        val history=dao.recentDailyBefore(insCode,date,45)
            .mapNotNull{d->
                val h=d.high
                val py=d.yesterday
                if(h!=null && py!=null && py>0){
                    (h/py-1.0).takeIf{it>0}
                }else null
            }
        return isLikelySpecialRise(rise,history)
    }

    private fun isLikelySpecialRise(
        rise:Double,
        previousPositive:List<Double>
    ):Boolean{
        // Large reopenings are safely treated as special.
        if(rise>=0.14) return true

        // Do not remove ordinary wide-range / leveraged moves merely because
        // they exceeded the usual 5–6% range.
        if(rise<0.095) return false

        val clean=previousPositive
            .filter{it>0 && it<0.14}
            .sorted()

        val p90=if(clean.size>=10){
            clean[((clean.size-1)*0.90).toInt()]
        }else 0.05

        // Special must be both unusually large for this symbol and at least 9.5%.
        val threshold=max(0.095,min(0.135,p90*1.80))
        return rise>threshold
    }

    fun scoreLive(
        priceMomentum:Double,
        volumeAccel:Double,
        bidAskImbalance:Double,
        supplyDrop:Double
    ):Double {
        val z=
            .30*priceMomentum.coerceIn(0.0,1.0)+
            .25*volumeAccel.coerceIn(0.0,1.0)+
            .30*bidAskImbalance.coerceIn(0.0,1.0)+
            .15*supplyDrop.coerceIn(0.0,1.0)
        return (z*100.0).coerceIn(0.0,100.0)
    }
}
