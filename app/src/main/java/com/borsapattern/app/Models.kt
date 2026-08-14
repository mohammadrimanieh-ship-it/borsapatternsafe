package com.borsapattern.app

import androidx.room.*

@Entity(tableName="symbols")
data class SymbolEntity(
    @PrimaryKey val insCode:String,
    val symbol:String?,
    val name:String?,
    val flow:Int?=null,
    val segment:String="OTHER",
    val boardTitle:String?=null,
    val instrumentType:String="TYPE_STOCK"
)

@Entity(tableName="daily",primaryKeys=["insCode","date"])
data class DailyEntity(
    val insCode:String,val date:Int,val high:Double?,val last:Double?,
    val yesterday:Double?,val volume:Double?,val value:Double?
)

@Entity(tableName="queue_events",primaryKeys=["insCode","date"])
data class QueueEventEntity(
    val insCode:String,val date:Int,val eventTime:Int?,val queueValue:Double?,
    val score:Double,val status:String,
    val signalTime:Int?=null,
    val nextTradingDate:Int?=null,
    val nextDayQueueStatus:String="PENDING",
    val queueDurationMinutes:Int=0,
    val queuePersistenceRatio:Double=0.0,
    val queueBreakCount:Int=0,
    val queueEndHeld:Boolean=false,
    val queueValueRetention:Double=0.0,
    val nextDayReturnPct:Double?=null
)

@Entity(
    tableName="prequeue_snapshots",
    primaryKeys=["insCode","date","minutesBefore"]
)
data class PreQueueSnapshotEntity(
    val insCode:String,
    val date:Int,
    val minutesBefore:Int,
    val snapshotTime:Int,
    val score:Double,
    val bidImbalance:Double,
    val bidGrowth:Double,
    val askDrop:Double,
    val pricePressure:Double,
    val label:Int,
    val detected:Boolean
)

@Entity(tableName="live_scores")
data class LiveScoreEntity(
    @PrimaryKey val insCode:String,
    val symbol:String?,
    val score:Double,
    val reason:String,
    val updatedAt:Long,
    val patternScore:Double=0.0,
    val technicalScore:Double=0.0,
    val volumeScore:Double=0.0,
    val rsi:Double?=null,
    val macd:Double?=null,
    val actorScore:Double=0.0,
    val lastPrice:Double=0.0
)

@Entity(tableName="paper_trades")
data class PaperTradeEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val insCode:String,
    val symbol:String?,
    val entryPrice:Double,
    val currentPrice:Double,
    val entryTime:Long,
    val exitTime:Long?,
    val exitPrice:Double?,
    val status:String,
    val entryScore:Double,
    val pnlPct:Double
)

data class QueueHistoryRow(
    val insCode:String,val symbol:String?,val date:Int,val eventTime:Int?,
    val queueValue:Double?,val score:Double,val status:String,
    val signalTime:Int?,val nextTradingDate:Int?,val nextDayQueueStatus:String,
    val queueDurationMinutes:Int,val queuePersistenceRatio:Double,
    val queueBreakCount:Int,val queueEndHeld:Boolean,
    val queueValueRetention:Double,val nextDayReturnPct:Double?
)

data class SymbolSignalRow(
    val insCode:String,val symbol:String?,val date:Int,val signalTime:Int?,
    val eventTime:Int?,val score:Double,val status:String,
    val nextTradingDate:Int?,val nextDayQueueStatus:String,
    val queueDurationMinutes:Int,val queuePersistenceRatio:Double,
    val queueBreakCount:Int,val queueEndHeld:Boolean,
    val queueValueRetention:Double,val nextDayReturnPct:Double?
)

data class SymbolDetailStats(
    val recordCount:Int,
    val firstDate:Int?,
    val lastDate:Int?
)

data class PreQueueSnapshotRow(
    val insCode:String,
    val date:Int,
    val minutesBefore:Int,
    val snapshotTime:Int,
    val score:Double,
    val bidImbalance:Double,
    val bidGrowth:Double,
    val askDrop:Double,
    val pricePressure:Double,
    val label:Int,
    val detected:Boolean
)

@Dao
interface BorsaDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertSymbols(items:List<SymbolEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertDaily(items:List<DailyEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertEvents(items:List<QueueEventEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertPreQueueSnapshots(items:List<PreQueueSnapshotEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertScores(items:List<LiveScoreEntity>)
    @Insert suspend fun insertPaperTrade(item:PaperTradeEntity):Long

    @Query("DELETE FROM prequeue_snapshots WHERE insCode IN (:codes)")
    suspend fun deletePreQueueByCodes(codes:List<String>)

    @Query("DELETE FROM prequeue_snapshots")
    suspend fun deleteAllPreQueueSnapshots()

    @Query("DELETE FROM queue_events WHERE insCode IN (:codes)")
    suspend fun deleteEventsByCodes(codes:List<String>)

    @Query("DELETE FROM daily WHERE insCode IN (:codes)")
    suspend fun deleteDailyByCodes(codes:List<String>)

    @Query("DELETE FROM live_scores WHERE insCode IN (:codes)")
    suspend fun deleteLiveByCodes(codes:List<String>)

    @Query("DELETE FROM symbols WHERE insCode IN (:codes)")
    suspend fun deleteSymbolsByCodes(codes:List<String>)

    @Query("SELECT COUNT(*) FROM symbols") suspend fun symbolCount():Int
    @Query("SELECT COUNT(*) FROM daily") suspend fun dailyCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='CANDIDATE'") suspend fun candidateCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='QUEUE_CONFIRMED'") suspend fun confirmedCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='FRAGILE_QUEUE'") suspend fun fragileQueueCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='NOT_QUEUE'") suspend fun rejectedCount():Int
    @Query("SELECT COUNT(*) FROM queue_events WHERE status='ERROR'") suspend fun errorCount():Int

    @Query("""
      SELECT COUNT(*) FROM queue_events e
      INNER JOIN symbols s ON s.insCode=e.insCode
      WHERE e.status='ERROR'
        AND s.instrumentType IN (:types)
        AND (
          s.segment IN (:segments)
          OR (
            s.instrumentType='TYPE_FUND'
            AND (
              COALESCE(s.symbol,'') LIKE '%اهرم%'
              OR COALESCE(s.name,'') LIKE '%اهرم%'
              OR COALESCE(s.name,'') LIKE '%اهرمی%'
              OR COALESCE(s.symbol,'') IN ('توان','شتاب','موج','جهش','بیدار','دوایکس')
            )
          )
        )
    """)
    suspend fun errorCountFor(segments:List<String>,types:List<String>):Int

    @Query("""
      SELECT COUNT(*) FROM queue_events
      WHERE status='QUEUE_CONFIRMED' AND nextDayQueueStatus='PENDING'
    """)
    suspend fun nextDayPendingCount():Int

    @Query("""
      SELECT COUNT(*) FROM queue_events
      WHERE status='QUEUE_CONFIRMED' AND nextDayQueueStatus!='PENDING'
    """)
    suspend fun nextDayCompletedCount():Int

    @Query("""
      SELECT COUNT(*) FROM queue_events
      WHERE status IN ('QUEUE_CONFIRMED','NOT_QUEUE','FRAGILE_QUEUE')
    """)
    suspend fun walkForwardEligibleCount():Int

    @Query("""
      SELECT COUNT(*) FROM (
        SELECT DISTINCT insCode,date FROM prequeue_snapshots
      )
    """)
    suspend fun walkForwardProcessedCount():Int
    @Query("SELECT MAX(date) FROM daily") suspend fun latestMarketDate():Int?
    @Query("SELECT MAX(date) FROM daily WHERE insCode=:insCode") suspend fun latestDateFor(insCode:String):Int?
    @Query("SELECT MIN(date) FROM daily WHERE insCode=:insCode") suspend fun earliestDateFor(insCode:String):Int?

    @Query("SELECT * FROM symbols ORDER BY COALESCE(symbol,name,insCode)")
    suspend fun allSymbols():List<SymbolEntity>

    @Query("SELECT * FROM symbols WHERE insCode=:insCode LIMIT 1")
    suspend fun symbolByCode(insCode:String):SymbolEntity?

    @Query("UPDATE symbols SET instrumentType=:type WHERE insCode=:insCode")
    suspend fun updateInstrumentType(insCode:String,type:String)

    @Query("""
      SELECT * FROM symbols
      WHERE symbol IS NULL OR TRIM(symbol)='' OR symbol=insCode OR symbol GLOB '[0-9]*'
      ORDER BY insCode LIMIT :limit
    """)
    suspend fun unknownSymbols(limit:Int):List<SymbolEntity>

    @Query("""
      SELECT * FROM symbols
      WHERE symbol IS NULL OR TRIM(symbol)='' OR symbol=insCode OR symbol GLOB '[0-9]*'
         OR name IS NULL OR TRIM(name)=''
         OR flow IS NULL
         OR segment='OTHER'
      ORDER BY
        CASE WHEN symbol IS NULL OR TRIM(symbol)='' THEN 0 ELSE 1 END,
        insCode
      LIMIT :limit
    """)
    suspend fun symbolsNeedingMetadata(limit:Int):List<SymbolEntity>

    @Query("""
      SELECT * FROM live_scores
      WHERE insCode NOT IN (SELECT insCode FROM symbols)
         OR symbol IS NULL OR TRIM(symbol)=''
      ORDER BY updatedAt DESC LIMIT :limit
    """)
    suspend fun liveScoresNeedingName(limit:Int):List<LiveScoreEntity>

    @Query("""
      UPDATE live_scores
      SET symbol=(SELECT COALESCE(NULLIF(symbol,''),NULLIF(name,'')) FROM symbols s WHERE s.insCode=live_scores.insCode)
      WHERE EXISTS(
        SELECT 1 FROM symbols s
        WHERE s.insCode=live_scores.insCode
          AND COALESCE(NULLIF(s.symbol,''),NULLIF(s.name,'')) IS NOT NULL
      )
    """)
    suspend fun repairLiveScoreNames()

    @Query("SELECT * FROM daily WHERE insCode=:insCode ORDER BY date DESC LIMIT :limit")
    suspend fun recentDaily(insCode:String,limit:Int=220):List<DailyEntity>

    @Query("""
      SELECT l.insCode AS insCode,
             COALESCE(NULLIF(s.symbol,''),NULLIF(s.name,''),NULLIF(l.symbol,''),'در حال تکمیل نام') AS symbol,
             l.score AS score,l.reason AS reason,l.updatedAt AS updatedAt,
             l.patternScore AS patternScore,l.technicalScore AS technicalScore,
             l.volumeScore AS volumeScore,l.rsi AS rsi,l.macd AS macd,
             l.actorScore AS actorScore,l.lastPrice AS lastPrice
      FROM live_scores l
      LEFT JOIN symbols s ON s.insCode=l.insCode
      WHERE COALESCE(s.segment,'OTHER') IN (:segments)
        AND COALESCE(s.instrumentType,'TYPE_STOCK') IN (:types)
      ORDER BY l.score DESC LIMIT 50
    """)
    suspend fun topScoresFor(segments:List<String>,types:List<String>):List<LiveScoreEntity>

    @Query("""
      SELECT e.* FROM queue_events e
      INNER JOIN symbols s ON s.insCode=e.insCode
      WHERE e.status='CANDIDATE'
        AND s.instrumentType IN (:types)
        AND (
          s.segment IN (:segments)
          OR (
            s.instrumentType='TYPE_FUND'
            AND (
              COALESCE(s.symbol,'') LIKE '%اهرم%'
              OR COALESCE(s.name,'') LIKE '%اهرم%'
              OR COALESCE(s.name,'') LIKE '%اهرمی%'
              OR COALESCE(s.symbol,'') IN ('توان','شتاب','موج','جهش','بیدار','دوایکس')
            )
          )
        )
      ORDER BY e.date DESC LIMIT :limit
    """)
    suspend fun candidateEventsFor(segments:List<String>,types:List<String>,limit:Int):List<QueueEventEntity>

    @Query("""
      SELECT COUNT(*) FROM queue_events e
      INNER JOIN symbols s ON s.insCode=e.insCode
      WHERE e.status='CANDIDATE'
        AND s.instrumentType IN (:types)
        AND (
          s.segment IN (:segments)
          OR (
            s.instrumentType='TYPE_FUND'
            AND (
              COALESCE(s.symbol,'') LIKE '%اهرم%'
              OR COALESCE(s.name,'') LIKE '%اهرم%'
              OR COALESCE(s.name,'') LIKE '%اهرمی%'
              OR COALESCE(s.symbol,'') IN ('توان','شتاب','موج','جهش','بیدار','دوایکس')
            )
          )
        )
    """)
    suspend fun candidateCountFor(segments:List<String>,types:List<String>):Int

    @Query("SELECT * FROM daily WHERE insCode=:insCode AND date=:date LIMIT 1")
    suspend fun dailyFor(insCode:String,date:Int):DailyEntity?

    @Query("""
      SELECT * FROM daily
      WHERE insCode=:insCode AND date<:date
      ORDER BY date DESC LIMIT :limit
    """)
    suspend fun recentDailyBefore(insCode:String,date:Int,limit:Int=45):List<DailyEntity>


    @Query("SELECT COUNT(*) FROM queue_events WHERE status='SPECIAL_REOPEN'")
    suspend fun specialReopenCount():Int

    @Query("""
      SELECT COUNT(*) FROM queue_events
      WHERE status='QUEUE_CONFIRMED'
        AND nextDayQueueStatus IN ('QUEUE_AGAIN','PREOPEN_QUEUE_NEXT_DAY')
    """)
    suspend fun twoDayQueueCount():Int

    @Query("""
      SELECT COUNT(*) FROM queue_events
      WHERE status='QUEUE_CONFIRMED'
        AND nextDayQueueStatus IN (
          'PREOPEN_QUEUE_NEXT_DAY','QUEUE_AGAIN',
          'POSITIVE_STRONG_NEXT_DAY','POSITIVE_NEXT_DAY'
        )
    """)
    suspend fun positiveContinuationCount():Int

    @Query("""
      SELECT COUNT(*) FROM queue_events
      WHERE status='QUEUE_CONFIRMED'
        AND nextDayQueueStatus='PREOPEN_QUEUE_NEXT_DAY'
    """)
    suspend fun strongPreopenNextDayCount():Int

    @Query("SELECT COUNT(*) FROM queue_events WHERE status='PREOPEN_QUEUE'")
    suspend fun preopenDay1ExcludedCount():Int

    @Query("""
      SELECT AVG(queuePersistenceRatio) FROM queue_events
      WHERE status='QUEUE_CONFIRMED'
    """)
    suspend fun averagePersistenceRatio():Double?

    @Query("""
      SELECT AVG(queueDurationMinutes) FROM queue_events
      WHERE status='QUEUE_CONFIRMED'
    """)
    suspend fun averageQueueDuration():Double?

    @Query("UPDATE queue_events SET status='CANDIDATE' WHERE status='ERROR'")
    suspend fun retryErrors()

    @Query("""
      SELECT e.insCode AS insCode,
             COALESCE(NULLIF(s.symbol,''),NULLIF(s.name,''),'در حال تکمیل نام') AS symbol,
             e.date AS date,e.eventTime AS eventTime,e.queueValue AS queueValue,
             e.score AS score,e.status AS status,
             e.signalTime AS signalTime,e.nextTradingDate AS nextTradingDate,
             e.nextDayQueueStatus AS nextDayQueueStatus,
             e.queueDurationMinutes AS queueDurationMinutes,
             e.queuePersistenceRatio AS queuePersistenceRatio,
             e.queueBreakCount AS queueBreakCount,
             e.queueEndHeld AS queueEndHeld,
             e.queueValueRetention AS queueValueRetention,
             e.nextDayReturnPct AS nextDayReturnPct
      FROM queue_events e
      INNER JOIN symbols s ON s.insCode=e.insCode
      WHERE e.status='QUEUE_CONFIRMED'
        AND s.segment IN (:segments)
        AND s.instrumentType IN (:types)
      ORDER BY e.date DESC,e.score DESC LIMIT :limit
    """)
    suspend fun confirmedHistoryFor(
        segments:List<String>,
        types:List<String>,
        limit:Int=1000
    ):List<QueueHistoryRow>

    @Query("""
      SELECT l.insCode AS insCode,
             COALESCE(NULLIF(s.symbol,''),NULLIF(s.name,''),NULLIF(l.symbol,''),'در حال تکمیل نام') AS symbol,
             l.score AS score,l.reason AS reason,l.updatedAt AS updatedAt,
             l.patternScore AS patternScore,l.technicalScore AS technicalScore,
             l.volumeScore AS volumeScore,l.rsi AS rsi,l.macd AS macd,
             l.actorScore AS actorScore,l.lastPrice AS lastPrice
      FROM live_scores l
      INNER JOIN symbols s ON s.insCode=l.insCode
      WHERE s.segment IN ('BOURSE','FARABOURSE','BASE_YELLOW','BASE_ORANGE','BASE_RED')
        AND (
          s.instrumentType IN ('TYPE_STOCK','TYPE_BASE')
          OR (
            s.instrumentType='TYPE_FUND'
            AND (
              COALESCE(s.symbol,'') LIKE '%اهرم%'
              OR COALESCE(s.name,'') LIKE '%اهرم%'
              OR COALESCE(s.name,'') LIKE '%اهرمی%'
              OR COALESCE(s.symbol,'') IN ('توان','شتاب','موج','جهش','بیدار','دوایکس')
            )
          )
        )
        AND s.instrumentType!='TYPE_OPTION'
      ORDER BY l.score DESC LIMIT 80
    """)
    suspend fun topSignalScores():List<LiveScoreEntity>

    @Query("""
      SELECT COUNT(*) AS recordCount, MIN(date) AS firstDate, MAX(date) AS lastDate
      FROM daily WHERE insCode=:insCode
    """)
    suspend fun symbolDetailStats(insCode:String):SymbolDetailStats

    @Query("SELECT * FROM paper_trades WHERE status='OPEN' AND insCode=:insCode LIMIT 1")
    suspend fun openPaperTrade(insCode:String):PaperTradeEntity?

    @Query("SELECT * FROM paper_trades ORDER BY entryTime DESC LIMIT :limit")
    suspend fun recentPaperTrades(limit:Int=100):List<PaperTradeEntity>

    @Query("""
      UPDATE paper_trades
      SET currentPrice=:price,pnlPct=:pnl
      WHERE id=:id
    """)
    suspend fun updatePaperTrade(id:Long,price:Double,pnl:Double)

    @Query("""
      UPDATE paper_trades
      SET currentPrice=:price,exitPrice=:price,exitTime=:exitTime,status='CLOSED',pnlPct=:pnl
      WHERE id=:id
    """)
    suspend fun closePaperTrade(id:Long,price:Double,exitTime:Long,pnl:Double)

    @Query("""
      SELECT * FROM symbols
      WHERE (
        symbol LIKE '%' || :q || '%'
        OR name LIKE '%' || :q || '%'
        OR insCode LIKE '%' || :q || '%'
      )
      ORDER BY CASE WHEN symbol=:q THEN 0 ELSE 1 END, COALESCE(symbol,name)
      LIMIT :limit
    """)
    suspend fun searchSymbols(q:String,limit:Int=30):List<SymbolEntity>

    @Query("""
      SELECT e.insCode AS insCode,
             COALESCE(NULLIF(s.symbol,''),NULLIF(s.name,''),'در حال تکمیل نام') AS symbol,
             e.date AS date,e.signalTime AS signalTime,e.eventTime AS eventTime,
             e.score AS score,e.status AS status,
             e.nextTradingDate AS nextTradingDate,e.nextDayQueueStatus AS nextDayQueueStatus,
             e.queueDurationMinutes AS queueDurationMinutes,
             e.queuePersistenceRatio AS queuePersistenceRatio,
             e.queueBreakCount AS queueBreakCount,
             e.queueEndHeld AS queueEndHeld,
             e.queueValueRetention AS queueValueRetention,
             e.nextDayReturnPct AS nextDayReturnPct
      FROM queue_events e LEFT JOIN symbols s ON s.insCode=e.insCode
      WHERE e.insCode=:insCode ORDER BY e.date DESC LIMIT :limit
    """)
    suspend fun signalHistoryForSymbol(insCode:String,limit:Int=250):List<SymbolSignalRow>

    @Query("""
      SELECT * FROM prequeue_snapshots
      WHERE insCode=:insCode
        AND minutesBefore IN (0,30,20,15,10,5,-100,-200,-300)
      ORDER BY date DESC,
               CASE WHEN minutesBefore=0 THEN -1 ELSE minutesBefore END DESC
      LIMIT :limit
    """)
    suspend fun preQueueTimelineForSymbol(
        insCode:String,
        limit:Int=700
    ):List<PreQueueSnapshotRow>

    @Query("SELECT COUNT(*) FROM prequeue_snapshots")
    suspend fun preQueueSnapshotCount():Int

    @Query("""
      SELECT * FROM queue_events
      WHERE status='QUEUE_CONFIRMED' AND nextDayQueueStatus='PENDING'
      ORDER BY date ASC LIMIT :limit
    """)
    suspend fun pendingNextDayChecks(limit:Int):List<QueueEventEntity>

    @Query("SELECT * FROM daily WHERE insCode=:insCode AND date>:date ORDER BY date ASC LIMIT 1")
    suspend fun nextTradingDaily(insCode:String,date:Int):DailyEntity?

    @Query("""
      UPDATE queue_events
      SET nextTradingDate=:nextDate,
          nextDayQueueStatus=:result,
          nextDayReturnPct=:returnPct
      WHERE insCode=:insCode AND date=:date
    """)
    suspend fun updateNextDayResult(
        insCode:String,date:Int,nextDate:Int?,result:String,returnPct:Double?=null
    )
}

@Database(
    entities=[
        SymbolEntity::class,DailyEntity::class,QueueEventEntity::class,
        PreQueueSnapshotEntity::class,
        LiveScoreEntity::class,PaperTradeEntity::class
    ],
    version=8,exportSchema=false
)
abstract class AppDatabase:RoomDatabase(){ abstract fun dao():BorsaDao }
