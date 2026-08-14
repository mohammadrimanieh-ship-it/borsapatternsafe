package com.borsapattern.app

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.*
import java.util.concurrent.TimeUnit

class BorsaApp:Application(){
    lateinit var db:AppDatabase

    override fun onCreate(){
        super.onCreate()
        db=Room.databaseBuilder(this,AppDatabase::class.java,"borsa_safe_v281.db")
            .addMigrations(
                MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,
                MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8
            )
            .build()
        Notifications.createChannel(this)

        val appPrefs=getSharedPreferences("app_state",MODE_PRIVATE)
        if(!appPrefs.getBoolean("v18_worker_reset_done",false)){
            val wm=WorkManager.getInstance(this)
            wm.cancelUniqueWork(HistoricalWorker.HISTORY_CHAIN)
            wm.cancelUniqueWork("daily_incremental_sync_kickoff")
            wm.cancelUniqueWork("historical_queue_analysis")
            getSharedPreferences("sync",MODE_PRIVATE).edit()
                .putBoolean("sync_running",false)
                .putInt("sync_done",0)
                .putInt("sync_total",0)
                .putString("sync_status","آماده؛ برای استخراج انتخاب‌ها را تایید کنید")
                .apply()

            // One-time repair only for genuinely missing symbol names.
            val nameRepair=OneTimeWorkRequestBuilder<MetadataWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(workDataOf("batch" to 30))
                .build()
            wm.enqueueUniqueWork(
                MetadataWorker.CHAIN,
                ExistingWorkPolicy.KEEP,
                nameRepair
            )

            appPrefs.edit().putBoolean("v18_worker_reset_done",true).apply()
        }

        val catalogPrefs=getSharedPreferences("catalog",MODE_PRIVATE)
        val now=System.currentTimeMillis()
        val lastCatalog=catalogPrefs.getLong("last_refresh",0L)
        val appState=getSharedPreferences("app_state",MODE_PRIVATE)
        val forceV24=!appState.getBoolean("v24_symbol_update_done",false)

        if(forceV24 || now-lastCatalog > 12L*60L*60L*1000L){
            if(forceV24){
                catalogPrefs.edit()
                    .remove("eligible_count")
                    .remove("raw_count")
                    .remove("bourse_count")
                    .remove("farabourse_count")
                    .remove("base_count")
                    .remove("leveraged_count")
                    .remove("unknown_count")
                    .remove("excluded_count")
                    .putString("status","در حال بازسازی کامل فهرست نمادها")
                    .apply()
            }

            val catalogReq=OneTimeWorkRequestBuilder<SymbolCatalogWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                SymbolCatalogWorker.CHAIN,
                if(forceV24) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                catalogReq
            )
            if(forceV24){
                appState.edit().putBoolean("v24_symbol_update_done",true).apply()
            }
        }

        if(catalogPrefs.getInt("unknown_count",0)>0){
            val resumeMetadata=OneTimeWorkRequestBuilder<MetadataWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(workDataOf("batch" to 60))
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                MetadataWorker.CHAIN,
                ExistingWorkPolicy.KEEP,
                resumeMetadata
            )
        }

        val pipelineState=getSharedPreferences("analysis_pipeline",MODE_PRIVATE)
        if(pipelineState.getBoolean("enabled",false)){
            val repair=OneTimeWorkRequestBuilder<PipelineCoordinatorWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                PipelineCoordinatorWorker.CHAIN,
                ExistingWorkPolicy.KEEP,
                repair
            )
        }

        scheduleBackgroundWork()
    }

    private fun scheduleBackgroundWork(){
        val wm=WorkManager.getInstance(this)

        // Extraction and historical analysis are user-controlled only.
        // Cancel legacy periodic jobs left by older versions.
        wm.cancelUniqueWork("daily_incremental_sync_kickoff")
        wm.cancelUniqueWork("historical_queue_analysis")

        // Keep only lightweight live monitoring; it respects the 09:00-12:30 window.
        val net=HistoricalWorker.networkConstraint()
        wm.enqueueUniquePeriodicWork(
            "live_monitor",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LiveWorker>(15,TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
        )

        // Local-only classification repair; no network extraction.
        wm.enqueueUniqueWork(
            "category_repair",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CategoryRepairWorker>().build()
        )
    }

    companion object{
        val MIGRATION_1_2=object:Migration(1,2){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE symbols ADD COLUMN flow INTEGER")
                db.execSQL("ALTER TABLE symbols ADD COLUMN segment TEXT NOT NULL DEFAULT 'OTHER'")
                db.execSQL("ALTER TABLE symbols ADD COLUMN boardTitle TEXT")
            }
        }

        val MIGRATION_2_3=object:Migration(2,3){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE live_scores ADD COLUMN patternScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN technicalScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN volumeScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN rsi REAL")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN macd REAL")
            }
        }

        val MIGRATION_3_4=object:Migration(3,4){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE live_scores ADD COLUMN actorScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN lastPrice REAL NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS paper_trades (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        insCode TEXT NOT NULL,
                        symbol TEXT,
                        entryPrice REAL NOT NULL,
                        currentPrice REAL NOT NULL,
                        entryTime INTEGER NOT NULL,
                        exitTime INTEGER,
                        exitPrice REAL,
                        status TEXT NOT NULL,
                        entryScore REAL NOT NULL,
                        pnlPct REAL NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5=object:Migration(4,5){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL(
                    "ALTER TABLE symbols ADD COLUMN instrumentType TEXT NOT NULL DEFAULT 'TYPE_STOCK'"
                )
            }
        }

        val MIGRATION_5_6=object:Migration(5,6){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE queue_events ADD COLUMN signalTime INTEGER")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN nextTradingDate INTEGER")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN nextDayQueueStatus TEXT NOT NULL DEFAULT 'PENDING'")
            }
        }
        val MIGRATION_6_7=object:Migration(6,7){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS prequeue_snapshots (
                        insCode TEXT NOT NULL,
                        date INTEGER NOT NULL,
                        minutesBefore INTEGER NOT NULL,
                        snapshotTime INTEGER NOT NULL,
                        score REAL NOT NULL,
                        bidImbalance REAL NOT NULL,
                        bidGrowth REAL NOT NULL,
                        askDrop REAL NOT NULL,
                        pricePressure REAL NOT NULL,
                        label INTEGER NOT NULL,
                        detected INTEGER NOT NULL,
                        PRIMARY KEY(insCode,date,minutesBefore)
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_7_8=object:Migration(7,8){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queueDurationMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queuePersistenceRatio REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queueBreakCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queueEndHeld INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queueValueRetention REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN nextDayReturnPct REAL")
            }
        }
    }
}
