package com.borsapattern.app

import android.content.Context
import androidx.work.*

class PipelineCoordinatorWorker(
    ctx:Context,
    p:WorkerParameters
):CoroutineWorker(ctx,p){

    private val app get()=applicationContext as BorsaApp
    private val dao get()=app.db.dao()

    override suspend fun doWork():Result{
        val state=applicationContext.getSharedPreferences(
            "analysis_pipeline",Context.MODE_PRIVATE
        )
        if(!state.getBoolean("enabled",false)){
            return Result.success()
        }

        val wm=WorkManager.getInstance(applicationContext)

        val segments=MarketPrefs.selectedSegments(applicationContext).toList()
        val types=MarketPrefs.selectedTypes(applicationContext).toList()
        // v3 parallel pipeline: start every stage that already has eligible work.
        // The stages still respect data dependencies at row level, but no longer wait
        // for the whole previous stage to finish before useful work can begin.
        val pendingCandidates=dao.candidateCountFor(segments,types)+dao.errorCountFor(segments,types)
        var launched=false
        if(pendingCandidates>0){
            val req=OneTimeWorkRequestBuilder<QueueAnalysisWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(workDataOf("batchSize" to 120,"parallelism" to 4,"resetErrors" to true))
                .build()
            wm.enqueueUniqueWork(QueueAnalysisWorker.ANALYSIS_CHAIN,ExistingWorkPolicy.KEEP,req)
            launched=true
        }

        if(dao.nextDayPendingCount()>0){
            val req=OneTimeWorkRequestBuilder<NextDayQueueWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .build()
            wm.enqueueUniqueWork(NextDayQueueWorker.CHAIN,ExistingWorkPolicy.KEEP,req)
            launched=true
        }

        val walkTotal=dao.walkForwardEligibleCount()
        val walkDone=dao.walkForwardProcessedCount()
        if(walkDone<walkTotal){
            val req=OneTimeWorkRequestBuilder<PreQueueBacktestWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(workDataOf("batch" to 24))
                .build()
            wm.enqueueUniqueWork(PreQueueBacktestWorker.CHAIN,ExistingWorkPolicy.KEEP,req)
            launched=true
        }

        if(launched){
            state.edit().putString("stage","PARALLEL_3").apply()
            return Result.success()
        }

        QueuePatternLearningEngine.rebuild(applicationContext)
        state.edit()
            .putString("stage","COMPLETE")
            .putLong("completed_at",System.currentTimeMillis())
            .apply()
        return Result.success()
    }

    companion object{
        const val CHAIN="analysis_pipeline_coordinator"
    }
}
