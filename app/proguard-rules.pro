# NewPipe Extractor: YouTube çözümleyici (yansıma ve JavaScript motoru kullanır)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn com.google.re2j.**
-dontwarn org.jsoup.**
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# WorkManager / Room: veritabanı sınıfı yansıma (reflection) ile kurulur; yapıcısı silinmesin
# (silinince uygulama açılışta çöküyordu: NoSuchMethodException WorkDatabase_Impl.<init>)
-keep class * extends androidx.room.RoomDatabase { <init>(...); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(...); }
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class * extends androidx.work.InputMerger { <init>(...); }
