package com.borsapattern.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    private const val CH="signals"
    fun createChannel(ctx: Context) {
        val nm=ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH,"سیگنال‌های بازار",NotificationManager.IMPORTANCE_HIGH))
    }
    fun show(ctx: Context,symbol:String,score:Double,reason:String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n=NotificationCompat.Builder(ctx,CH)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("فرصت در حال شکل‌گیری: $symbol")
            .setContentText("امتیاز ${score.toInt()} — $reason")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(symbol.hashCode(),n)
    }
}
