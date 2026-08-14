package com.borsapattern.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TsetmcClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 Android BorsaPattern/1.1")
            .header("Referer", "https://tsetmc.com/")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            return r.body.string()
        }
    }

    fun healthCheck(): Boolean {
        return try{
            val raw=get(
                "https://cdn.tsetmc.com/api/ClosingPrice/GetMarketWatch?market=0" +
                    "&paperTypes[0]=1&withBestLimits=false&hEven=0&RefID=0"
            )
            raw.isNotBlank() && (raw.trim().startsWith("{") || raw.trim().startsWith("["))
        }catch(_:Exception){
            false
        }
    }

    fun marketWatchRaw(): String {
        val u = "https://cdn.tsetmc.com/api/ClosingPrice/GetMarketWatch?market=0" +
            "&paperTypes[0]=1&paperTypes[1]=2&paperTypes[2]=3&paperTypes[3]=4" +
            "&paperTypes[4]=5&paperTypes[5]=6&paperTypes[6]=7&paperTypes[7]=8" +
            "&paperTypes[8]=9&withBestLimits=false&hEven=0&RefID=0"
        return get(u)
    }


    fun instrumentInfoRaw(insCode: String): String =
        get("https://cdn.tsetmc.com/api/Instrument/GetInstrumentInfo/$insCode")

    fun instrumentsHistoryInDayRaw(date: Int): String =
        get("https://cdn.tsetmc.com/api/ClosingPrice/GetInstrmentsHistoryInDay/$date")

    fun dailyRaw(insCode: String): String =
        get("https://cdn.tsetmc.com/api/ClosingPrice/GetClosingPriceDailyList/$insCode/0")

    fun bestLimitsRaw(insCode: String, date: Int): String =
        get("https://cdn.tsetmc.com/api/BestLimits/$insCode/$date")

    fun jsonObjectFrom(raw:String,vararg keys:String):JSONObject? {
        val t=raw.trim()
        if(!t.startsWith("{")) return null
        val root=JSONObject(t)
        for(k in keys){
            val v=root.opt(k)
            if(v is JSONObject) return v
        }
        return root
    }

    fun jsonArrayFrom(raw: String, vararg keys: String): JSONArray {
        val t = raw.trim()
        if (t.startsWith("[")) return JSONArray(t)
        val o = JSONObject(t)
        for (k in keys) if (o.has(k) && o.get(k) is JSONArray) return o.getJSONArray(k)
        val names = o.names()
        if (names != null && names.length() == 1) {
            val v = o.get(names.getString(0))
            if (v is JSONArray) return v
        }
        return JSONArray()
    }
}
