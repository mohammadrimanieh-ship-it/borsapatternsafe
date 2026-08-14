package com.borsapattern.app

object Jalali {
    fun fromGregorianInt(date:Int?):String {
        if(date==null || date<19000101) return "—"
        val gy=date/10000
        val gm=(date/100)%100
        val gd=date%100
        val p=gregorianToJalali(gy,gm,gd)
        return "${toFa(p[0])}/${toFa(p[1],2)}/${toFa(p[2],2)}"
    }

    private fun gregorianToJalali(gy0:Int, gm:Int, gd:Int):IntArray {
        val gdm=intArrayOf(0,31,59,90,120,151,181,212,243,273,304,334)
        var gy=gy0
        val gy2=if(gm>2) gy+1 else gy
        var days=355666 + 365*gy +
            (gy2+3)/4 - (gy2+99)/100 + (gy2+399)/400 +
            gd + gdm[gm-1]
        var jy=-1595 + 33*(days/12053)
        days%=12053
        jy+=4*(days/1461)
        days%=1461
        if(days>365){
            jy+=(days-1)/365
            days=(days-1)%365
        }
        val jm:Int
        val jd:Int
        if(days<186){
            jm=1+days/31
            jd=1+days%31
        }else{
            jm=7+(days-186)/30
            jd=1+(days-186)%30
        }
        return intArrayOf(jy,jm,jd)
    }

    fun toFa(v:Int,width:Int=0):String {
        var s=v.toString()
        if(width>0) s=s.padStart(width,'0')
        return s.map{
            when(it){
                '0'->'۰'; '1'->'۱'; '2'->'۲'; '3'->'۳'; '4'->'۴'
                '5'->'۵'; '6'->'۶'; '7'->'۷'; '8'->'۸'; '9'->'۹'
                else->it
            }
        }.joinToString("")
    }

    fun digits(s:String):String = s.map{
        when(it){
            '0'->'۰'; '1'->'۱'; '2'->'۲'; '3'->'۳'; '4'->'۴'
            '5'->'۵'; '6'->'۶'; '7'->'۷'; '8'->'۸'; '9'->'۹'
            else->it
        }
    }.joinToString("")
}
