#!/bin/bash
set -x
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
adb install -r "$APK"
adb shell pm grant com.erkam.temasa android.permission.POST_NOTIFICATIONS || true
adb logcat -c
echo "##### 1) Uygulamayı aç"
adb shell am start -W -n com.erkam.temasa/.MainActivity
sleep 40
adb shell dumpsys activity activities | grep -i "temasa" | head -5
echo "##### 2) Arka plan işlerini zorla çalıştır (uygulama açıkken)"
adb shell dumpsys jobscheduler | grep -A3 "com.erkam.temasa" | head -40
for i in 0 1 2 3 4 5 6 7 8 9 10; do adb shell cmd jobscheduler run -f com.erkam.temasa $i; done
sleep 45
echo "##### 3) Ana ekrana çık, süreci öldür, işleri arka planda çalıştır"
adb shell input keyevent HOME
sleep 3
adb shell am kill com.erkam.temasa
sleep 3
for i in 0 1 2 3 4 5 6 7 8 9 10; do adb shell cmd jobscheduler run -f com.erkam.temasa $i; done
sleep 60
echo "##### 4) Süreç tamamen kapalıyken tekrar aç"
adb shell am force-stop com.erkam.temasa
sleep 2
adb shell am start -W -n com.erkam.temasa/.MainActivity
sleep 30
adb shell pidof com.erkam.temasa && echo "SÜREÇ AYAKTA" || echo "SÜREÇ YOK"
adb logcat -d -v time > logcat.txt
echo "##### ÇÖKMELER"
grep -n -A40 "FATAL EXCEPTION" logcat.txt | head -200
grep -n "ANR in com.erkam\|Process com.erkam.temasa.*has died\|crashed\|ForegroundService" logcat.txt | head -40
