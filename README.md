# NokiaFake — מצב Nokia ל־QIN F21 Pro

פרויקט Android Studio עצמאי. הוא נועד להיפתח כממשק Nokia בעברית בזמן שהפעילות בחזית, ולהיסגר בחזרה ל־Android. הוא אינו Launcher ואינו משתמש ב־Root.

## מצב המימוש

זו גרסת פיתוח ראשונית, לא גרסה סופית. היא כוללת Activity במסך מלא, תפריט גרפי, ניווט מקשים, חיפוש מספרי באנשי קשר, חייגן, חיוג דרך Android, בקשת תפקיד SMS, קריאת שרשורי SMS, כתיבה ושליחה ב־Multi‑Tap בסיסי בעברית/אנגלית/ספרות, קליטת SMS והתראה, יומן שיחות בסיסי ו־Snake בסיסי. היא גם מבקשת הרשאת הצגה מעל אפליקציות אחרות ומפעילה שכבת־על שקופה שצורכת נגיעות מעל מסך האפליקציה. המחשבון, תמונות/וידאו וזמני היום עדיין דורשים השלמה. המצלמה נפתחת דרך אפליקציית המצלמה החיצונית. לא נבדק על QIN F21 Pro.

אין כאן עדיין מימוש מלא של מסך שיחה נכנסת/שיחה פעילה, MMS, מצלמה וגלריה פנימיות, חישוב זמני היום, משחק Snake מלא, או UI Nokia המבוסס על נכסי הקושחה. ה־Multi‑Tap והניווט טעונים בדיקה ותיקון על המקשים הפיזיים של גרסת המכשיר המסוימת.

## מגבלות Android שאי אפשר לעקוף באפליקציה רגילה

- **תפקיד SMS:** Android מציג אישור מערכת לבחירת אפליקציית SMS ברירת מחדל. האפליקציה מבקשת את התפקיד בעת פתיחה רק אם אינו מוחזק. לאחר שהמשתמש אישר, אין API ציבורי שמאפשר לאפליקציה לבטל את התפקיד כשנסגרת או לגרום למערכת לשאול שוב בכל הפעלה; רק המשתמש או המערכת יכולים לשנותו. התפקיד משפיע גם מחוץ לזמן שהאפליקציה פתוחה.
- **מגע:** Activity מתעלמת ממגע בתוך החלון. שכבת־העל מבקשת `SYSTEM_ALERT_WINDOW` ומנסה לבלוע נגיעות כשהאפליקציה פעילה. Android שומר שליטה בחלונות ובמחוות מערכת מסוימות, ולכן אפליקציה רגילה אינה יכולה להבטיח חסימת Touch מוחלטת בכל ממשק או למנוע יציאה/מעבר לאפליקציה אחרת. אם ההרשאה נדחית, רק מסך האפליקציה עצמו מתעלם ממגע.
- **סרגל המצב/ההתראות:** האפליקציה מפעילה Immersive Sticky ומסתירה את סרגל המצב והניווט, כולל ניסיון הסתרה דרך `WindowInsetsController` ב־Android 11 ומעלה. היא מפעילה זאת מחדש בחזרה לאפליקציה. Android עדיין מאפשר להציג סרגלים זמניים באמצעות מחוות קצה; אפליקציה רגילה אינה יכולה לחסום לצמיתות את ה־Notification Shade. לפי תיעוד Android, השבתה קבועה נתמכת רק בפריסת Android Enterprise: DPC שמוגדר כ־Device Owner יכול לקרוא ל־`DevicePolicyManager.setStatusBarDisabled()` ולהשתמש ב־Lock Task. התקנת APK רגילה או הרשאת Overlay אינן מעניקות מעמד זה, ולכן הפרויקט אינו טוען לחסימה מלאה.
- **שיחות נכנסות:** הצגת ממשק שיחה משלנו דורשת תפקיד Dialer ברירת מחדל ו־Telecom `InCallService`. הפרויקט אינו מבקש את התפקיד הזה, ולכן Android מטפל בשיחות.
- **יציאה:** `1234` בחייגן מסיים את הפעילות. לחצן Back/End מסוגל גם לצאת ממסכים או לסגור פעילות; אפליקציה רגילה לא יכולה לנעול את המשתמש בתוכה באופן אמין בלי מצב ניהול מכשיר ייעודי.

## הסתרת סרגל המצב

בגרסה זו האפליקציה מפעילה Immersive Sticky ומסתירה את סרגל המצב והניווט, לרבות דרך `WindowInsetsController` ב־Android 11 ומעלה, ומפעילה הסתרה מחדש כשחוזרים לאפליקציה. Android עדיין מאפשר להציג סרגלים זמניים במחוות קצה. אפליקציה רגילה אינה יכולה לחסום לצמיתות את לוח ההתראות: לפי תיעוד Android, השבתה קבועה נתמכת רק בפריסת Android Enterprise, שבה אפליקציית DPC מוגדרת כ־Device Owner ומשתמשת ב־`DevicePolicyManager.setStatusBarDisabled()` וב־Lock Task. התקנת APK רגילה או הרשאת Overlay לא מעניקות מעמד Device Owner, ולכן חסימה מלאה אינה אפשרית במסגרת ההתקנה הרגילה של הפרויקט.

מקורות רשמיים:
- https://developer.android.com/develop/ui/views/immersive
- https://developer.android.com/design/ui/mobile/guides/layout-and-content/immersive-content
- https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setStatusBarDisabled(android.content.ComponentName,boolean)
- https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode

## Build

GitHub Actions בונה APK Debug בכל push ל־`main` וב־workflow ידני. הקובץ נשמר כ־Actions artifact בשם `NokiaMode-debug-apk` למשך 30 יום.

לבנייה מקומית:

1. התקן Android Studio, Android SDK Platform 35 ו־JDK 17.
2. פתח את תיקיית הפרויקט ב־Android Studio והמתן ל־Gradle Sync.
3. בחר **Build > Build APK(s)**. ה־APK ייווצר ב־`app/build/outputs/apk/debug/app-debug.apk`.

במערכת עם Gradle 8.7:

```bash
gradle --no-daemon assembleDebug
```

## התקנה ובדיקה

התקן את `app-debug.apk`, פתח את האפליקציה ואשר את תפקיד SMS בחלון המערכת אם ברצונך להפעיל את מסכי ההודעות. הרשאות אנשי קשר, יומן שיחות, שיחה ו־SMS מתבקשות רק בעת שימוש בפונקציה הרלוונטית. בדוק את כל מקשי F21 Pro בפועל; קודי KeyEvent עשויים להיות שונים בין גרסאות קושחה.

## MMI_RES

תיקיית `MMI_RES` לא הייתה זמינה בסביבת העבודה בעת יצירת הפרויקט, ולכן לא נבנה inventory ולא הועתקו נכסי Nokia. יש להוסיף אותה לפרויקט לצורך ניתוח ושיפור העיצוב.

## מקורות Android בנושא Immersive ו־Device Owner

- Immersive mode: https://developer.android.com/develop/ui/views/immersive
- הנחיות Android לתוכן Immersive ומגבלת הסתרה קבועה במכשיר אישי: https://developer.android.com/design/ui/mobile/guides/layout-and-content/immersive-content
- `DevicePolicyManager.setStatusBarDisabled`: https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setStatusBarDisabled(android.content.ComponentName,boolean)
- Lock Task למכשירים ייעודיים: https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode
