# قارئ PDF الصوتي — PdfVoiceReader

نسخة Java بسيطة لقراءة النصوص من ملفات PDF وتحويلها إلى صوت عبر Android Text-to-Speech.

## التوافق
- compileSdk: 36 (Android 16)
- targetSdk: 36
- minSdk: 23 (Android 6.0)
- يدعم APK واحد Android 12 و13 و14 و15 و16، ما دام الجهاز يوفّر محرك Text-to-Speech متوافقًا.

## ملاحظات التوافق
- اختيار PDF يستخدم Storage Access Framework (`ACTION_OPEN_DOCUMENT`) ولا يحتاج صلاحيات التخزين القديمة.
- تمت حماية `takePersistableUriPermission` لأن بعض مزوّدي المستندات لا يمنحون صلاحية مستمرة.
- لا توجد صلاحيات حساسة مطلوبة في Manifest.
- `android:exported="true"` مضبوط لنشاط التشغيل، وهو مطلوب للتطبيقات المستهدفة Android 12+.
- زر "استئناف" يعيد تشغيل المقطع الحالي؛ Android TextToSpeech لا يوفر API عامًّا للإيقاف المؤقت الحقيقي.
- ملفات PDF التي تحتوي نصًا قابلًا للاستخراج مدعومة. PDF المصوّر يحتاج OCR في إصدار لاحق.
