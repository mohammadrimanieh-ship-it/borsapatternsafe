package com.borsapattern.app

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.work.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity:ComponentActivity(){
    private val notifPerm=registerForActivityResult(ActivityResultContracts.RequestPermission()){}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(android.os.Build.VERSION.SDK_INT>=33)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { AppUi() } }
    }

    @Composable
    private fun AppTheme(content: @Composable () -> Unit){
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){
        MaterialTheme(
            colorScheme=lightColorScheme(
                primary=Color(0xFF5C35C8),
                secondary=Color(0xFF16B8A6),
                background=Color(0xFFF7F8FC),
                surface=Color.White,
                primaryContainer=Color(0xFFEFE8FF),
                secondaryContainer=Color(0xFFE7F7F4)
            ),
            content=content
        )
        }
    }

    @Composable
    private fun AppUi(){
        val app=application as BorsaApp
        val syncPrefs=remember{getSharedPreferences("sync",Context.MODE_PRIVATE)}
        val analysisPrefs=remember{getSharedPreferences("analysis",Context.MODE_PRIVATE)}
        val metaPrefs=remember{getSharedPreferences("metadata",Context.MODE_PRIVATE)}
        val nextPrefs=remember{getSharedPreferences("nextday",Context.MODE_PRIVATE)}
        val catalogPrefs=remember{getSharedPreferences("catalog",Context.MODE_PRIVATE)}
        val queueLearningPrefs=remember{getSharedPreferences("queue_learning",Context.MODE_PRIVATE)}
        val preQueuePrefs=remember{getSharedPreferences("prequeue_backtest",Context.MODE_PRIVATE)}

        var symbols by remember{mutableStateOf(0)}
        var records by remember{mutableStateOf(0)}
        var eligibleCount by remember{mutableStateOf(0)}
        var candidates by remember{mutableStateOf(0)}
        var confirmed by remember{mutableStateOf(0)}
        var rejected by remember{mutableStateOf(0)}
        var errors by remember{mutableStateOf(0)}
        var latest by remember{mutableStateOf<Int?>(null)}
        var scores by remember{mutableStateOf(emptyList<LiveScoreEntity>())}
        var history by remember{mutableStateOf(emptyList<QueueHistoryRow>())}
        var trades by remember{mutableStateOf(emptyList<PaperTradeEntity>())}

        var section by remember{mutableIntStateOf(0)}
        var searchText by remember{mutableStateOf("")}
        var searchResults by remember{mutableStateOf(emptyList<SymbolEntity>())}
        var selectedSymbol by remember{mutableStateOf<SymbolEntity?>(null)}
        var selectedSignals by remember{mutableStateOf(emptyList<SymbolSignalRow>())}
        var selectedPreQueue by remember{mutableStateOf(emptyList<PreQueueSnapshotRow>())}
        var selectedStats by remember{mutableStateOf<SymbolDetailStats?>(null)}
        var liveEnabled by remember{mutableStateOf(false)}
        var lastLiveScan by remember{mutableStateOf<Long?>(null)}
        var showMarkets by remember{mutableStateOf(false)}

        var syncStatus by remember{mutableStateOf("آماده")}
        var syncDone by remember{mutableStateOf(0)}
        var syncTotal by remember{mutableStateOf(0)}
        var analysisStatus by remember{mutableStateOf("آماده")}
        var analysisDone by remember{mutableStateOf(0)}
        var analysisTotal by remember{mutableStateOf(0)}
        var metadataStatus by remember{mutableStateOf("آماده")}
        var nextDayStatus by remember{mutableStateOf("آماده")}
        var nextDayDone by remember{mutableStateOf(0)}
        var nextDayTotal by remember{mutableStateOf(0)}
        var walkDone by remember{mutableStateOf(0)}
        var walkTotal by remember{mutableStateOf(0)}
        var catalogStatus by remember{mutableStateOf("در حال آماده‌سازی فهرست نمادها")}
        var catalogRaw by remember{mutableStateOf(0)}
        var catalogBourse by remember{mutableStateOf(0)}
        var catalogFarabourse by remember{mutableStateOf(0)}
        var catalogBase by remember{mutableStateOf(0)}
        var catalogLeveraged by remember{mutableStateOf(0)}
        var catalogUnknown by remember{mutableStateOf(0)}
        var catalogExcluded by remember{mutableStateOf(0)}

        var specialReopenCount by remember{mutableStateOf(0)}
        var preopenDay1Excluded by remember{mutableStateOf(0)}
        var twoDayQueueCount by remember{mutableStateOf(0)}
        var positiveContinuationCount by remember{mutableStateOf(0)}
        var strongPreopenNextDay by remember{mutableStateOf(0)}
        var fragileQueueCount by remember{mutableStateOf(0)}
        var avgPersistence by remember{mutableStateOf(0.0)}
        var avgQueueDuration by remember{mutableStateOf(0.0)}
        var learnedKnown by remember{mutableStateOf(0)}
        var learnedSuccess by remember{mutableStateOf(0)}
        var learnedRate by remember{mutableStateOf(0f)}
        var learnedBestBucket by remember{mutableStateOf("داده کافی نیست")}
        var learnedBestBucketRate by remember{mutableStateOf(0f)}
        var learnedMedianTime by remember{mutableStateOf(0)}
        var learnedMedianQueueValue by remember{mutableStateOf(0L)}
        var learnedAvgScore by remember{mutableStateOf(0f)}
        var preQueueStatus by remember{mutableStateOf("آماده")}
        var detect30 by remember{mutableStateOf(0f)}
        var detect20 by remember{mutableStateOf(0f)}
        var detect15 by remember{mutableStateOf(0f)}
        var detect10 by remember{mutableStateOf(0f)}
        var detect5 by remember{mutableStateOf(0f)}
        var falsePositiveRate by remember{mutableStateOf(0f)}
        var precisionRate by remember{mutableStateOf(0f)}
        var preQueueSnapshots by remember{mutableStateOf(0)}

        LaunchedEffect(Unit){
            while(true){
                val segs=MarketPrefs.selectedSegments(this@MainActivity).toList()
                val types=MarketPrefs.selectedTypes(this@MainActivity).toList()
                symbols=app.db.dao().symbolCount()
                records=app.db.dao().dailyCount()
                val prefEligible=catalogPrefs.getInt("eligible_count",-1)
                eligibleCount=if(prefEligible>=0) prefEligible else app.db.dao().allSymbols().count{
                    val effectiveType=MarketPrefs.classifyType(
                        it.symbol,it.name,it.flow,it.boardTitle
                    )
                    val derivedSegment=MarketPrefs.classify(it.flow,it.boardTitle)
                    val effectiveSegment=if(derivedSegment==MarketPrefs.OTHER) it.segment else derivedSegment
                    val stockLike=
                        effectiveType==MarketPrefs.TYPE_STOCK ||
                        effectiveType==MarketPrefs.TYPE_BASE ||
                        (
                            effectiveType==MarketPrefs.TYPE_FUND &&
                            MarketPrefs.isLeveragedFund(it.symbol,it.name)
                        )
                    stockLike &&
                    effectiveSegment!=MarketPrefs.OTHER &&
                    MarketPrefs.selectedSegments(this@MainActivity).contains(effectiveSegment)
                }
                candidates=app.db.dao().candidateCount()
                confirmed=app.db.dao().confirmedCount()
                rejected=app.db.dao().rejectedCount()
                errors=app.db.dao().errorCount()
                latest=app.db.dao().latestMarketDate()
                scores=app.db.dao().topSignalScores()
                history=app.db.dao().confirmedHistoryFor(segs,types,5000)
                trades=app.db.dao().recentPaperTrades(100)
                specialReopenCount=app.db.dao().specialReopenCount()
                preopenDay1Excluded=app.db.dao().preopenDay1ExcludedCount()
                twoDayQueueCount=app.db.dao().twoDayQueueCount()
                positiveContinuationCount=app.db.dao().positiveContinuationCount()
                strongPreopenNextDay=app.db.dao().strongPreopenNextDayCount()
                fragileQueueCount=app.db.dao().fragileQueueCount()
                avgPersistence=app.db.dao().averagePersistenceRatio() ?: 0.0
                avgQueueDuration=app.db.dao().averageQueueDuration() ?: 0.0

                learnedKnown=queueLearningPrefs.getInt("total_known",0)
                learnedSuccess=queueLearningPrefs.getInt("success_count",0)
                learnedRate=queueLearningPrefs.getFloat("success_rate",0f)
                learnedBestBucket=queueLearningPrefs.getString("best_bucket","داده کافی نیست")?:"داده کافی نیست"
                learnedBestBucketRate=queueLearningPrefs.getFloat("best_bucket_rate",0f)
                learnedMedianTime=queueLearningPrefs.getInt("median_success_time",0)
                learnedMedianQueueValue=queueLearningPrefs.getLong("median_success_queue_value",0L)
                learnedAvgScore=queueLearningPrefs.getFloat("avg_success_score",0f)

                preQueueStatus=preQueuePrefs.getString("status","آماده")?:"آماده"
                detect30=preQueuePrefs.getFloat("rate_30",0f)
                detect20=preQueuePrefs.getFloat("rate_20",0f)
                detect15=preQueuePrefs.getFloat("rate_15",0f)
                detect10=preQueuePrefs.getFloat("rate_10",0f)
                detect5=preQueuePrefs.getFloat("rate_5",0f)
                falsePositiveRate=preQueuePrefs.getFloat("false_positive_rate",0f)
                precisionRate=preQueuePrefs.getFloat("precision",0f)
                preQueueSnapshots=preQueuePrefs.getInt("snapshot_count",0)

                syncStatus=syncPrefs.getString("sync_status","آماده")?:"آماده"
                syncDone=syncPrefs.getInt("sync_done",0)
                syncTotal=syncPrefs.getInt("sync_total",0)
                analysisStatus=analysisPrefs.getString("analysis_status","آماده")?:"آماده"
                analysisDone=analysisPrefs.getInt("analysis_batch_done",0)
                analysisTotal=analysisPrefs.getInt("analysis_batch_total",0)
                metadataStatus=metaPrefs.getString("status","آماده")?:"آماده"
                nextDayStatus=nextPrefs.getString("status","آماده")?:"آماده"
                nextDayDone=nextPrefs.getInt("done",0)
                nextDayTotal=nextPrefs.getInt("total",0)
                walkDone=preQueuePrefs.getInt("events_done",0)
                walkTotal=preQueuePrefs.getInt("events_total",0)
                catalogStatus=catalogPrefs.getString("status","در حال آماده‌سازی فهرست نمادها")?:"در حال آماده‌سازی فهرست نمادها"
                catalogRaw=catalogPrefs.getInt("raw_count",0)
                catalogBourse=catalogPrefs.getInt("bourse_count",0)
                catalogFarabourse=catalogPrefs.getInt("farabourse_count",0)
                catalogBase=catalogPrefs.getInt("base_count",0)
                catalogLeveraged=catalogPrefs.getInt("leveraged_count",0)
                catalogUnknown=catalogPrefs.getInt("unknown_count",0)
                catalogExcluded=catalogPrefs.getInt("excluded_count",0)
                delay(1200)
            }
        }

        LaunchedEffect(searchText){
            if(searchText.trim().isNotEmpty()){
                delay(250); searchResults=app.db.dao().searchSymbols(searchText.trim(),30)
            }else searchResults=emptyList()
        }
        LaunchedEffect(selectedSymbol?.insCode){
            val s=selectedSymbol
            if(s==null){
                selectedSignals=emptyList()
                selectedPreQueue=emptyList()
                selectedStats=null
            }else{
                selectedSignals=runCatching{
                    app.db.dao().signalHistoryForSymbol(s.insCode,250)
                }.getOrDefault(emptyList())
                selectedPreQueue=runCatching{
                    app.db.dao().preQueueTimelineForSymbol(s.insCode,500)
                }.getOrDefault(emptyList())
                selectedStats=runCatching{
                    app.db.dao().symbolDetailStats(s.insCode)
                }.getOrNull()
            }
        }
        BackHandler(enabled=true){
            when{
                selectedSymbol!=null -> selectedSymbol=null
                section!=0 -> section=0
                else -> finish()
            }
        }

        LaunchedEffect(liveEnabled){
            while(liveEnabled){
                try{
                    LiveScanEngine.scanOnce(this@MainActivity)
                    lastLiveScan=System.currentTimeMillis()
                }catch(_:Exception){}
                delay(5000)
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){
            Scaffold(
                containerColor=Color(0xFFF6F7FB),
                topBar={
                    Surface(
                        modifier=Modifier.statusBarsPadding(),
                        color=Color.White,
                        shadowElevation=1.dp
                    ){
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(horizontal=16.dp,vertical=11.dp),
                            horizontalArrangement=Arrangement.SpaceBetween,
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Column(horizontalAlignment=Alignment.End){
                                Text(
                                    when(section){
                                        0 -> "سیگنال روزانه"
                                        1 -> "بک‌تست روزانه"
                                        2 -> "جستجوی نماد"
                                        3 -> "استخراج داده"
                                        4 -> "پیپر تریدینگ"
                                        else -> "تنظیمات پیشرفته"
                                    },
                                    fontSize=19.sp,
                                    fontWeight=FontWeight.Black,
                                    textAlign=TextAlign.Right
                                )
                                Text(
                                    "Signal • v2.8.5-safe",
                                    fontSize=10.sp,
                                    color=Color(0xFF777A88)
                                )
                            }
                            Row(
                                horizontalArrangement=Arrangement.spacedBy(8.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ){
                                TextButton(onClick={section=5}){
                                    Text("⚙",fontSize=20.sp)
                                }
                                Surface(
                                    color=Color(0xFFF0EBFF),
                                    shape=RoundedCornerShape(13.dp)
                                ){
                                    Text(
                                        "S",
                                        Modifier.padding(horizontal=12.dp,vertical=7.dp),
                                        color=MaterialTheme.colorScheme.primary,
                                        fontWeight=FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                },
                bottomBar={
                    NavigationBar(
                        containerColor=Color.White,
                        tonalElevation=5.dp
                    ){
                        NavigationBarItem(
                            selected=section==0,
                            onClick={section=0},
                            icon={Text("↗",fontSize=20.sp)},
                            label={Text("سیگنال",fontSize=9.sp)}
                        )
                        NavigationBarItem(
                            selected=section==3,
                            onClick={section=3},
                            icon={Text("▤",fontSize=20.sp)},
                            label={Text("استخراج",fontSize=9.sp)}
                        )
                        NavigationBarItem(
                            selected=section==1,
                            onClick={section=1},
                            icon={Text("◫",fontSize=20.sp)},
                            label={Text("بک‌تست",fontSize=9.sp)}
                        )
                        NavigationBarItem(
                            selected=section==4,
                            onClick={section=4},
                            icon={Text("◔",fontSize=20.sp)},
                            label={Text("پیپر",fontSize=9.sp)}
                        )
                        NavigationBarItem(
                            selected=section==2,
                            onClick={section=2},
                            icon={Text("⌕",fontSize=20.sp)},
                            label={Text("جستجو",fontSize=9.sp)}
                        )
                    }
                }
            ){padding->
                Box(
                    Modifier.fillMaxSize()
                        .padding(padding)
                        .padding(horizontal=12.dp)
                ){
                    when(section){
                        0 -> DailySignals(scores,liveEnabled,{liveEnabled=it},lastLiveScan)
                        1 -> DailyBacktest(
                            history=history,
                            specialReopenCount=specialReopenCount,
                            preopenDay1Excluded=preopenDay1Excluded,
                            twoDayQueueCount=twoDayQueueCount,
                            positiveContinuationCount=positiveContinuationCount,
                            strongPreopenNextDay=strongPreopenNextDay,
                            fragileQueueCount=fragileQueueCount,
                            avgPersistence=avgPersistence,
                            avgQueueDuration=avgQueueDuration,
                            learnedKnown=learnedKnown,
                            learnedSuccess=learnedSuccess,
                            learnedRate=learnedRate,
                            bestBucket=learnedBestBucket,
                            bestBucketRate=learnedBestBucketRate,
                            medianSuccessTime=learnedMedianTime,
                            medianSuccessQueueValue=learnedMedianQueueValue,
                            avgSuccessScore=learnedAvgScore,
                            preQueueStatus=preQueueStatus,
                            detect30=detect30,
                            detect20=detect20,
                            detect15=detect15,
                            detect10=detect10,
                            detect5=detect5,
                            falsePositiveRate=falsePositiveRate,
                            precisionRate=precisionRate,
                            preQueueSnapshots=preQueueSnapshots
                        )
                        2 -> SymbolSearchPage(
                            searchText,{searchText=it},searchResults,
                            selectedSymbol,selectedSignals,selectedPreQueue,selectedStats,
                            catalogStatus,
                            {selectedSymbol=it},
                            {
                                selectedSymbol=null
                                searchText=""
                                searchResults=emptyList()
                            }
                        )
                        3 -> DataExtractionPage(
                            eligibleCount,catalogRaw,catalogBourse,catalogFarabourse,
                            catalogBase,catalogLeveraged,catalogUnknown,catalogExcluded,
                            syncStatus,syncDone,syncTotal,
                            metadataStatus,catalogStatus,
                            analysisStatus,analysisDone,analysisTotal,
                            nextDayStatus,nextDayDone,nextDayTotal,
                            preQueueStatus,walkDone,walkTotal,
                            candidates,confirmed,preopenDay1Excluded,
                            fragileQueueCount,rejected,specialReopenCount,errors,
                            onMarkets={showMarkets=true},
                            onSymbolsUpdate={refreshSymbolCatalog()},
                            onStart={mode->
                                saveExtractionSelection(if(mode=="QUICK") 1 else 5)
                                startUpdate(mode)
                            },
                            onAnalyze={startAnalyze()}
                        )
                        4 -> PaperTrades(trades)
                        else -> SettingsPage(
                            onMarkets={showMarkets=true},
                            onSymbolsUpdate={refreshSymbolCatalog()},
                            onUpdate={startUpdate()}
                        )
                    }
                }
            }
        }

        if(showMarkets){
            MarketDialog(
                initialTypes=MarketPrefs.selectedTypes(this),
                initialSegments=MarketPrefs.selectedSegments(this),
                onDismiss={showMarkets=false},
                onSave={types,segments->
                    MarketPrefs.saveFilters(this,types,segments)
                    showMarkets=false
                }
            )
        }
    }


    @Composable
    private fun SignalSidebar(
        compact:Boolean,
        section:Int,
        onSection:(Int)->Unit,
        onSettings:()->Unit
    ){
        val items=listOf(
            Triple(0,"◎","سیگنال روزانه"),
            Triple(3,"▤","استخراج داده"),
            Triple(1,"◫","بک‌تست روزانه"),
            Triple(4,"◔","پیپر تریدینگ"),
            Triple(2,"⌕","جستجو")
        )

        Column(
            Modifier.fillMaxSize()
                .padding(horizontal=if(compact) 9.dp else 14.dp,vertical=18.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ){
            Surface(
                color=Color(0xFF141A2C),
                shape=RoundedCornerShape(22.dp),
                border=BorderStroke(1.dp,Color(0xFF2B3650))
            ){
                Box(
                    Modifier.size(if(compact) 64.dp else 82.dp),
                    contentAlignment=Alignment.Center
                ){
                    Text(
                        "↗",
                        fontSize=if(compact) 36.sp else 44.sp,
                        fontWeight=FontWeight.Black,
                        color=Color(0xFF24D4BE)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Signal",
                color=Color.White,
                fontSize=if(compact) 22.sp else 28.sp,
                fontWeight=FontWeight.Black
            )
            Text(
                "سیگنال هوشمند بورس",
                color=Color(0xFFB4B8C8),
                fontSize=if(compact) 8.sp else 10.sp,
                textAlign=TextAlign.Center
            )

            Spacer(Modifier.height(22.dp))

            items.forEach{(id,icon,label)->
                SidebarItem(
                    compact=compact,
                    selected=section==id,
                    icon=icon,
                    label=label,
                    onClick={onSection(id)}
                )
                Spacer(Modifier.height(6.dp))
            }

            SidebarItem(
                compact=compact,
                selected=section==5,
                icon="⚙",
                label="تنظیمات پیشرفته",
                onClick=onSettings
            )

            Spacer(Modifier.weight(1f))

            Surface(
                color=Color(0xFF151B2D),
                shape=RoundedCornerShape(14.dp),
                border=BorderStroke(1.dp,Color(0xFF33405B))
            ){
                Column(
                    Modifier.padding(horizontal=10.dp,vertical=8.dp),
                    horizontalAlignment=Alignment.CenterHorizontally
                ){
                    Text(
                        "v2.8.5-safe",
                        color=Color(0xFF25D5C0),
                        fontWeight=FontWeight.Bold,
                        fontSize=if(compact) 9.sp else 11.sp
                    )
                    Text(
                        "نسخه تست",
                        color=Color(0xFFB8BDCB),
                        fontSize=if(compact) 7.sp else 9.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun SidebarItem(
        compact:Boolean,
        selected:Boolean,
        icon:String,
        label:String,
        onClick:()->Unit
    ){
        Surface(
            modifier=Modifier.fillMaxWidth().clickable(onClick=onClick),
            color=if(selected) Color(0xFF211C3E) else Color.Transparent,
            shape=RoundedCornerShape(14.dp)
        ){
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal=8.dp,vertical=9.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=if(compact) Arrangement.Center else Arrangement.Start
            ){
                Text(
                    icon,
                    fontSize=if(compact) 20.sp else 22.sp,
                    color=if(selected) Color(0xFF2AD8C1) else Color(0xFF9DA4B8)
                )
                if(!compact){
                    Spacer(Modifier.width(8.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){
                        Text(
                            label,
                            modifier=Modifier.weight(1f),
                            color=if(selected) Color.White else Color(0xFFD8DBE4),
                            fontSize=11.sp,
                            fontWeight=if(selected) FontWeight.Bold else FontWeight.Normal,
                            textAlign=TextAlign.Right
                        )
                    }
                }
            }
        }
        if(compact){
            Text(
                label,
                color=if(selected) Color.White else Color(0xFFAEB4C5),
                fontSize=7.sp,
                textAlign=TextAlign.Center,
                modifier=Modifier.fillMaxWidth().padding(top=2.dp)
            )
        }
    }

    @Composable
    private fun DailySignals(
        scores:List<LiveScoreEntity>,
        liveEnabled:Boolean,
        onLiveToggle:(Boolean)->Unit,
        lastLiveScan:Long?
    ){
        var filter by remember{mutableIntStateOf(0)}
        var nowTick by remember{mutableLongStateOf(System.currentTimeMillis())}
        LaunchedEffect(Unit){
            while(true){
                nowTick=System.currentTimeMillis()
                delay(1000)
            }
        }
        val currentGregorian=remember(nowTick){todayGregorianInt(nowTick)}
        val currentJalali=remember(nowTick){Jalali.fromGregorianInt(currentGregorian)}
        val currentClock=remember(nowTick){clock(nowTick)}
        val currentDay=remember(nowTick){persianDayName(nowTick)}
        val all=scores.sortedByDescending{it.score}
        val visible=when(filter){
            1 -> all.filter{it.score>=80}
            2 -> all.filter{it.score in 65.0..79.999}
            3 -> all.filter{it.score<65}
            else -> all
        }
        val avg=if(all.isEmpty()) 0 else all.map{it.score}.average().toInt()

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=14.dp)
        ){
            item{
                PolishedCard{
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column{
                            Text(
                                "$currentDay • $currentJalali",
                                fontSize=12.sp,
                                fontWeight=FontWeight.Black
                            )
                            Text(
                                marketPhase(nowTick),
                                fontSize=10.sp,
                                color=Color(0xFF777A86)
                            )
                        }
                        Text(
                            currentClock,
                            fontSize=23.sp,
                            fontWeight=FontWeight.Black,
                            color=MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            item{
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ){
                    SummaryTile(
                        title="تعداد سیگنال‌ها",
                        value=fa(all.size),
                        bg=Color(0xFFE8F7F3),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="میانگین امتیاز",
                        value=fa(avg),
                        bg=Color(0xFFEAF2FF),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="آخرین بروزرسانی",
                        value=if(lastLiveScan!=null) clock(lastLiveScan) else "—",
                        bg=Color(0xFFF1E9FF),
                        modifier=Modifier.weight(1f)
                    )
                }
            }

            item{
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(6.dp),
                    verticalAlignment=Alignment.CenterVertically
                ){
                    SignalFilterChip(filter==0,{filter=0},"همه (${fa(all.size)})")
                    SignalFilterChip(filter==1,{filter=1},"قوی (${fa(all.count{it.score>=80})})")
                    SignalFilterChip(filter==2,{filter=2},"متوسط (${fa(all.count{it.score in 65.0..79.999})})")
                    SignalFilterChip(filter==3,{filter=3},"ضعیف (${fa(all.count{it.score<65})})")
                }
            }

            item{
                Card(
                    shape=RoundedCornerShape(18.dp),
                    colors=CardDefaults.cardColors(containerColor=Color(0xFFF9FAFD)),
                    border=BorderStroke(1.dp,Color(0xFFE5E7EF))
                ){
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column{
                            Text(
                                "اسکن و سیگنال فقط در بازه 09:00 تا 12:30",
                                fontSize=11.sp,
                                color=Color(0xFF6F7280)
                            )
                            Text(
                                if(liveEnabled) "رصد زنده فعال" else "رصد زنده خاموش",
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold,
                                color=if(liveEnabled) Color(0xFF168D68) else Color(0xFF8B8D98)
                            )
                        }
                        Switch(checked=liveEnabled,onCheckedChange=onLiveToggle)
                    }
                }
            }

            if(visible.isEmpty()){
                item{
                    Card(
                        shape=RoundedCornerShape(20.dp),
                        colors=CardDefaults.cardColors(containerColor=Color.White)
                    ){
                        Text(
                            "فعلاً سیگنالی در این فیلتر وجود ندارد.",
                            Modifier.fillMaxWidth().padding(22.dp),
                            textAlign=TextAlign.Center,
                            color=Color(0xFF777A87)
                        )
                    }
                }
            }else{
                items(visible){s->
                    SignalCard(s)
                }
            }

            item{
                Text(
                    "این اطلاعات صرفاً جهت تحلیل بوده و تایید قطعی خرید یا فروش نیست.",
                    modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),
                    textAlign=TextAlign.Center,
                    fontSize=10.sp,
                    color=Color(0xFF8C8E98)
                )
            }
        }
    }

    @Composable
    private fun SummaryTile(
        title:String,
        value:String,
        bg:Color,
        modifier:Modifier=Modifier
    ){
        Card(
            modifier=modifier.height(116.dp),
            shape=RoundedCornerShape(18.dp),
            colors=CardDefaults.cardColors(containerColor=bg)
        ){
            Column(
                Modifier.fillMaxSize().padding(10.dp),
                horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.Center
            ){
                Text(title,fontSize=10.sp,color=Color(0xFF555968),textAlign=TextAlign.Center)
                Spacer(Modifier.height(7.dp))
                Text(value,fontSize=22.sp,fontWeight=FontWeight.Black,color=Color(0xFF171927))
            }
        }
    }

    @Composable
    private fun SignalFilterChip(
        selected:Boolean,
        onClick:()->Unit,
        text:String
    ){
        FilterChip(
            selected=selected,
            onClick=onClick,
            label={Text(text,fontSize=10.sp)},
            colors=FilterChipDefaults.filterChipColors(
                selectedContainerColor=MaterialTheme.colorScheme.primary,
                selectedLabelColor=Color.White,
                containerColor=Color.White
            ),
            border=FilterChipDefaults.filterChipBorder(
                enabled=true,
                selected=selected,
                borderColor=Color(0xFFE1E3EA),
                selectedBorderColor=MaterialTheme.colorScheme.primary
            )
        )
    }

    @Composable
    private fun SignalCard(s:LiveScoreEntity){
        val score=s.score.toInt()
        val strong=score>=80
        val medium=score>=65
        val badge=when{
            strong -> Color(0xFFDFF5E8)
            medium -> Color(0xFFFFF0D9)
            else -> Color(0xFFF2E8E8)
        }
        val badgeText=when{
            strong -> Color(0xFF118658)
            medium -> Color(0xFFD67A00)
            else -> Color(0xFFA85A5A)
        }

        Card(
            modifier=Modifier.fillMaxWidth(),
            shape=RoundedCornerShape(20.dp),
            colors=CardDefaults.cardColors(containerColor=Color.White),
            border=BorderStroke(1.dp,Color(0xFFE4E6ED))
        ){
            Row(
                Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=13.dp),
                verticalAlignment=Alignment.CenterVertically
            ){
                Column(Modifier.weight(1.2f)){
                    Text(
                        s.symbol?:"در حال تکمیل نام",
                        fontSize=17.sp,
                        fontWeight=FontWeight.Black,
                        color=Color(0xFF1C1E29)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when{
                            strong -> "سیگنال قوی"
                            medium -> "سیگنال متوسط"
                            else -> "تحت نظر"
                        },
                        fontSize=10.sp,
                        color=Color(0xFF777A86)
                    )
                }

                Column(
                    Modifier.weight(.8f),
                    horizontalAlignment=Alignment.CenterHorizontally
                ){
                    Surface(
                        shape=RoundedCornerShape(10.dp),
                        color=badge
                    ){
                        Text(
                            fa(score),
                            Modifier.padding(horizontal=10.dp,vertical=5.dp),
                            color=badgeText,
                            fontWeight=FontWeight.Bold
                        )
                    }
                    Text("امتیاز",fontSize=9.sp,color=Color.Gray)
                }

                Column(
                    Modifier.weight(.9f),
                    horizontalAlignment=Alignment.CenterHorizontally
                ){
                    Text(clock(s.updatedAt),fontSize=13.sp,fontWeight=FontWeight.Bold)
                    Text("زمان سیگنال",fontSize=9.sp,color=Color.Gray)
                }

                Text(
                    if(strong)"▲" else if(medium)"●" else "•",
                    color=if(strong) Color(0xFF159A63) else if(medium) Color(0xFFE28B14) else Color(0xFF9A9CA6),
                    fontSize=15.sp
                )
            }
        }
    }

    @Composable
    private fun DailyBacktest(
        history:List<QueueHistoryRow>,
        specialReopenCount:Int,
        preopenDay1Excluded:Int,
        twoDayQueueCount:Int,
        positiveContinuationCount:Int,
        strongPreopenNextDay:Int,
        fragileQueueCount:Int,
        avgPersistence:Double,
        avgQueueDuration:Double,
        learnedKnown:Int,
        learnedSuccess:Int,
        learnedRate:Float,
        bestBucket:String,
        bestBucketRate:Float,
        medianSuccessTime:Int,
        medianSuccessQueueValue:Long,
        avgSuccessScore:Float,
        preQueueStatus:String,
        detect30:Float,detect20:Float,detect15:Float,detect10:Float,detect5:Float,
        falsePositiveRate:Float,
        precisionRate:Float,
        preQueueSnapshots:Int
    ){
        var onlyContinuation by remember{mutableStateOf(true)}
        val continuation=history.filter{
            it.nextDayQueueStatus in setOf(
                "PREOPEN_QUEUE_NEXT_DAY","QUEUE_AGAIN",
                "POSITIVE_STRONG_NEXT_DAY","POSITIVE_NEXT_DAY"
            )
        }
        val visible=if(onlyContinuation) continuation else history
        val grouped=visible.groupBy{it.date}.toSortedMap(compareByDescending{it})

        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(12.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
        ){
            item{
                PageHero(
                    eyebrow="QUEUE PATTERN",
                    title="پیش‌بینی صف خرید پایدار",
                    subtitle="فقط صف‌های ادامه‌دار روز اول؛ نتیجه مثبت روز کاری بعد داخل همان تحلیل است"
                )
            }

            item{
                PolishedCard{
                    Text("Walk-Forward قبل از صف",fontSize=17.sp,fontWeight=FontWeight.Black)
                    Text(
                        preQueueStatus,
                        fontSize=10.sp,
                        color=Color(0xFF777A86)
                    )
                    Text(
                        "Walk-Forward واقعی: شبیه‌سازی از ۰۹:۰۰ رو‌به‌جلو حرکت می‌کند. در هر لحظه فقط داده همان لحظه و قبل از آن قابل مشاهده است؛ زمان صف، سقف نهایی روز و نتیجه روز بعد هیچ‌کدام وارد امتیاز همان لحظه نمی‌شوند.",
                        fontSize=10.sp,
                        color=Color(0xFF777A86)
                    )
                    Text(
                        "صف‌های قبل از ۰۹:۰۰ جدا هستند و موفقیت پیش‌بینی محسوب نمی‌شوند. نتیجه روز بعد فقط بعداً برای ارزیابی کیفیت سیگنال استفاده می‌شود.",
                        fontSize=9.sp,
                        color=Color(0xFF118658)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(5.dp)
                    ){
                        MetricPill("هشدار ۳۰دقیقه قبل","${fa((detect30*100).toInt())}٪",Modifier.weight(1f))
                        MetricPill("۲۰دقیقه قبل","${fa((detect20*100).toInt())}٪",Modifier.weight(1f))
                        MetricPill("۱۵دقیقه قبل","${fa((detect15*100).toInt())}٪",Modifier.weight(1f))
                        MetricPill("۱۰دقیقه قبل","${fa((detect10*100).toInt())}٪",Modifier.weight(1f))
                        MetricPill("۵دقیقه قبل","${fa((detect5*100).toInt())}٪",Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(7.dp)
                    ){
                        MetricPill(
                            "نمونه‌های زمانی",
                            fa(preQueueSnapshots),
                            Modifier.weight(1f)
                        )
                        MetricPill(
                            "Precision",
                            "${fa((precisionRate*100).toInt())}٪",
                            Modifier.weight(1f)
                        )
                        MetricPill(
                            "False Positive",
                            "${fa((falsePositiveRate*100).toInt())}٪",
                            Modifier.weight(1f)
                        )
                    }
                }
            }

            item{
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ){
                    SummaryTile(
                        title="ادامه مثبت روز بعد",
                        value=fa(positiveContinuationCount),
                        bg=Color(0xFFE5F7EC),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="نمونه نتیجه‌دار",
                        value=fa(learnedKnown),
                        bg=Color(0xFFEAF2FF),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="نتیجه مثبت روز بعد",
                        value=if(learnedKnown>0)
                            "${fa((learnedRate*100).toInt())}٪"
                        else "—",
                        bg=Color(0xFFF2ECFF),
                        modifier=Modifier.weight(1f)
                    )
                }
            }

            item{
                PolishedCard{
                    Text("کیفیت صف روز سیگنال",fontSize=16.sp,fontWeight=FontWeight.Black)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(7.dp)
                    ){
                        MetricPill(
                            "میانگین دوام",
                            "${fa(avgQueueDuration.toInt())} دقیقه",
                            Modifier.weight(1f)
                        )
                        MetricPill(
                            "پایداری",
                            "${fa((avgPersistence*100).toInt())}٪",
                            Modifier.weight(1f)
                        )
                        MetricPill(
                            "صف شکننده حذف‌شده",
                            fa(fragileQueueCount),
                            Modifier.weight(1f)
                        )
                    }
                    Text(
                        "صف لحظه‌ای یا شکننده دیگر نمونه مثبت مدل نیست؛ فقط صفی وارد یادگیری می‌شود که بعد از تشکیل، دوام قابل‌قبول داشته باشد.",
                        fontSize=10.sp,color=Color(0xFF747785)
                    )
                }
            }

            item{
                PolishedCard{
                    Text("الگوی استخراج‌شده",fontSize=17.sp,fontWeight=FontWeight.Black)
                    Text(
                        if(learnedKnown>=10)
                            "بر اساس ${fa(learnedKnown)} صف خرید معتبر که نتیجه روز معاملاتی بعدشان مشخص شده است."
                        else
                            "هنوز نمونه کافی برای الگوی قابل اتکا جمع نشده است.",
                        fontSize=11.sp,
                        color=Color(0xFF737684)
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(7.dp)
                    ){
                        MetricPill(
                            "بهترین بازه",
                            bestBucket,
                            Modifier.weight(1f)
                        )
                        MetricPill(
                            "موفقیت بازه",
                            if(bestBucketRate>0f) "${fa((bestBucketRate*100).toInt())}٪" else "—",
                            Modifier.weight(1f)
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(7.dp)
                    ){
                        MetricPill(
                            "میانه شروع صف",
                            if(medianSuccessTime>0) fmtTime(medianSuccessTime) else "—",
                            Modifier.weight(1f)
                        )
                        MetricPill(
                            "میانه ارزش صف",
                            if(medianSuccessQueueValue>0)
                                "${fa((medianSuccessQueueValue/1_000_000_000L).toInt())} میلیارد"
                            else "—",
                            Modifier.weight(1f)
                        )
                        MetricPill(
                            "میانگین امتیاز",
                            if(avgSuccessScore>0f) fa(avgSuccessScore.toInt()) else "—",
                            Modifier.weight(1f)
                        )
                    }

                    Surface(
                        color=Color(0xFFFFF6E3),
                        shape=RoundedCornerShape(13.dp)
                    ){
                        Text(
                            "${fa(specialReopenCount)} رخداد بازگشایی ویژه/بدون دامنه از مدل اصلی کنار گذاشته شده‌اند.",
                            Modifier.fillMaxWidth().padding(10.dp),
                            fontSize=10.sp,
                            color=Color(0xFF7B6415)
                        )
                    }
                }
            }

            item{
                PolishedCard{
                    Text("پاکسازی مدل",fontSize=15.sp,fontWeight=FontWeight.Black)
                    Text(
                        "${fa(preopenDay1Excluded)} روز به‌خاطر صف قبل از ۹ در روز سیگنال حذف شده‌اند.",
                        fontSize=11.sp,color=Color(0xFF747785)
                    )
                    Text(
                        "${fa(specialReopenCount)} بازگشایی ویژه/بدون دامنه نیز از مدل اصلی حذف شده‌اند.",
                        fontSize=11.sp,color=Color(0xFF747785)
                    )
                    Text(
                        "${fa(strongPreopenNextDay)} موفقیت خیلی قوی: سهم روز بعد از پیش‌گشایش صف خرید بوده است.",
                        fontSize=11.sp,color=Color(0xFF118658),fontWeight=FontWeight.Bold
                    )
                }
            }

            item{
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ){
                    FilterChip(
                        selected=onlyContinuation,
                        onClick={onlyContinuation=true},
                        label={Text("ادامه مثبت روز بعد (${fa(continuation.size)})")},
                        modifier=Modifier.weight(1f)
                    )
                    FilterChip(
                        selected=!onlyContinuation,
                        onClick={onlyContinuation=false},
                        label={Text("همه صف‌های معتبر (${fa(history.size)})")},
                        modifier=Modifier.weight(1f)
                    )
                }
            }

            if(grouped.isEmpty()){
                item{
                    PolishedEmpty(
                        if(onlyContinuation)
                            "هنوز سیگنال پایدار با نتیجه مثبت در روز کاری بعد پیدا نشده است."
                        else
                            "هنوز نتیجه تحلیل صف‌های معتبر ثبت نشده است."
                    )
                }
            }else{
                grouped.forEach{(date,rows)->
                    item{
                        Card(
                            shape=RoundedCornerShape(22.dp),
                            colors=CardDefaults.cardColors(containerColor=Color.White),
                            border=BorderStroke(1.dp,Color(0xFFE6E8F0))
                        ){
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement=Arrangement.spacedBy(10.dp)
                            ){
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement=Arrangement.SpaceBetween,
                                    verticalAlignment=Alignment.CenterVertically
                                ){
                                    Text(
                                        Jalali.fromGregorianInt(date),
                                        fontSize=18.sp,
                                        fontWeight=FontWeight.Black
                                    )
                                    Surface(
                                        color=if(onlyContinuation) Color(0xFFE4F6EA) else Color(0xFFF0EBFF),
                                        shape=RoundedCornerShape(12.dp)
                                    ){
                                        Text(
                                            "${fa(rows.size)} نماد",
                                            Modifier.padding(horizontal=10.dp,vertical=5.dp),
                                            color=if(onlyContinuation) Color(0xFF118658) else MaterialTheme.colorScheme.primary,
                                            fontSize=11.sp,
                                            fontWeight=FontWeight.Bold
                                        )
                                    }
                                }

                                rows.forEach{s->
                                    Surface(
                                        color=Color(0xFFF9FAFC),
                                        shape=RoundedCornerShape(16.dp)
                                    ){
                                        Column(
                                            Modifier.fillMaxWidth().padding(12.dp),
                                            verticalArrangement=Arrangement.spacedBy(5.dp)
                                        ){
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement=Arrangement.SpaceBetween
                                            ){
                                                Text(
                                                    s.symbol?:"در حال تکمیل نام",
                                                    fontWeight=FontWeight.Black,
                                                    fontSize=16.sp
                                                )
                                                Text(
                                                    "${fa(s.score.toInt())}/۱۰۰",
                                                    color=MaterialTheme.colorScheme.primary,
                                                    fontWeight=FontWeight.Black
                                                )
                                            }
                                            Text(
                                                "شروع صف: ${fmtTime(s.signalTime)}  •  ارزش بیشینه صف: ${
                                                    if((s.queueValue?:0.0)>0)
                                                        fa(((s.queueValue?:0.0)/1_000_000_000.0).toInt())+" میلیارد"
                                                    else "—"
                                                }",
                                                fontSize=11.sp,
                                                color=Color(0xFF727583)
                                            )
                                            Text(
                                                "دوام ${fa(s.queueDurationMinutes)} دقیقه • پایداری ${fa((s.queuePersistenceRatio*100).toInt())}٪ • شکست ${fa(s.queueBreakCount)} بار" +
                                                    if(s.queueEndHeld) " • پایان بازار: صف حفظ شد" else "",
                                                fontSize=10.sp,
                                                color=Color(0xFF666A78)
                                            )
                                            s.nextDayReturnPct?.let{ret->
                                                Text(
                                                    "بازده روز کاری بعد: ${if(ret>=0) "+" else ""}${Jalali.digits(String.format(Locale.US,"%.2f",ret))}٪",
                                                    fontSize=10.sp,
                                                    color=if(ret>=0) Color(0xFF118658) else Color(0xFFB85A5A)
                                                )
                                            }
                                            Text(
                                                when(s.nextDayQueueStatus){
                                                    "PREOPEN_QUEUE_NEXT_DAY" -> "روز بعد از پیش‌گشایش صف خرید بود ★ موفقیت خیلی قوی"
                                                    "QUEUE_AGAIN" -> "روز معاملاتی بعد در تایم عادی صف خرید شد ✓"
                                                    "POSITIVE_STRONG_NEXT_DAY" -> "روز کاری بعد مثبت قوی بود ✓"
                                                    "POSITIVE_NEXT_DAY" -> "روز کاری بعد مثبت بود ✓"
                                                    "FLAT_NEXT_DAY" -> "روز کاری بعد خنثی بود"
                                                    "NEGATIVE_NEXT_DAY" -> "روز کاری بعد منفی بود"
                                                    "NOT_QUEUE_NEXT_DAY" -> "روز معاملاتی بعد صف خرید نشد"
                                                    "NEXT_DAY_SPECIAL_REOPEN" -> "روز بعد بازگشایی ویژه بود؛ از الگو حذف شد"
                                                    "NO_NEXT_DAY" -> "داده روز معاملاتی بعد موجود نیست"
                                                    else -> "روز بعد هنوز بررسی نشده"
                                                },
                                                fontSize=11.sp,
                                                color=when(s.nextDayQueueStatus){
                                                    "PREOPEN_QUEUE_NEXT_DAY" -> Color(0xFF087A53)
                                                    "QUEUE_AGAIN" -> Color(0xFF118658)
                                                    "POSITIVE_STRONG_NEXT_DAY" -> Color(0xFF118658)
                                                    "POSITIVE_NEXT_DAY" -> Color(0xFF3A8C68)
                                                    "NEGATIVE_NEXT_DAY" -> Color(0xFFB85A5A)
                                                    "NOT_QUEUE_NEXT_DAY" -> Color(0xFFB85A5A)
                                                    "NEXT_DAY_SPECIAL_REOPEN" -> Color(0xFFD08B00)
                                                    else -> Color(0xFF777A87)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DataExtractionPage(
        eligibleCount:Int,
        rawCount:Int,bourseCount:Int,farabourseCount:Int,baseCount:Int,
        leveragedCount:Int,unknownCount:Int,excludedCount:Int,
        syncStatus:String,syncDone:Int,syncTotal:Int,metadataStatus:String,
        catalogStatus:String,
        analysisStatus:String,analysisDone:Int,analysisTotal:Int,
        nextDayStatus:String,nextDayDone:Int,nextDayTotal:Int,
        walkStatus:String,walkDone:Int,walkTotal:Int,
        candidatePending:Int,confirmedAfter9:Int,preopenExcluded:Int,
        fragileCount:Int,notQueueCount:Int,specialCount:Int,errorCount:Int,
        onMarkets:()->Unit,onSymbolsUpdate:()->Unit,
        onStart:(String)->Unit,onAnalyze:()->Unit
    ){
        var selectedMode by remember{mutableStateOf("QUICK")}
        var showConfirm by remember{mutableStateOf(false)}

        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(12.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
        ){
            item{
                PageHero(
                    eyebrow="DATASET",
                    title="استخراج داده",
                    subtitle="فقط سهام بورس، فرابورس، بازار پایه و صندوق‌های اهرمی؛ اختیار و درآمد ثابت حذف کامل"
                )
            }

            item{
                PolishedCard{
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column(Modifier.weight(1f)){
                            Text("فهرست نمادها",fontWeight=FontWeight.Black)
                            Text(catalogStatus,fontSize=11.sp,color=Color(0xFF747785))
                            if(metadataStatus!="آماده"){
                                Text(metadataStatus,fontSize=10.sp,color=MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                "خام ${fa(rawCount)} • حذف‌شده ${fa(excludedCount)}",
                                fontSize=10.sp,color=Color(0xFF91939D)
                            )
                        }
                        TextButton(onClick=onSymbolsUpdate){Text("به‌روزرسانی افزایشی")}
                    }
                    Text(
                        "نمادهای کامل دوباره پردازش نمی‌شوند؛ فقط موارد جدید یا ناقص بررسی می‌شوند.",
                        fontSize=9.sp,color=Color(0xFF118658)
                    )
                    Text(
                        "اختیار معامله و ابزارهای درآمد ثابت از همان ورودی حذف می‌شوند و وارد دیتابیس تحلیل/استخراج تاریخی نمی‌شوند.",
                        fontSize=9.sp,color=Color(0xFFB05B5B)
                    )
                    Text(
                        "موارد مبهم در مرحله سبک Metadata نگه داشته می‌شوند تا سهام واقعی اشتباهی حذف نشوند؛ تا زمان تأیید وارد استخراج سنگین نمی‌شوند.",
                        fontSize=9.sp,color=Color(0xFF777A86)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)
                    ){
                        MetricPill("بورس",fa(bourseCount),Modifier.weight(1f))
                        MetricPill("فرابورس",fa(farabourseCount),Modifier.weight(1f))
                        MetricPill("پایه",fa(baseCount),Modifier.weight(1f))
                        MetricPill("اهرمی",fa(leveragedCount),Modifier.weight(1f))
                    }
                    if(unknownCount>0){
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color=Color(0xFFFFF6DF),
                            shape=RoundedCornerShape(12.dp)
                        ){
                            Text(
                                "${fa(unknownCount)} مورد stock-like هنوز بازار قطعی ندارند؛ در قرنطینه متادیتا می‌مانند و تحلیل سنگین نمی‌شوند.",
                                Modifier.fillMaxWidth().padding(9.dp),
                                fontSize=10.sp,
                                color=Color(0xFF7B6517)
                            )
                        }
                    }
                }
            }

            item{
                PolishedCard{
                    Text("Audit رخدادهای صف",fontSize=17.sp,fontWeight=FontWeight.Black)
                    Text(
                        "عدد کل تحلیل، «کاندید روز معاملاتی» است؛ بعد از BestLimits مشخص می‌شود واقعاً صف بوده یا نه.",
                        fontSize=10.sp,color=Color(0xFF747785)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(5.dp)
                    ){
                        MetricPill("کل کاندید",fa(analysisTotal),Modifier.weight(1f))
                        MetricPill("باقی‌مانده",fa(candidatePending),Modifier.weight(1f))
                        MetricPill("صف بعد از ۹",fa(confirmedAfter9),Modifier.weight(1f))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(5.dp)
                    ){
                        MetricPill("صف قبل از ۹",fa(preopenExcluded),Modifier.weight(1f))
                        MetricPill("صف شکننده",fa(fragileCount),Modifier.weight(1f))
                        MetricPill("اصلاً صف نبود",fa(notQueueCount),Modifier.weight(1f))
                    }
                    Text(
                        "بازگشایی ویژه/بدون دامنه حذف‌شده: ${fa(specialCount)}",
                        fontSize=9.sp,color=Color(0xFFD08B00)
                    )
                    if(errorCount>0){
                        Text(
                            "خطای تحلیل: ${fa(errorCount)} رخداد؛ این موارد در تلاش بعدی دوباره بررسی می‌شوند.",
                            fontSize=9.sp,color=Color(0xFFB05B5B)
                        )
                    }
                    Text(
                        "Walk-Forward فقط روی صف‌های معتبر بعد از ۰۹:۰۰ و نمونه‌های منفی مناسب اجرا می‌شود؛ صف پیش‌گشایش وارد موفقیت اصلی مدل نمی‌شود.",
                        fontSize=9.sp,color=Color(0xFF118658)
                    )
                }
            }

            item{
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ){
                    SummaryTile(
                        title="Universe نهایی",
                        value=fa(eligibleCount),
                        bg=Color(0xFFE9F7F3),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="رکورد ذخیره‌شده",
                        value=if(syncDone>0) fa(syncDone) else "—",
                        bg=Color(0xFFEEF2FF),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="حالت استخراج",
                        value=if(selectedMode=="QUICK") "سریع ۱ سال" else "عمیق ۵ سال",
                        bg=Color(0xFFF2ECFF),
                        modifier=Modifier.weight(1f)
                    )
                }
            }

            item{
                PolishedCard{
                    Text("۱. محدوده بازار",fontSize=15.sp,fontWeight=FontWeight.Black)
                    Text(
                        "گروه‌های نامرتبط حذف شده‌اند و در شمارش Universe هم وارد نمی‌شوند.",
                        fontSize=11.sp,color=Color(0xFF747785)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick=onMarkets,
                        modifier=Modifier.fillMaxWidth(),
                        shape=RoundedCornerShape(14.dp)
                    ){
                        Text("انتخاب بورس / فرابورس / بازار پایه / اهرمی")
                    }
                }
            }

            item{
                PolishedCard{
                    Text("۲. نوع استخراج",fontSize=15.sp,fontWeight=FontWeight.Black)
                    Text(
                        "سریع: یک سال برای شروع تحلیل. عمیق: تکمیل پنج‌ساله با Resume و بدون دانلود دوباره داده کامل.",
                        fontSize=10.sp,color=Color(0xFF747785)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ){
                        FilterChip(
                            selected=selectedMode=="QUICK",
                            onClick={selectedMode="QUICK"},
                            label={Text("استخراج سریع • ۱ سال")},
                            modifier=Modifier.weight(1f),
                            colors=FilterChipDefaults.filterChipColors(
                                selectedContainerColor=MaterialTheme.colorScheme.primary,
                                selectedLabelColor=Color.White
                            )
                        )
                        FilterChip(
                            selected=selectedMode=="DEEP",
                            onClick={selectedMode="DEEP"},
                            label={Text("تکمیل عمیق • ۵ سال")},
                            modifier=Modifier.weight(1f),
                            colors=FilterChipDefaults.filterChipColors(
                                selectedContainerColor=MaterialTheme.colorScheme.primary,
                                selectedLabelColor=Color.White
                            )
                        )
                    }
                    Text(
                        "اگر Android کار را متوقف کند، checkpoint ذخیره می‌شود و شروع بعدی از همان نماد ادامه می‌دهد.",
                        fontSize=10.sp,color=Color(0xFF118658)
                    )
                }
            }

            item{
                Button(
                    onClick={showConfirm=true},
                    enabled=eligibleCount>0,
                    modifier=Modifier.fillMaxWidth().height(52.dp),
                    shape=RoundedCornerShape(16.dp)
                ){
                    Text(
                        if(eligibleCount>0)
                            if(selectedMode=="QUICK") "شروع استخراج سریع"
                            else "شروع تکمیل عمیق"
                        else "ابتدا فهرست نمادها را بازسازی کنید",
                        fontWeight=FontWeight.Bold
                    )
                }
            }

            item{
                ProcessCard("وضعیت استخراج",syncStatus,syncDone,syncTotal)
            }

            item{
                PolishedCard{
                    Text("وضعیت تحلیل یکپارچه",fontSize=15.sp,fontWeight=FontWeight.Black)
                    ProcessCardInline(
                        title="۱. بررسی کاندیدها و صف واقعی بعد از ۹",
                        status=analysisStatus,
                        done=analysisDone,
                        total=analysisTotal
                    )
                    ProcessCardInline(
                        title="۲. نتیجه روز کاری بعد",
                        status=nextDayStatus,
                        done=nextDayDone,
                        total=nextDayTotal
                    )
                    ProcessCardInline(
                        title="۳. Walk-Forward قبل از صف",
                        status=walkStatus,
                        done=walkDone,
                        total=walkTotal
                    )
                    Text(
                        "اگر یک مرحله قبلاً کامل شده باشد، اجرای دوباره تحلیل از مرحله ناقص بعدی ادامه پیدا می‌کند.",
                        fontSize=10.sp,color=Color(0xFF118658)
                    )
                }
            }

            item{
                FilledTonalButton(
                    onClick=onAnalyze,
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(14.dp)
                ){
                    Text("اجرای تحلیل یکپارچه صف پایدار + روز بعد",fontSize=11.sp)
                }
            }
        }

        if(showConfirm){
            AlertDialog(
                onDismissRequest={showConfirm=false},
                shape=RoundedCornerShape(24.dp),
                title={Text("تایید شروع استخراج",fontWeight=FontWeight.Black)},
                text={
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("Universe فعلی: ${fa(eligibleCount)} نماد")
                        Text(
                            if(selectedMode=="QUICK")
                                "حالت: استخراج سریع یک‌ساله"
                            else
                                "حالت: تکمیل عمیق پنج‌ساله"
                        )
                        Text(
                            "تا زمانی که «تایید و شروع» را نزنی هیچ Worker استخراجی اجرا نمی‌شود.",
                            fontSize=11.sp,color=MaterialTheme.colorScheme.primary,
                            fontWeight=FontWeight.Bold
                        )
                    }
                },
                confirmButton={
                    Button(onClick={
                        showConfirm=false
                        onStart(selectedMode)
                    }){Text("تایید و شروع")}
                },
                dismissButton={
                    TextButton(onClick={showConfirm=false}){Text("انصراف")}
                }
            )
        }
    }

    @Composable
    private fun Dashboard(
        records:Int,symbols:Int,candidates:Int,confirmed:Int,rejected:Int,errors:Int,latest:Int?,
        syncStatus:String,syncDone:Int,syncTotal:Int,
        analysisStatus:String,analysisDone:Int,analysisTotal:Int,
        metadataStatus:String,liveEnabled:Boolean,lastLiveScan:Long?,
        onLiveToggle:(Boolean)->Unit,onUpdate:()->Unit,onAnalyze:()->Unit
    ){
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            item{
                Card(
                    shape=RoundedCornerShape(24.dp),
                    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)
                ){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Stat("رکورد",records,Modifier.weight(1f))
                            Stat("نماد",symbols,Modifier.weight(1f))
                            Stat("صف",confirmed,Modifier.weight(1f))
                        }
                        Text("کاندید: ${fa(candidates)}  •  ردشده: ${fa(rejected)}  •  خطا: ${fa(errors)}")
                        Text("آخرین روز بازار: ${Jalali.fromGregorianInt(latest)}")
                    }
                }
            }

            item{
                Card(shape=RoundedCornerShape(20.dp)){
                    Column(Modifier.padding(14.dp)){
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.SpaceBetween,
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Column{
                                Text("اسکن زنده ۵ ثانیه‌ای",fontWeight=FontWeight.Bold)
                                Text(
                                    if(liveEnabled) "فعال — کل MarketWatch در هر چرخه بررسی می‌شود"
                                    else "خاموش",
                                    fontSize=12.sp
                                )
                            }
                            Switch(checked=liveEnabled,onCheckedChange=onLiveToggle)
                        }
                        if(lastLiveScan!=null){
                            Text("آخرین اسکن: ${clock(lastLiveScan)}",fontSize=12.sp)
                        }
                    }
                }
            }

            item{ ProcessCard("دریافت تاریخچه",syncStatus,syncDone,syncTotal) }
            item{ ProcessCard("تحلیل الگو",analysisStatus,analysisDone,analysisTotal) }
            item{
                Card(shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(14.dp)){
                        Text("نام نمادها",fontWeight=FontWeight.Bold)
                        Text(metadataStatus)
                    }
                }
            }

            item{
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick=onUpdate,modifier=Modifier.weight(1f)){Text("به‌روزرسانی")}
                    Button(onClick=onAnalyze,modifier=Modifier.weight(1f)){Text("تحلیل سریع")}
                }
            }
        }
    }

    @Composable
    private fun Opportunities(scores:List<LiveScoreEntity>){
        if(scores.isEmpty()){
            Empty("فعلاً فرصت زنده‌ای ثبت نشده")
            return
        }
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            items(scores){s->
                Card(shape=RoundedCornerShape(22.dp)){
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Column{
                                Text(s.symbol?:"در حال تکمیل نام",fontWeight=FontWeight.Black,fontSize=20.sp)
                                Text(s.reason,fontSize=12.sp,color=Color.DarkGray)
                            }
                            ScoreBadge(s.score)
                        }

                        WeightedBar("الگوی صف",s.patternScore,40)
                        WeightedBar("تکنیکال",s.technicalScore,25)
                        WeightedBar("حجم",s.volumeScore,20)
                        WeightedBar("شباهت رفتاری*",s.actorScore,15)

                        if(s.rsi!=null){
                            Text("RSI: ${Jalali.digits(String.format(Locale.US,"%.1f",s.rsi))}",fontSize=12.sp)
                        }
                        Text("* شباهت رفتاری آزمایشی است و هویت یک معامله‌گر را اثبات نمی‌کند.",fontSize=10.sp,color=Color.Gray)
                    }
                }
            }
        }
    }

    @Composable
    private fun QueueHistory(items:List<QueueHistoryRow>){
        if(items.isEmpty()){
            Empty("هنوز صف تأییدشده‌ای برای بازارهای انتخابی ثبت نشده")
            return
        }
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(8.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            items(items){h->
                Card(shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(14.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Text(h.symbol?:"در حال تکمیل نام",fontWeight=FontWeight.Bold,fontSize=18.sp)
                            Text("${fa(h.score.toInt())}/۱۰۰",fontWeight=FontWeight.Black)
                        }
                        Text(Jalali.fromGregorianInt(h.date),color=MaterialTheme.colorScheme.primary)
                        Text("زمان صف: ${fmtTime(h.eventTime)}  •  ارزش صف: ${fmtMoney(h.queueValue)}")
                    }
                }
            }
        }
    }

    @Composable
    private fun PaperTrades(items:List<PaperTradeEntity>){
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(12.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
        ){
            item{
                PageHero(
                    eyebrow="PAPER",
                    title="پیپر تریدینگ",
                    subtitle="آزمایش استراتژی بدون پول واقعی"
                )
            }

            if(items.isEmpty()){
                item{
                    PolishedEmpty("هنوز معامله آزمایشی ثبت نشده است. سیگنال‌های قوی بعداً می‌توانند اینجا وارد Paper Trading شوند.")
                }
            }else{
                items(items){t->
                    val open=t.status=="OPEN"
                    Card(
                        shape=RoundedCornerShape(20.dp),
                        colors=CardDefaults.cardColors(containerColor=Color.White),
                        border=BorderStroke(1.dp,Color(0xFFE5E7EE))
                    ){
                        Column(
                            Modifier.padding(15.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)
                        ){
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement=Arrangement.SpaceBetween,
                                verticalAlignment=Alignment.CenterVertically
                            ){
                                Text(
                                    t.symbol?:"نماد",
                                    fontSize=18.sp,
                                    fontWeight=FontWeight.Black
                                )
                                Surface(
                                    color=if(open) Color(0xFFE3F6EA) else Color(0xFFF0F1F5),
                                    shape=RoundedCornerShape(10.dp)
                                ){
                                    Text(
                                        if(open)"باز" else "بسته",
                                        Modifier.padding(horizontal=10.dp,vertical=5.dp),
                                        color=if(open) Color(0xFF118658) else Color(0xFF6D707D),
                                        fontSize=11.sp,
                                        fontWeight=FontWeight.Bold
                                    )
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement=Arrangement.spacedBy(8.dp)
                            ){
                                MetricPill("ورود",faPrice(t.entryPrice),Modifier.weight(1f))
                                MetricPill("فعلی/خروج",faPrice(t.currentPrice),Modifier.weight(1f))
                                MetricPill(
                                    "بازده",
                                    Jalali.digits(String.format(Locale.US,"%.2f%%",t.pnlPct)),
                                    Modifier.weight(1f)
                                )
                            }
                            Text(
                                "امتیاز ورود ${fa(t.entryScore.toInt())}/۱۰۰",
                                fontSize=11.sp,
                                color=Color(0xFF777A86)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SymbolSearchPage(
        query:String,onQuery:(String)->Unit,results:List<SymbolEntity>,
        selected:SymbolEntity?,signals:List<SymbolSignalRow>,
        preQueue:List<PreQueueSnapshotRow>,
        stats:SymbolDetailStats?,catalogStatus:String,
        onSelect:(SymbolEntity)->Unit,
        onNewSearch:()->Unit
    ){
        if(selected!=null){
            LazyColumn(
                verticalArrangement=Arrangement.spacedBy(12.dp),
                contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
            ){
                item{
                    OutlinedButton(
                        onClick=onNewSearch,
                        modifier=Modifier.fillMaxWidth(),
                        shape=RoundedCornerShape(14.dp)
                    ){
                        Text("🔎 جستجوی نماد دیگر",fontWeight=FontWeight.Bold)
                    }
                }
                item{
                    PageHero(
                        eyebrow="SYMBOL",
                        title=selected.symbol?:selected.name?:"نماد",
                        subtitle=selected.name?.takeIf{it!=selected.symbol} ?: "جزئیات تاریخی نماد"
                    )
                }

                item{
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ){
                        SummaryTile(
                            title="رکورد تاریخی",
                            value=fa(stats?.recordCount ?: 0),
                            bg=Color(0xFFEAF2FF),
                            modifier=Modifier.weight(1f)
                        )
                        SummaryTile(
                            title="اولین داده",
                            value=stats?.firstDate?.let{Jalali.fromGregorianInt(it)} ?: "—",
                            bg=Color(0xFFF2ECFF),
                            modifier=Modifier.weight(1f)
                        )
                        SummaryTile(
                            title="آخرین داده",
                            value=stats?.lastDate?.let{Jalali.fromGregorianInt(it)} ?: "—",
                            bg=Color(0xFFE9F7F3),
                            modifier=Modifier.weight(1f)
                        )
                    }
                }

                if(preQueue.isNotEmpty()){
                    item{
                        PolishedCard{
                            Text("تایم‌لاین هشدار قبل از صف",fontSize=16.sp,fontWeight=FontWeight.Black)
                            Text(
                                "امتیاز در ۳۰، ۲۰، ۱۵، ۱۰ و ۵ دقیقه قبل از تشکیل واقعی صف",
                                fontSize=10.sp,color=Color(0xFF777A86)
                            )
                        }
                    }
                }

                if(signals.isEmpty()){
                    item{
                        PolishedEmpty(
                            "هنوز سیگنال تاریخی ثبت نشده. بعد از استخراج و تحلیل داده‌های همین نماد، زمان‌های هشدار اینجا نمایش داده می‌شوند."
                        )
                    }
                }else{
                    items(signals){s->
                        Card(
                            shape=RoundedCornerShape(18.dp),
                            colors=CardDefaults.cardColors(containerColor=Color.White),
                            border=BorderStroke(1.dp,Color(0xFFE6E8EF))
                        ){
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement=Arrangement.spacedBy(5.dp)
                            ){
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement=Arrangement.SpaceBetween
                                ){
                                    Text(
                                        Jalali.fromGregorianInt(s.date),
                                        fontWeight=FontWeight.Black
                                    )
                                    Text(
                                        "${fa(s.score.toInt())}/۱۰۰",
                                        color=MaterialTheme.colorScheme.primary,
                                        fontWeight=FontWeight.Black
                                    )
                                }
                                val snapsForDay=preQueue
                                    .filter{it.date==s.date && it.label==1}
                                    .sortedByDescending{it.minutesBefore}
                                val firstDetectedForDay=preQueue
                                    .firstOrNull{
                                        it.date==s.date &&
                                        it.label==1 &&
                                        it.minutesBefore==0 &&
                                        it.detected
                                    }

                                Text(
                                    when(s.status){
                                        "QUEUE_CONFIRMED" ->
                                            if(firstDetectedForDay!=null)
                                                "اولین هشدار ${fmtTime(firstDetectedForDay.snapshotTime)} • صف ${fmtTime(s.eventTime)} • پیش‌آگاهی ${leadMinutesText(firstDetectedForDay.snapshotTime,s.eventTime)}"
                                            else
                                                "صف ${fmtTime(s.eventTime)} • در شبیه‌سازی رو‌به‌جلو هشدار قبلی ثبت نشد"
                                        "FRAGILE_QUEUE" ->
                                            "صف شکننده ${fmtTime(s.eventTime)} • از سیگنال مثبت حذف شده"
                                        "PREOPEN_QUEUE" ->
                                            "صف از قبلِ ۹ وجود داشته • از روز سیگنال حذف شده"
                                        "SPECIAL_REOPEN" ->
                                            "بازگشایی ویژه • از مدل اصلی حذف شده"
                                        "NOT_QUEUE" ->
                                            "کاندید بررسی شد • صف پایدار تشکیل نشد"
                                        "ERROR" ->
                                            "تحلیل این رخداد نیازمند تلاش مجدد است"
                                        else ->
                                            "وضعیت: ${s.status}"
                                    },
                                    fontSize=11.sp,
                                    color=Color(0xFF737684)
                                )
                                Text(
                                    when(s.nextDayQueueStatus){
                                        "SKIPPED_PREOPEN_DAY1"->"نتیجه روز بعد برای این نمونه محاسبه نمی‌شود"
                                        "SKIPPED_SPECIAL_REOPEN"->"بازگشایی ویژه؛ خارج از ارزیابی اصلی"
                                        "SKIPPED_FRAGILE"->"صف روز اول پایدار نبود؛ نمونه منفی مدل"
                                        "NEXT_DAY_DAILY_ONLY"->"داده سفارش روز بعد نبود؛ نتیجه روزانه ثبت شد"
                                        "PREOPEN_QUEUE_NEXT_DAY"->"روز بعد از پیش‌گشایش صف خرید بود ★"
                                        "QUEUE_AGAIN"->"روز بعد در تایم عادی صف خرید شد ✓"
                                        "POSITIVE_STRONG_NEXT_DAY"->"روز بعد مثبت قوی بود ✓"
                                        "POSITIVE_NEXT_DAY"->"روز بعد مثبت بود ✓"
                                        "FLAT_NEXT_DAY"->"روز بعد خنثی بود"
                                        "NEGATIVE_NEXT_DAY"->"روز بعد منفی بود"
                                        "NOT_QUEUE_NEXT_DAY"->"روز بعد صف نشد"
                                        "NO_NEXT_DAY"->"داده روز بعد موجود نیست"
                                        else->"روز بعد هنوز بررسی نشده"
                                    },
                                    fontSize=11.sp,
                                    color=when(s.nextDayQueueStatus){
                                        "PREOPEN_QUEUE_NEXT_DAY"->Color(0xFF087A53)
                                        "QUEUE_AGAIN"->Color(0xFF118658)
                                        "POSITIVE_STRONG_NEXT_DAY"->Color(0xFF118658)
                                        "POSITIVE_NEXT_DAY"->Color(0xFF3A8C68)
                                        "NEGATIVE_NEXT_DAY"->Color(0xFFB85A5A)
                                        "NOT_QUEUE_NEXT_DAY"->Color(0xFFB85A5A)
                                        else->Color(0xFF777A86)
                                    }
                                )
                                if(s.eventTime!=null){
                                    Text(
                                        "دوام صف ${fa(s.queueDurationMinutes)} دقیقه • پایداری ${fa((s.queuePersistenceRatio*100).toInt())}٪ • شکست ${fa(s.queueBreakCount)} بار" +
                                            if(s.queueEndHeld) " • پایان بازار: صف حفظ شد" else "",
                                        fontSize=10.sp,
                                        color=Color(0xFF626675)
                                    )
                                }
                                s.nextDayReturnPct?.let{ret->
                                    Text(
                                        "بازده روز کاری بعد: ${if(ret>=0) "+" else ""}${Jalali.digits(String.format(Locale.US,"%.2f",ret))}٪",
                                        fontSize=10.sp,
                                        color=if(ret>=0) Color(0xFF118658) else Color(0xFFB85A5A)
                                    )
                                }
                                val snaps=snapsForDay
                                if(snaps.isNotEmpty()){
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "قبل از صف:",
                                        fontSize=10.sp,
                                        fontWeight=FontWeight.Bold,
                                        color=Color(0xFF676A78)
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement=Arrangement.spacedBy(4.dp)
                                    ){
                                        snaps.take(5).forEach{p->
                                            Surface(
                                                modifier=Modifier.weight(1f),
                                                color=if(p.detected) Color(0xFFE6F7ED) else Color(0xFFF4F4F7),
                                                shape=RoundedCornerShape(10.dp)
                                            ){
                                                Column(
                                                    Modifier.padding(vertical=6.dp,horizontal=3.dp),
                                                    horizontalAlignment=Alignment.CenterHorizontally
                                                ){
                                                    Text("${fa(p.minutesBefore)}د",fontSize=8.sp)
                                                    Text(
                                                        fa(p.score.toInt()),
                                                        fontSize=11.sp,
                                                        fontWeight=FontWeight.Black,
                                                        color=if(p.detected) Color(0xFF118658) else Color(0xFF777A86)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    val firstDetected=snaps
                                        .filter{it.detected}
                                        .maxByOrNull{it.minutesBefore}
                                    Text(
                                        if(firstDetected!=null)
                                            "اولین هشدار معتبر: ${fa(firstDetected.minutesBefore)} دقیقه قبل از صف"
                                        else
                                            "مدل قبل از این صف هشدار معتبر نداده است",
                                        fontSize=10.sp,
                                        color=if(firstDetected!=null) Color(0xFF118658) else Color(0xFFB05B5B)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            return
        }

        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
        ){
            item{
                PageHero(
                    eyebrow="SEARCH",
                    title="جستجوی نماد",
                    subtitle="نام نماد را پیدا کن و سابقه سیگنال‌هایش را ببین"
                )
            }
            item{
                OutlinedTextField(
                    value=query,
                    onValueChange=onQuery,
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(18.dp),
                    singleLine=true,
                    label={Text("نام نماد")},
                    placeholder={Text("مثال: وبملت")}
                )
            }

            if(query.isNotBlank() && results.isEmpty()){
                item{
                    PolishedEmpty(
                        if(catalogStatus.contains("آماده شد"))
                            "نمادی با این عبارت پیدا نشد."
                        else "فهرست نمادها هنوز آماده نیست. وضعیت: $catalogStatus"
                    )
                }
            }

            items(results){s->
                Card(
                    modifier=Modifier.fillMaxWidth().clickable{onSelect(s)},
                    shape=RoundedCornerShape(18.dp),
                    colors=CardDefaults.cardColors(containerColor=Color.White),
                    border=BorderStroke(1.dp,Color(0xFFE6E8EF))
                ){
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column(Modifier.weight(1f)){
                            Text(
                                s.symbol?:s.name?:"نماد",
                                fontWeight=FontWeight.Black,
                                fontSize=17.sp
                            )
                            if(!s.name.isNullOrBlank() && s.name!=s.symbol){
                                Text(s.name!!,fontSize=11.sp,color=Color(0xFF777A86))
                            }
                        }
                        Surface(
                            color=Color(0xFFF0EBFF),
                            shape=RoundedCornerShape(12.dp)
                        ){
                            Text(
                                "جزئیات",
                                Modifier.padding(horizontal=10.dp,vertical=6.dp),
                                color=MaterialTheme.colorScheme.primary,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsPage(onMarkets:()->Unit,onSymbolsUpdate:()->Unit,onUpdate:()->Unit){
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            item{
                Button(onClick=onMarkets,modifier=Modifier.fillMaxWidth()){Text("نوع اوراق و بازارهای مورد بررسی")}
            }
            item{
                FilledTonalButton(onClick=onSymbolsUpdate,modifier=Modifier.fillMaxWidth()){
                    Text("به‌روزرسانی فهرست و نام نمادها")
                }
            }
            item{
                FilledTonalButton(onClick=onUpdate,modifier=Modifier.fillMaxWidth()){Text("همگام‌سازی داده‌های جدید")}
            }

            item{
                Card(shape=RoundedCornerShape(18.dp)){
                    Text(
                        "اسکن ۵ثانیه‌ای فقط هنگام باز بودن برنامه اجرا می‌شود. در پس‌زمینه Android، رصد دوره‌ای با WorkManager ادامه دارد.",
                        Modifier.padding(14.dp),
                        fontSize=12.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun PageHero(eyebrow:String,title:String,subtitle:String){
        Card(
            shape=RoundedCornerShape(24.dp),
            colors=CardDefaults.cardColors(containerColor=Color(0xFF211B37))
        ){
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement=Arrangement.spacedBy(5.dp)
            ){
                Text(
                    eyebrow,
                    fontSize=10.sp,
                    fontWeight=FontWeight.Bold,
                    color=Color(0xFFBCAEFF)
                )
                Text(
                    title,
                    fontSize=23.sp,
                    fontWeight=FontWeight.Black,
                    color=Color.White
                )
                Text(
                    subtitle,
                    fontSize=11.sp,
                    color=Color(0xFFD5D0E4)
                )
            }
        }
    }

    @Composable
    private fun PolishedCard(content:@Composable ColumnScope.()->Unit){
        Card(
            shape=RoundedCornerShape(20.dp),
            colors=CardDefaults.cardColors(containerColor=Color.White),
            border=BorderStroke(1.dp,Color(0xFFE5E7EE))
        ){
            Column(
                Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement=Arrangement.spacedBy(6.dp),
                content=content
            )
        }
    }

    @Composable
    private fun PolishedEmpty(text:String){
        Card(
            shape=RoundedCornerShape(20.dp),
            colors=CardDefaults.cardColors(containerColor=Color.White),
            border=BorderStroke(1.dp,Color(0xFFE6E8EF))
        ){
            Text(
                text,
                Modifier.fillMaxWidth().padding(22.dp),
                textAlign=TextAlign.Center,
                fontSize=12.sp,
                color=Color(0xFF7A7D89)
            )
        }
    }

    @Composable
    private fun MetricPill(title:String,value:String,modifier:Modifier=Modifier){
        Surface(
            modifier=modifier,
            color=Color(0xFFF7F8FB),
            shape=RoundedCornerShape(14.dp)
        ){
            Column(
                Modifier.padding(horizontal=8.dp,vertical=9.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ){
                Text(title,fontSize=9.sp,color=Color(0xFF858793))
                Text(value,fontSize=12.sp,fontWeight=FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun ProcessCardInline(
        title:String,status:String,done:Int,total:Int
    ){
        Column(
            Modifier.fillMaxWidth().padding(vertical=4.dp),
            verticalArrangement=Arrangement.spacedBy(4.dp)
        ){
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.SpaceBetween
            ){
                Text(title,fontSize=11.sp,fontWeight=FontWeight.Bold)
                if(total>0){
                    Text("${fa(done)} / ${fa(total)}",fontSize=10.sp,color=Color(0xFF777A86))
                }
            }
            Text(status,fontSize=10.sp,color=Color(0xFF747785))
            if(total>0 && done<total){
                LinearProgressIndicator(
                    progress={done.toFloat()/total.toFloat()},
                    modifier=Modifier.fillMaxWidth().height(5.dp)
                )
            }
        }
    }

    @Composable
    private fun ProcessCard(title:String,status:String,done:Int,total:Int){
        Card(
            shape=RoundedCornerShape(20.dp),
            colors=CardDefaults.cardColors(containerColor=Color.White),
            border=BorderStroke(1.dp,Color(0xFFE5E7EE))
        ){
            Column(
                Modifier.padding(15.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)
            ){
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.SpaceBetween
                ){
                    Text(title,fontWeight=FontWeight.Bold)
                    if(total>0){
                        Text("${fa(done)} / ${fa(total)}",fontSize=11.sp,color=Color(0xFF777A86))
                    }
                }
                Text(status,fontSize=11.sp,color=Color(0xFF6F7280))
                if(total>0 && done<total){
                    LinearProgressIndicator(
                        progress={done.toFloat()/total.toFloat()},
                        modifier=Modifier.fillMaxWidth().height(7.dp),
                        trackColor=Color(0xFFEDEEF3)
                    )
                }
            }
        }
    }

    @Composable
    private fun Stat(title:String,value:Int,modifier:Modifier){
        Surface(modifier,shape=RoundedCornerShape(16.dp),color=Color.White.copy(alpha=.78f)){
            Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text(fa(value),fontSize=20.sp,fontWeight=FontWeight.Black)
                Text(title,fontSize=11.sp)
            }
        }
    }

    @Composable
    private fun ScoreBadge(score:Double){
        Surface(
            shape=RoundedCornerShape(18.dp),
            color=MaterialTheme.colorScheme.primaryContainer
        ){
            Text(
                "${fa(score.toInt())}/۱۰۰",
                Modifier.padding(horizontal=12.dp,vertical=10.dp),
                fontWeight=FontWeight.Black
            )
        }
    }

    @Composable
    private fun WeightedBar(label:String,raw:Double,weight:Int){
        val contribution=raw/100.0*weight
        Column{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text(label,fontSize=12.sp)
                Text(
                    "${fa(raw.toInt())}/۱۰۰  →  ${Jalali.digits(String.format(Locale.US,"%.1f",contribution))}/$weight",
                    fontSize=11.sp
                )
            }
            LinearProgressIndicator(
                progress={(raw/100.0).toFloat().coerceIn(0f,1f)},
                modifier=Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    private fun Empty(text:String){
        Card(shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().padding(top=10.dp)){
            Text(text,Modifier.padding(24.dp),color=Color.Gray)
        }
    }


    @Composable
    private fun MarketDialog(
        initialTypes:Set<String>,
        initialSegments:Set<String>,
        onDismiss:()->Unit,
        onSave:(Set<String>,Set<String>)->Unit
    ){
        var selectedTypes by remember{mutableStateOf(initialTypes.intersect(MarketPrefs.allTypes))}
        var selectedSegments by remember{mutableStateOf(initialSegments.intersect(MarketPrefs.allSegments))}

        AlertDialog(
            onDismissRequest=onDismiss,
            shape=RoundedCornerShape(26.dp),
            containerColor=Color.White,
            title={
                Column{
                    Text("محدوده استخراج",fontWeight=FontWeight.Black,fontSize=20.sp)
                    Text(
                        "فقط ابزارهای سازگار با مدل Signal",
                        fontSize=11.sp,
                        color=Color(0xFF777A86)
                    )
                }
            },
            text={
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("نوع ابزار",fontSize=12.sp,fontWeight=FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)
                    ){
                        listOf(
                            MarketPrefs.TYPE_STOCK,
                            MarketPrefs.TYPE_BASE,
                            MarketPrefs.TYPE_FUND
                        ).forEach{type->
                            FilterChip(
                                selected=selectedTypes.contains(type),
                                onClick={
                                    selectedTypes=
                                        if(selectedTypes.contains(type)) selectedTypes-type
                                        else selectedTypes+type
                                },
                                label={Text(MarketPrefs.typeLabel(type),fontSize=10.sp)},
                                colors=FilterChipDefaults.filterChipColors(
                                    selectedContainerColor=MaterialTheme.colorScheme.primary,
                                    selectedLabelColor=Color.White
                                )
                            )
                        }
                    }

                    HorizontalDivider(color=Color(0xFFEDEEF2))
                    Text("بازار",fontSize=12.sp,fontWeight=FontWeight.Bold)

                    listOf(
                        MarketPrefs.BOURSE,
                        MarketPrefs.FARABOURSE,
                        MarketPrefs.BASE_YELLOW,
                        MarketPrefs.BASE_ORANGE,
                        MarketPrefs.BASE_RED
                    ).forEach{seg->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Checkbox(
                                checked=selectedSegments.contains(seg),
                                onCheckedChange={on->
                                    selectedSegments=
                                        if(on) selectedSegments+seg else selectedSegments-seg
                                }
                            )
                            Text(MarketPrefs.label(seg),fontSize=12.sp)
                        }
                    }

                    Surface(
                        color=Color(0xFFF5F2FF),
                        shape=RoundedCornerShape(14.dp)
                    ){
                        Text(
                            "تسهیلات مسکن، حق تقدم، اوراق بدهی، اختیار معامله، آتی، بورس کالا، انرژی و TAL در این مدل حذف شده‌اند.",
                            Modifier.padding(10.dp),
                            fontSize=10.sp,
                            color=Color(0xFF625C76)
                        )
                    }
                }
            },
            confirmButton={
                Button(
                    onClick={
                        onSave(
                            if(selectedTypes.isEmpty()) MarketPrefs.allTypes else selectedTypes,
                            if(selectedSegments.isEmpty()) MarketPrefs.allSegments else selectedSegments
                        )
                    },
                    shape=RoundedCornerShape(14.dp)
                ){Text("ذخیره انتخاب‌ها")}
            },
            dismissButton={
                TextButton(onClick=onDismiss){Text("انصراف")}
            }
        )
    }

    private fun refreshSymbolCatalog(){
        getSharedPreferences("catalog",Context.MODE_PRIVATE)
            .edit()
            .putString("status","به‌روزرسانی افزایشی: فقط نمادهای جدید/ناقص بررسی می‌شوند")
            .apply()
        val req=OneTimeWorkRequestBuilder<SymbolCatalogWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            SymbolCatalogWorker.CHAIN,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    private fun saveExtractionSelection(years:Int){
        getSharedPreferences("extract",Context.MODE_PRIVATE)
            .edit()
            .remove("symbols")
            .putInt("years",years.coerceIn(1,5))
            .apply()
    }

    private fun startUpdate(mode:String="DEEP"){
        HistoricalWorker.start(this,false,mode)
    }

    private fun startAnalyze(){
        getSharedPreferences("analysis_pipeline",Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled",true)
            .putString("stage","DAY1")
            .apply()

        val req=OneTimeWorkRequestBuilder<QueueAnalysisWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .setInputData(
                workDataOf(
                    "batchSize" to 120,
                    "parallelism" to 4,
                    "resetErrors" to true
                )
            )
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            QueueAnalysisWorker.ANALYSIS_CHAIN,ExistingWorkPolicy.REPLACE,req
        )
    }

    private fun startNextDayCheck(){
        val req=OneTimeWorkRequestBuilder<NextDayQueueWorker>()
            .setConstraints(HistoricalWorker.networkConstraint()).build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            NextDayQueueWorker.CHAIN,ExistingWorkPolicy.REPLACE,req
        )
    }

    private fun startNameRepair(){
        val req=OneTimeWorkRequestBuilder<MetadataWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .setInputData(workDataOf("batch" to 30))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            MetadataWorker.CHAIN,ExistingWorkPolicy.REPLACE,req
        )
    }

    private fun todayGregorianInt(ms:Long):Int{
        val sdf=SimpleDateFormat("yyyyMMdd",Locale.US)
        return sdf.format(Date(ms)).toIntOrNull() ?: 0
    }

    private fun persianDayName(ms:Long):String{
        val cal=Calendar.getInstance()
        cal.timeInMillis=ms
        return when(cal.get(Calendar.DAY_OF_WEEK)){
            Calendar.SATURDAY -> "شنبه"
            Calendar.SUNDAY -> "یکشنبه"
            Calendar.MONDAY -> "دوشنبه"
            Calendar.TUESDAY -> "سه‌شنبه"
            Calendar.WEDNESDAY -> "چهارشنبه"
            Calendar.THURSDAY -> "پنج‌شنبه"
            else -> "جمعه"
        }
    }

    private fun marketPhase(ms:Long):String{
        val cal=Calendar.getInstance()
        cal.timeInMillis=ms
        val dow=cal.get(Calendar.DAY_OF_WEEK)
        if(dow==Calendar.THURSDAY || dow==Calendar.FRIDAY) return "بازار بسته"
        val hh=cal.get(Calendar.HOUR_OF_DAY)
        val mm=cal.get(Calendar.MINUTE)
        val mins=hh*60+mm
        return when{
            mins<8*60+45 -> "بازار بسته"
            mins<9*60 -> "پیش‌گشایش"
            mins<=12*60+30 -> "بازار باز"
            else -> "بازار بسته"
        }
    }

    private fun leadMinutesText(signal:Int?,event:Int?):String{
        if(signal==null || event==null) return "—"
        fun toSec(v:Int):Int{
            val s=v.toString().padStart(6,'0')
            val h=s.substring(0,2).toIntOrNull() ?: 0
            val m=s.substring(2,4).toIntOrNull() ?: 0
            val sec=s.substring(4,6).toIntOrNull() ?: 0
            return h*3600+m*60+sec
        }
        val d=(toSec(event)-toSec(signal)).coerceAtLeast(0)
        return if(d<60) "${Jalali.digits(d.toString())} ثانیه"
        else "${Jalali.digits((d/60).toString())} دقیقه"
    }

    private fun fa(v:Int)=Jalali.digits(v.toString())

    private fun clock(ms:Long):String =
        Jalali.digits(SimpleDateFormat("HH:mm:ss",Locale.US).format(Date(ms)))

    private fun fmtTime(v:Int?):String{
        if(v==null)return "—"
        val s=v.toString().padStart(6,'0')
        return Jalali.digits("${s.substring(0,2)}:${s.substring(2,4)}")
    }

    private fun fmtMoney(v:Double?):String{
        if(v==null||v<=0)return "—"
        val raw=when{
            v>=10_000_000_000.0 -> String.format(Locale.US,"%.1f میلیارد تومان",v/10_000_000_000.0)
            else -> String.format(Locale.US,"%.0f میلیون تومان",v/10_000_000.0)
        }
        return Jalali.digits(raw)
    }

    private fun faPrice(v:Double):String =
        Jalali.digits(String.format(Locale.US,"%.0f",v))
}
