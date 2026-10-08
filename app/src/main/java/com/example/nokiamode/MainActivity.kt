package com.example.nokiamode

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { HOME, MENU, CONTACTS, CONTACT, DIALER, THREADS, CONVERSATION, CALLLOG, COMPOSE, GALLERY, VIDEOS, CALC, SNAKE, ZMANIM, SETTINGS, HELP }
private data class Person(val name: String, val number: String)
private data class SmsItem(val address: String, val body: String, val date: Long, val type: Int)

class MainActivity : Activity() {
    private lateinit var ui: NokiaView
    private var screen = Screen.HOME
    private var previous = Screen.HOME
    private var cursor = 0
    private var contacts = mutableListOf<Person>()
    private var contactSearch = ""
    private var smsItems = mutableListOf<SmsItem>()
    private var smsAddresses = mutableListOf<String>()
    private var selectedThread = mutableListOf<SmsItem>()
    private var multiMode = 0
    private var lastMultiKey = ""
    private var lastMultiAt = 0L
    private var multiIndex = 0
    private var dial = ""
    private var messageText = ""
    private var composeNumber = ""
    private var selected = Person("", "")
    private var notice = ""
    private var calcInput = ""
    private var snakeX = 5; private var snakeY = 5; private var foodX = 10; private var foodY = 7
    private var snakeScore = 0
    private val snakeBody=mutableListOf(Pair(5,5),Pair(4,5),Pair(3,5))
    private var snakeDirection=Pair(1,0)
    private val handler=Handler(Looper.getMainLooper())
    private var batteryPct=0
    private val clockTick=object:Runnable{override fun run(){try{val b=registerReceiver(null,android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));val level=b?.getIntExtra("level",0)?:0;val scale=b?.getIntExtra("scale",100)?:100;batteryPct=if(scale>0)level*100/scale else 0}catch(_:Exception){};if(::ui.isInitialized)ui.invalidate();handler.postDelayed(this,15000)}}
    private val snakeTick=object:Runnable{override fun run(){if(screen==Screen.SNAKE){snakeStep(snakeDirection.first,snakeDirection.second);ui.invalidate();handler.postDelayed(this,520)}}}
    private val menus = listOf("אנשי קשר", "חייגן", "הודעות", "מצלמה", "תמונות", "היסטוריית שיחות", "Snake", "סרטונים", "מחשבון", "זמני היום", "הגדרות")

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        ui = NokiaView(); setContentView(ui); handler.post(clockTick); requestSmsRoleIfNeeded()
        if(intent?.action==Intent.ACTION_SENDTO){composeNumber=intent.data?.schemeSpecificPart?.substringBefore('?').orEmpty();screen=Screen.COMPOSE}
    }
    override fun onResume() { super.onResume(); @Suppress("DEPRECATION")
        run { window.decorView.systemUiVisibility = 5894 or 1024 or 512 } }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val key = event.keyCode
        val digit = when(key) { KeyEvent.KEYCODE_0->"0"; KeyEvent.KEYCODE_1->"1"; KeyEvent.KEYCODE_2->"2"; KeyEvent.KEYCODE_3->"3"; KeyEvent.KEYCODE_4->"4"; KeyEvent.KEYCODE_5->"5"; KeyEvent.KEYCODE_6->"6"; KeyEvent.KEYCODE_7->"7"; KeyEvent.KEYCODE_8->"8"; KeyEvent.KEYCODE_9->"9"; KeyEvent.KEYCODE_STAR->"*"; KeyEvent.KEYCODE_POUND->"#"; else->null }
        if (screen == Screen.DIALER && digit != null) { dial += digit; if (dial == "1234") { finish(); return true }; ui.invalidate(); return true }
        when(key) {
            KeyEvent.KEYCODE_SOFT_LEFT -> { softLeft(); ui.invalidate() }
            KeyEvent.KEYCODE_SOFT_RIGHT -> { softRight(); ui.invalidate() }
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> { if(screen==Screen.SNAKE)turnSnake(-1,0) else if(screen==Screen.CALC)calcInput+="-" else cursor=(cursor-1).coerceAtLeast(0) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if(screen==Screen.SNAKE)turnSnake(1,0) else if(screen==Screen.CALC)calcInput+="+" else cursor=(cursor+1).coerceAtMost(maxIndex()) }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> select()
            KeyEvent.KEYCODE_CALL -> {if(screen==Screen.COMPOSE)sendSms() else callOrAnswer()}
            KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> back()
            KeyEvent.KEYCODE_DEL -> { when(screen){Screen.DIALER->dial=dial.dropLast(1); Screen.COMPOSE->messageText=messageText.dropLast(1); Screen.CALC->calcInput=calcInput.dropLast(1); Screen.CONTACTS->{contactSearch=contactSearch.dropLast(1);cursor=0};else->back()} }
            else -> if (digit != null) enterDigit(digit) else return super.dispatchKeyEvent(event)
        }
        ui.invalidate(); return true
    }
    private fun move(n:Int) { when(screen) { Screen.SNAKE -> turnSnake(0,n); Screen.COMPOSE -> { messageText += if(n<0) " " else "א" }; else -> cursor=(cursor+n).coerceIn(0,maxIndex()) } }
    private fun maxIndex() = when(screen){Screen.MENU->menus.lastIndex; Screen.CONTACTS->(visibleContacts().size-1).coerceAtLeast(0); Screen.THREADS->(smsAddresses.size-1).coerceAtLeast(0); Screen.CONVERSATION->(selectedThread.size-1).coerceAtLeast(0); Screen.CALLLOG->(callRows.size-1).coerceAtLeast(0); Screen.SETTINGS->2; else->0}
    private fun enterDigit(d:String) { when(screen){Screen.DIALER->dial+=d; Screen.COMPOSE->multiTap(d); Screen.CALC->calcInput+=when(d){"*"->"*";"#"->"/";else->d}; Screen.SNAKE->when(d){"2"->turnSnake(0,-1);"8"->turnSnake(0,1);"4"->turnSnake(-1,0);"6"->turnSnake(1,0);else->{}};Screen.CONTACTS->{contactSearch+=d;cursor=0};else->{}} }
    private fun turnSnake(dx:Int,dy:Int){if(dx==-snakeDirection.first&&dy==-snakeDirection.second)return;snakeDirection=Pair(dx,dy)}
    private fun snakeStep(dx:Int,dy:Int){val head=snakeBody.first();val next=Pair((head.first+dx+20)%20,(head.second+dy+20)%20);if(snakeBody.dropLast(1).contains(next)){snakeBody.clear();snakeBody.addAll(listOf(Pair(5,5),Pair(4,5),Pair(3,5)));snakeScore=0;snakeDirection=Pair(1,0);return};snakeBody.add(0,next);snakeX=next.first;snakeY=next.second;if(next.first==foodX&&next.second==foodY){snakeScore++;foodX=(foodX*7+3)%20;foodY=(foodY*11+5)%20}else snakeBody.removeAt(snakeBody.lastIndex)}
    private fun select() { when(screen) {
        Screen.HOME -> {screen=Screen.MENU;cursor=0}
        Screen.MENU -> when(cursor){0->{contactSearch="";loadContacts();open(Screen.CONTACTS)};1->open(Screen.DIALER);2->{loadSms();open(Screen.THREADS)};3->launchCamera();4->open(Screen.GALLERY);5->{loadCallLog();open(Screen.CALLLOG)};6->open(Screen.SNAKE);7->open(Screen.VIDEOS);8->open(Screen.CALC);9->open(Screen.ZMANIM);10->open(Screen.SETTINGS)}
        Screen.CONTACTS -> { val list=visibleContacts();if(list.isNotEmpty()){selected=list[cursor.coerceIn(0,list.lastIndex)];open(Screen.CONTACT)} }
        Screen.CONTACT -> { dial=selected.number;screen=Screen.DIALER }
        Screen.DIALER -> callOrAnswer()
        Screen.THREADS -> {val address=smsAddresses.getOrNull(cursor);if(address!=null){selectedThread=smsItems.filter{it.address==address}.sortedBy{it.date}.toMutableList();composeNumber=address;open(Screen.CONVERSATION)}}
        Screen.CONVERSATION -> {messageText="";multiMode=0;screen=Screen.COMPOSE}
        Screen.COMPOSE -> sendSms()
        Screen.CALC -> { calcInput=calculate(calcInput) }
        Screen.SETTINGS -> if(cursor==2){screen=Screen.HOME}
        else -> {}
    } }
    private fun open(s:Screen, text:String?=null) { previous=screen;screen=s;cursor=0; if(text!=null){notice=text;screen=Screen.HELP};if(screen==Screen.SNAKE){snakeBody.clear();snakeBody.addAll(listOf(Pair(5,5),Pair(4,5),Pair(3,5)));snakeScore=0;snakeDirection=Pair(1,0);handler.removeCallbacks(snakeTick);handler.postDelayed(snakeTick,500)}else handler.removeCallbacks(snakeTick) }
    private fun back() { if(screen==Screen.HOME){finish()} else if(screen==Screen.CONTACT){screen=Screen.CONTACTS} else if(screen==Screen.HELP){screen=previous} else if(screen==Screen.DIALER){dial="";screen=Screen.MENU} else if(screen==Screen.COMPOSE){screen=Screen.THREADS} else screen=Screen.MENU;cursor=0;ui.invalidate() }
    private fun softLeft(){when(screen){Screen.HOME->{contactSearch="";loadContacts();screen=Screen.CONTACTS};Screen.CONTACT->{composeNumber=selected.number;messageText="";multiMode=0;screen=Screen.COMPOSE};Screen.CONVERSATION->{messageText="";multiMode=0;screen=Screen.COMPOSE};Screen.THREADS->{contactSearch="";loadContacts();screen=Screen.CONTACTS};Screen.COMPOSE->sendSms();else->select()}}
    private fun softRight(){if(screen==Screen.HOME){loadSms();screen=Screen.THREADS}else back()}
    private fun requestSmsRoleIfNeeded(){if(android.os.Build.VERSION.SDK_INT>=29){val manager=getSystemService(RoleManager::class.java);if(manager!=null&&manager.isRoleAvailable(RoleManager.ROLE_SMS)&&!manager.isRoleHeld(RoleManager.ROLE_SMS)){startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_SMS),20)}}}
    @Deprecated("Role request result") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==20){if(resultCode==RESULT_OK){toast("אפליקציית SMS נבחרה");loadSms()}else toast("ללא תפקיד SMS, גישה לשרשורים מוגבלת")}}
    private fun multiTap(d:String){if(d=="#"){multiMode=(multiMode+1)%3;lastMultiKey="";toast(when(multiMode){0->"עברית";1->"English";else->"123"});return};if(d=="0"){messageText+=" ";lastMultiKey="";return};val letters=when(multiMode){0->mapOf("2" to "אבג","3" to "דהו","4" to "זחט","5" to "יכל","6" to "מנס","7" to "עפצ","8" to "קרש","9" to "שתץ");1->mapOf("2" to "abc2","3" to "def3","4" to "ghi4","5" to "jkl5","6" to "mno6","7" to "pqrs7","8" to "tuv8","9" to "wxyz9");else->emptyMap()};val chars=letters[d];if(chars==null){messageText+=d;lastMultiKey="";return};val now=System.currentTimeMillis();if(lastMultiKey==d&&now-lastMultiAt<900&&messageText.isNotEmpty()){messageText=messageText.dropLast(1);multiIndex=(multiIndex+1)%chars.length;messageText+=chars[multiIndex]}else{multiIndex=0;messageText+=chars[0]};lastMultiKey=d;lastMultiAt=now}
    private fun callOrAnswer() { if(screen==Screen.CONTACT)dial=selected.number;if(dial.isBlank()) return; if(checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.CALL_PHONE),11);return}; try{startActivity(Intent(Intent.ACTION_CALL,Uri.parse("tel:$dial")))}catch(e:Exception){toast("לא ניתן לבצע שיחה במכשיר") } }
    private fun visibleContacts()=if(contactSearch.isBlank())contacts else contacts.filter{it.number.contains(contactSearch)||it.name.contains(contactSearch,true)}
    private fun loadContacts() { if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS),10);return}; try { contacts.clear(); val c=contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER),null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" COLLATE LOCALIZED ASC"); c?.use { while(it.moveToNext()) contacts.add(Person(it.getString(0)?:"ללא שם",it.getString(1)?:"")) };ui.invalidate() }catch(_:Exception){} }
    private fun loadSms(){smsItems.clear();smsAddresses.clear();if(checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.READ_SMS),14);return};try{contentResolver.query(Uri.parse("content://sms"),arrayOf("address","body","date","type"),null,null,"date DESC")?.use{c->while(c.moveToNext()){val address=c.getString(0)?:"";smsItems.add(SmsItem(address,c.getString(1)?:"",c.getLong(2),c.getInt(3)));if(address.isNotBlank()&&!smsAddresses.contains(address))smsAddresses.add(address)}}}catch(_:Exception){}}
    private var callRows=mutableListOf<String>()
    private fun loadCallLog(){callRows.clear();if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG),13);return};try{contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI,arrayOf(android.provider.CallLog.Calls.NUMBER,android.provider.CallLog.Calls.TYPE),null,null,android.provider.CallLog.Calls.DATE+" DESC")?.use{c->while(c.moveToNext()){val t=when(c.getInt(1)){android.provider.CallLog.Calls.INCOMING_TYPE->"נכנסת";android.provider.CallLog.Calls.MISSED_TYPE->"שלא נענתה";else->"יוצאת"};callRows.add("$t   ${c.getString(0)?:""}")}}}catch(_:Exception){}}
    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==10&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)loadContacts() else if(requestCode==11&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)callOrAnswer() else if(requestCode==12&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)sendSms() else if(requestCode==13&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)loadCallLog() else if(requestCode==14&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)loadSms() else if(grantResults.firstOrNull()!=PackageManager.PERMISSION_GRANTED)toast("ההרשאה לא ניתנה; הפונקציה אינה זמינה");ui.invalidate()}
    private fun sendSms(){if(composeNumber.isBlank()){toast("בחר איש קשר תחילה");return};if(messageText.isBlank()){toast("כתוב הודעה לפני השליחה");return};if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.SEND_SMS),12);return};try{val sms=SmsManager.getDefault();val parts=sms.divideMessage(messageText);if(parts.size>1)sms.sendMultipartTextMessage(composeNumber,null,ArrayList(parts),null,null) else sms.sendTextMessage(composeNumber,null,messageText,null,null);try{val values=ContentValues().apply{put("address",composeNumber);put("body",messageText);put("date",System.currentTimeMillis());put("read",1);put("seen",1);put("type",2)};contentResolver.insert(Uri.parse("content://sms/sent"),values)}catch(_:Exception){};toast("ההודעה נשלחה");messageText="";screen=Screen.THREADS;loadSms()}catch(e:Exception){toast("שליחת הודעה נכשלה")}}
    private fun launchCamera(){try{startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))}catch(e:Exception){toast("לא נמצאה מצלמה")}}
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
    private fun calculate(input:String):String{try{val tokens=Regex("\\d+(?:\\.\\d+)?|[+\\-*/]").findAll(input).map{it.value}.toList();val nums=java.util.ArrayDeque<Double>();val ops=java.util.ArrayDeque<Char>();fun prec(c:Char)=if(c=='+'||c=='-')1 else 2;for(t in tokens){if(t.length>1||t[0].isDigit())nums.addLast(t.toDouble())else{val op=t[0];while(ops.isNotEmpty()&&prec(ops.last())>=prec(op)){val b=nums.removeLast();val a=nums.removeLast();nums.addLast(when(ops.removeLast()){ '+'->a+b;'-'->a-b;'*'->a*b;else->a/b})};ops.addLast(op)}};while(ops.isNotEmpty()){val b=nums.removeLast();val a=nums.removeLast();nums.addLast(when(ops.removeLast()){ '+'->a+b;'-'->a-b;'*'->a*b;else->a/b})};return nums.last().toString()}catch(_:Exception){return input}}
    override fun onDestroy(){handler.removeCallbacks(snakeTick);handler.removeCallbacks(clockTick);super.onDestroy()}
    private inner class NokiaView:View(this){private val p=Paint(3);private val green=Color.rgb(154,205,50);private val bg=Color.rgb(20,24,20);override fun onTouchEvent(event:android.view.MotionEvent)=true;override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(bg);val sx=width/480f;val sy=height/640f;c.save();c.scale(sx,sy);p.color=Color.BLACK;c.drawRect(0f,0f,480f,640f,p);p.color=green;c.drawRect(0f,0f,480f,32f,p);txt(c,"NOKIA",18f,23f,16f,Color.BLACK);txt(c,"▮ $batteryPct%",325f,23f,14f,Color.BLACK);txt(c,SimpleDateFormat("HH:mm",Locale("he","IL")).format(Date()),420f,23f,16f,Color.BLACK,Paint.Align.RIGHT)
            when(screen){Screen.HOME->{txt(c,"${SimpleDateFormat("HH:mm",Locale("he","IL")).format(Date())}",240f,260f,76f,Color.WHITE,Paint.Align.CENTER);txt(c,SimpleDateFormat("EEEE  d/M",Locale("he","IL")).format(Date()),240f,300f,20f,green,Paint.Align.CENTER);txt(c,"לחץ OK לפתיחת התפריט",240f,500f,18f,Color.LTGRAY,Paint.Align.CENTER)}
                Screen.MENU->{title(c,"תפריט");drawRows(c,menus,cursor)}
                Screen.CONTACTS->{title(c,if(contactSearch.isBlank())"אנשי קשר" else "חיפוש: $contactSearch");val list=visibleContacts().map{it.name+"   "+it.number};if(list.isEmpty())txt(c,"אין התאמות",240f,300f,20f,Color.WHITE,Paint.Align.CENTER) else drawRows(c,list,cursor)}
                Screen.CONTACT->{title(c,selected.name);txt(c,selected.number,240f,260f,28f,Color.WHITE,Paint.Align.CENTER);txt(c,"OK: חייג   CALL: שיחה",240f,500f,17f,green,Paint.Align.CENTER)}
                Screen.DIALER->{title(c,"חייגן");txt(c,dial.ifBlank{"הקלד מספר"},240f,290f,38f,Color.WHITE,Paint.Align.CENTER);txt(c,"1234 לסיום מצב Nokia",240f,390f,16f,Color.GRAY,Paint.Align.CENTER)}
                Screen.THREADS->{title(c,"הודעות");if(smsAddresses.isEmpty())txt(c,"אין שרשורים או שאין הרשאת SMS",240f,260f,20f,Color.WHITE,Paint.Align.CENTER) else drawRows(c,smsAddresses.map{a->a+"   "+(smsItems.firstOrNull{it.address==a}?.body?:"")},cursor)}
                Screen.CONVERSATION->{title(c,composeNumber);selectedThread.drop(cursor).take(7).forEachIndexed{i,item->val who=if(item.type==2)"אני" else item.address;txt(c,"$who:",430f,120f+i*54f,17f,green);txt(c,item.body.take(38),420f,143f+i*54f,17f,Color.WHITE)}}
                Screen.CALLLOG->{title(c,"יומן שיחות");if(callRows.isEmpty())txt(c,"אין רשומות או שאין הרשאה",240f,260f,20f,Color.WHITE,Paint.Align.CENTER) else drawRows(c,callRows,cursor)}
                Screen.COMPOSE->{title(c,"כתיבת הודעה");txt(c,composeNumber,240f,100f,19f,green,Paint.Align.CENTER);wrap(c,messageText,35f,160f,410f,24f);txt(c,when(multiMode){0->"עברית";1->"English";else->"123"}+"   # החלפה · 0 רווח · DEL מחיקה",240f,500f,15f,Color.GRAY,Paint.Align.CENTER)}
                Screen.GALLERY,Screen.VIDEOS->{title(c,if(screen==Screen.GALLERY)"תמונות" else "סרטונים");txt(c,"פתיחה דרך גלריית Android",240f,280f,20f,Color.WHITE,Paint.Align.CENTER)}
                Screen.CALC->{title(c,"מחשבון");txt(c,calcInput,240f,260f,34f,Color.WHITE,Paint.Align.CENTER);txt(c,"ספרות להקלדה · OK לחישוב",240f,480f,17f,green,Paint.Align.CENTER)}
                Screen.SNAKE->{title(c,"Snake  ·  $snakeScore");for(y in 0..19)for(x in 0..19){p.color=if(snakeBody.contains(Pair(x,y)))green else if(x==foodX&&y==foodY)Color.RED else Color.DKGRAY;c.drawRect(35+x*20f,120+y*18f,50+x*20f,133+y*18f,p)};txt(c,"חצים או 2/4/6/8",240f,525f,16f,Color.WHITE,Paint.Align.CENTER)}
                Screen.ZMANIM->{title(c,"זמני היום");listOf("עלות השחר","טלית ותפילין","הנץ החמה","סוף זמן שמע","חצות היום","מנחה גדולה","מנחה קטנה","פלג המנחה","שקיעה","צאת הכוכבים").forEachIndexed{i,s->row(c,i,"$s       —",i==cursor)};txt(c,"הזמנים דורשים מיקום והגדרות הלכתיות",240f,535f,14f,Color.GRAY,Paint.Align.CENTER)}
                Screen.SETTINGS->{title(c,"הגדרות");listOf("שפה: עברית","תצוגה: ירוק","חזרה למסך בית").forEachIndexed{i,s->row(c,i,s,i==cursor)}}
                Screen.HELP->{title(c,"מידע");wrap(c,notice,35f,120f,410f,30f)} }
            p.color=green;c.drawRect(0f,590f,480f,640f,p);txt(c,softLeft(),70f,622f,17f,Color.BLACK);txt(c,softRight(),410f,622f,17f,Color.BLACK,Paint.Align.RIGHT);c.restore()}
            private fun title(c:Canvas,s:String){txt(c,s,240f,70f,24f,green,Paint.Align.CENTER)}
            private fun drawRows(c:Canvas,items:List<String>,selected:Int){val start=(selected-8).coerceAtLeast(0);items.drop(start).take(10).forEachIndexed{i,s->row(c,i,s,start+i==selected)}}
            private fun row(c:Canvas,i:Int,s:String,on:Boolean){val y=96f+i*48f;if(y>570) return;if(on){p.color=green;c.drawRect(12f,y,468f,y+42,p)};val fg=if(on)Color.BLACK else Color.WHITE;p.color=fg;p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;c.drawRoundRect(RectF(22f,y+9,44f,y+32),4f,4f,p);p.style=Paint.Style.FILL;val mark=when{ s.contains("אנשי קשר")->"א";s.contains("הודעות")->"✉";s.contains("מצלמה")->"◉";s.contains("תמונות")->"▣";s.contains("שיחות")->"☎";s.contains("Snake")->"S";s.contains("מחשבון")->"±";s.contains("זמני")->"☼";s.contains("הגדרות")->"⚙";else->"›"};txt(c,mark,33f,y+26,13f,fg,Paint.Align.CENTER);txt(c,s,440f,y+29,19f,fg,Paint.Align.RIGHT)}
            private fun softLeft()=when(screen){Screen.HOME->"אנשי קשר";Screen.CONTACT,Screen.CONVERSATION->"SMS";Screen.COMPOSE->"שלח";else->"בחר"}
            private fun softRight()=if(screen==Screen.HOME)"הודעות" else "חזרה"
            private fun txt(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,align:Paint.Align=Paint.Align.RIGHT){p.color=color;p.textSize=size;p.typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL);p.textAlign=align;c.drawText(s,x,y,p)}
            private fun wrap(c:Canvas,s:String,x:Float,y:Float,w:Float,lh:Float){val words=s.split(" ");var line="";var yy=y;for(word in words){if(p.measureText(line+word)>w){txt(c,line,x+w,yy,18f,Color.WHITE);line="";yy+=lh};line+=word+" "};txt(c,line,x+w,yy,18f,Color.WHITE)}
        }
}
