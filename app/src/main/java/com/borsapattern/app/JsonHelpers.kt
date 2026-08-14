package com.borsapattern.app

import org.json.JSONObject

private fun nestedObjects(o:JSONObject,depth:Int=0):Sequence<JSONObject> = sequence {
    if(depth>4) return@sequence
    yield(o)

    val preferred=listOf(
        "instrument","closingPrice","instrumentInfo","instrumentState",
        "marketWatch","data","result"
    )
    val seen=HashSet<String>()

    for(k in preferred){
        val n=o.optJSONObject(k)
        if(n!=null){
            seen+=k
            yieldAll(nestedObjects(n,depth+1))
        }
    }

    val names=o.names()
    if(names!=null){
        for(i in 0 until names.length()){
            val k=names.optString(i)
            if(k in seen) continue
            val n=o.optJSONObject(k)
            if(n!=null) yieldAll(nestedObjects(n,depth+1))
        }
    }
}

fun firstString(o:JSONObject,vararg keys:String):String?{
    for(obj in nestedObjects(o)){
        for(k in keys){
            if(obj.has(k) && !obj.isNull(k)){
                val v=obj.optString(k,null)?.trim()
                if(!v.isNullOrBlank() && v!="null") return v
            }
        }
    }
    return null
}

fun firstInt(o:JSONObject,vararg keys:String):Int?{
    for(obj in nestedObjects(o)){
        for(k in keys){
            if(obj.has(k) && !obj.isNull(k)){
                val raw=obj.opt(k)
                when(raw){
                    is Number -> return raw.toInt()
                    is String -> raw.trim().toIntOrNull()?.let{return it}
                }
            }
        }
    }
    return null
}

fun firstDouble(o:JSONObject,vararg keys:String):Double?{
    for(obj in nestedObjects(o)){
        for(k in keys){
            if(obj.has(k) && !obj.isNull(k)){
                val raw=obj.opt(k)
                when(raw){
                    is Number -> return raw.toDouble()
                    is String -> raw.trim().toDoubleOrNull()?.let{return it}
                }
            }
        }
    }
    return null
}

fun cleanSymbol(v:String?,insCode:String):String?{
    val s=v?.trim()
    if(s.isNullOrBlank() || s==insCode || s.all{it.isDigit()}) return null
    return s
}
