# SQLCipher uses JNI; keep its classes.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
# Room generated code is kept by Room's own consumer rules.
# WorkManager workers are instantiated reflectively by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# SQLCipher uses JNI; keep its classes and native-called members.
-keep class net.zetetic.** { *; }
-keep class androidx.sqlite.** { *; }
# Glance widget receivers/actions are instantiated reflectively.
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }
