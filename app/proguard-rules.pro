# Signal release hardening.
# Keep Room entities/DAO metadata and WorkManager workers stable under R8.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Keep application entry points.
-keep class com.borsapattern.app.BorsaApp { *; }
-keep class com.borsapattern.app.MainActivity { *; }
