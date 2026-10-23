# Freq Control — التحكم بتردد GPU / CPU (سامسونج + LSPosed)

تطبيق صغير يحدد **أدنى وأعلى تردد** للـ GPU (وCPU تجريبياً) بنفس آلية OneLab:
موديول LSPosed داخل `com.sec.android.sdhms` يستخدم `SemDvfsManager`، والتطبيق يكتب الإعدادات في `Settings.Global`.

## ماذا يحتاج
- **LSPosed**: ضروري (التطبيق الفعلي للتردد يتم داخل SDHMS).
- **روت**: مرة واحدة فقط، يمنح التطبيق صلاحية `WRITE_SECURE_SETTINGS`. بعدها ما يحتاجه.
  بدون روت: `adb shell pm grant io.github.freqctl android.permission.WRITE_SECURE_SETTINGS`

## بناء الـ APK
**الطريقة الأسهل (من الجوال، بدون كمبيوتر):**
1. أنشئ مستودع GitHub جديد وارفع محتويات هذا المجلد (مع مجلد `.github`).
2. تبويب **Actions** ← **Build APK** ← **Run workflow**.
3. بعد دقائق نزّل `FreqControl-apk` من أسفل صفحة التشغيل (فيه `app-debug.apk`).

**أو Android Studio:** افتح المجلد ← Build ← Build APK(s).

## التشغيل
1. ثبّت الـ APK.
2. LSPosed ← Modules ← فعّل **Freq Control** ← النطاق: **com.sec.android.sdhms**.
3. أعد تشغيل الجهاز.
4. افتح التطبيق، اسمح للروت، اختر النطاق وفعّل المفتاح.

## ملاحظات
- لا تفعّل تجربة GPU الخاصة بـ OneLab مع هذا التطبيق في نفس الوقت.
- التطبيق الأصلي يشترط تفعيل "SDHMS hidden thermal control" و"performance cap bypass" قبل ما يشتغل نطاق GPU. هنا محذوفين؛ النطاق يشتغل لحاله، لكن حماية الحرارة حق سامسونج تظل تقدر تخفض الحد الأعلى لما الجهاز يسخن.
- **CPU تجريبي:** أرقام أنواع CPU غير موجودة في OneLab، فيحاول التطبيق استنتاجها من النظام. إذا ما لقاها تظهر "غير مدعوم" ويكتب في سجل LSPosed سطر `FreqCtl[cpu]` فيه الحقول الموجودة.
- السجل: LSPosed ← Logs ← ابحث عن `FreqCtl`.
- الآلية مأخوذة من تحليل OneLab 1.3 (pigerzhu)، والكود هنا مكتوب من جديد.
