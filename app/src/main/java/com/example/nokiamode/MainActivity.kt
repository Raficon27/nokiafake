package com.example.nokiamode

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.provider.Settings
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
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

private enum class Screen { HOME, MENU, CONTACTS, CONTACT, DIALER, DEMO_CALL, THREADS, CONVERSATION, CALLLOG, COMPOSE, GALLERY, VIDEOS, CALC, SNAKE, ZMANIM, SETTINGS, KEYS, HELP }
private data class Person(val name: String, val number: String)
private data class SmsItem(val address: String, val body: String, val date: Long, val type: Int)

class MainActivity : Activity() {
    private lateinit var ui: NokiaView
    private var screen = Screen.HOME
    private var previous = Screen.HOME
    private var composeReturn = Screen.THREADS
    private var contactsReturn = Screen.MENU
    private var threadsReturn = Screen.MENU
    private var dialerReturn = Screen.MENU
    private var cursor = 0
    private var contacts = mutableListOf<Person>()
    private var contactSearch = ""
    private var smsItems = mutableListOf<SmsItem>()
    private val demoOutbox = mutableListOf<SmsItem>()
    private var smsAddresses = mutableListOf<String>()
    private var selectedThread = mutableListOf<SmsItem>()
    private val editor = MultiTapEngine()
    private val searchEditor = MultiTapEngine()
    private var demoMode = false
    private var demoCallActive = false
    private var demoSpeaker = false
    private var demoMuted = false
    private var lastPhysicalKey = ""
    private var exitReceiverRegistered = false
    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TouchShieldService.ACTION_EXIT) leaveMode()
        }
    }
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
    private val menus = listOf("גלריה", "אנשי קשר", "יומן שיחות", "מצלמה", "הודעות", "סנייק", "נינג׳ה אפ", "הגדרות", "סרטונים", "מוזיקה", "שעון מעורר", "מחשבון", "פנס", "רשמקול", "לוח שנה", "הקבצים שלי", "מונים")

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
        if (!ModeStore.isConfigured(this)) {
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }
        demoMode = ModeStore.get(this) == NokiaMode.DEMO
        ModeStore.start(this)
        applyImmersiveMode()
        ui = NokiaView(); setContentView(ui); handler.post(clockTick); startTouchShield()
        val filter = IntentFilter(TouchShieldService.ACTION_EXIT)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(exitReceiver, filter)
        exitReceiverRegistered = true
        if(intent?.action==Intent.ACTION_SENDTO && !demoMode){
            composeNumber=intent.data?.schemeSpecificPart?.substringBefore('?').orEmpty()
            editor.reset();screen=Screen.COMPOSE
        }
    }
    override fun onResume() { super.onResume(); applyImmersiveMode() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { applyImmersiveMode() }
    }
    /** Hide bars while active. Android still permits transient bars from system edge gestures. */
    private fun applyImmersiveMode() = ImmersiveUi.apply(this)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = KeypadController.map(event)
        if (event.action == KeyEvent.ACTION_UP) return key.action != KeyAction.UNKNOWN || super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        lastPhysicalKey = "KeyCode ${event.keyCode} · ScanCode ${event.scanCode} · ${key.action}"
        if (screen == Screen.KEYS) {
            if (key.action == KeyAction.SOFT_RIGHT || key.action == KeyAction.END) back()
            ui.invalidate(); return true
        }
        if (event.repeatCount > 0 && key.action !in listOf(KeyAction.UP, KeyAction.DOWN, KeyAction.LEFT, KeyAction.RIGHT)) return true
        if (screen == Screen.DIALER && key.action == KeyAction.DIGIT) {
            dial += key.symbol
            if (dial == "1234") { leaveMode(); return true }
            ui.invalidate(); return true
        }
        when (key.action) {
            KeyAction.SOFT_LEFT -> softLeft()
            KeyAction.SOFT_RIGHT, KeyAction.END -> back()
            KeyAction.UP -> move(-1)
            KeyAction.DOWN -> move(1)
            KeyAction.LEFT -> horizontal(-1)
            KeyAction.RIGHT -> horizontal(1)
            KeyAction.OK -> select()
            KeyAction.CALL -> if (screen == Screen.COMPOSE) sendSms() else callOrAnswer()
            KeyAction.DELETE -> when(screen) {
                Screen.DIALER -> dial = dial.dropLast(1)
                Screen.COMPOSE -> { editor.delete(); messageText = editor.text }
                Screen.CALC -> calcInput = calcInput.dropLast(1)
                Screen.CONTACTS -> { searchEditor.delete(); contactSearch = searchEditor.text; cursor = 0 }
                else -> back()
            }
            KeyAction.DIGIT -> enterDigit(key.symbol)
            KeyAction.UNKNOWN -> return super.dispatchKeyEvent(event)
        }
        ui.invalidate(); return true
    }
    private fun move(n:Int) { when(screen) { Screen.SNAKE -> turnSnake(0,n); Screen.MENU -> {val next=cursor+n*3;if(next in menus.indices)cursor=next}; Screen.CALC -> calcInput += if(n<0) "*" else "/"; Screen.COMPOSE -> editor.commit(); else -> cursor=(cursor+n).coerceIn(0,maxIndex()) } }
    private fun horizontal(n:Int) { when(screen) {
        Screen.SNAKE -> turnSnake(n,0)
        Screen.DEMO_CALL -> if(n<0)demoSpeaker=!demoSpeaker else demoMuted=!demoMuted
        Screen.CALC -> calcInput += if(n<0) "-" else "+"
        Screen.COMPOSE -> editor.move(n)
        Screen.MENU -> { val next=cursor+n; if(next in menus.indices && next/3==cursor/3)cursor=next }
        else -> cursor=(cursor+n).coerceIn(0,maxIndex())
    } }
    private fun maxIndex() = when(screen){Screen.MENU->menus.lastIndex; Screen.CONTACTS->(visibleContacts().size-1).coerceAtLeast(0); Screen.THREADS->(smsAddresses.size-1).coerceAtLeast(0); Screen.CONVERSATION->(selectedThread.size-1).coerceAtLeast(0); Screen.CALLLOG->(callRows.size-1).coerceAtLeast(0); Screen.SETTINGS->4; else->0}
    private fun enterDigit(d:String) { when(screen){Screen.DIALER->dial+=d; Screen.COMPOSE->{editor.insert(d,android.os.SystemClock.elapsedRealtime());messageText=editor.text}; Screen.CALC->calcInput+=when(d){"*"->"*";"#"->"/";else->d}; Screen.SNAKE->when(d){"2"->turnSnake(0,-1);"8"->turnSnake(0,1);"4"->turnSnake(-1,0);"6"->turnSnake(1,0);else->{}};Screen.CONTACTS->{searchEditor.insert(d,android.os.SystemClock.elapsedRealtime());contactSearch=searchEditor.text;cursor=0};else->{}} }
    private fun turnSnake(dx:Int,dy:Int){if(dx==-snakeDirection.first&&dy==-snakeDirection.second)return;snakeDirection=Pair(dx,dy)}
    private fun snakeStep(dx:Int,dy:Int){val head=snakeBody.first();val next=Pair((head.first+dx+20)%20,(head.second+dy+20)%20);if(snakeBody.dropLast(1).contains(next)){snakeBody.clear();snakeBody.addAll(listOf(Pair(5,5),Pair(4,5),Pair(3,5)));snakeScore=0;snakeDirection=Pair(1,0);return};snakeBody.add(0,next);snakeX=next.first;snakeY=next.second;if(next.first==foodX&&next.second==foodY){snakeScore++;foodX=(foodX*7+3)%20;foodY=(foodY*11+5)%20}else snakeBody.removeAt(snakeBody.lastIndex)}
    private fun select() { when(screen) {
        Screen.HOME -> {screen=Screen.MENU;cursor=0}
        Screen.MENU -> when(cursor){
            0->feature("gallery");1->{contactsReturn=Screen.MENU;searchEditor.reset();contactSearch="";loadContacts();open(Screen.CONTACTS)}
            2->{loadCallLog();open(Screen.CALLLOG)};3->feature("camera")
            4->{threadsReturn=Screen.MENU;loadSms();open(Screen.THREADS)};5->open(Screen.SNAKE)
            6->feature("ninja");7->open(Screen.SETTINGS);8->feature("videos")
            9->feature("music");10->feature("alarms");11->open(Screen.CALC)
            12->feature("flashlight");13->feature("recorder");14->feature("calendar")
            15->feature("files");16->feature("counters")
        }
        Screen.CONTACTS -> { val list=visibleContacts();if(list.isNotEmpty()){selected=list[cursor.coerceIn(0,list.lastIndex)];open(Screen.CONTACT)} }
        Screen.CONTACT -> { dial=selected.number;dialerReturn=Screen.CONTACT;screen=Screen.DIALER }
        Screen.DIALER -> callOrAnswer()
        Screen.THREADS -> {val address=smsAddresses.getOrNull(cursor);if(address!=null){selectedThread=smsItems.filter{it.address==address}.sortedBy{it.date}.toMutableList();composeNumber=address;open(Screen.CONVERSATION)}}
        Screen.CONVERSATION -> {editor.reset();messageText="";composeReturn=Screen.CONVERSATION;screen=Screen.COMPOSE}
        Screen.DEMO_CALL -> demoCallActive=true
        Screen.COMPOSE -> sendSms()
        Screen.CALC -> { calcInput=calculate(calcInput) }
        Screen.SETTINGS -> when(cursor) {
            1 -> { ModeStore.stop(this);stopService(Intent(this,TouchShieldService::class.java));startActivity(Intent(this,SetupActivity::class.java).putExtra("change_mode",true));finish() }
            2 -> open(Screen.KEYS)
            3 -> screen=Screen.HOME
            else -> Unit
        }
        else -> {}
    } }
    private fun open(s:Screen, text:String?=null) { previous=screen;screen=s;cursor=0; if(text!=null){notice=text;screen=Screen.HELP};if(screen==Screen.SNAKE){snakeBody.clear();snakeBody.addAll(listOf(Pair(5,5),Pair(4,5),Pair(3,5)));snakeScore=0;snakeDirection=Pair(1,0);handler.removeCallbacks(snakeTick);handler.postDelayed(snakeTick,500)}else handler.removeCallbacks(snakeTick) }
    private fun feature(name:String) { startActivity(Intent(this, FeatureActivity::class.java).putExtra("feature",name)) }
    private fun leaveMode() { ModeStore.stop(this);finish() }
    private fun back() { when(screen) {
        Screen.HOME -> return
        Screen.CONTACT -> screen=Screen.CONTACTS
        Screen.CONTACTS -> screen=contactsReturn
        Screen.CONVERSATION -> screen=Screen.THREADS
        Screen.THREADS -> screen=threadsReturn
        Screen.HELP -> screen=previous
        Screen.KEYS -> screen=Screen.SETTINGS
        Screen.DEMO_CALL -> { demoCallActive=false;screen=Screen.DIALER }
        Screen.DIALER -> {dial="";screen=dialerReturn}
        Screen.COMPOSE -> {editor.reset();messageText="";screen=composeReturn}
        else -> screen=Screen.MENU
    };cursor=0;ui.invalidate() }
    private fun softLeft(){when(screen){Screen.HOME->{contactsReturn=Screen.HOME;searchEditor.reset();contactSearch="";loadContacts();screen=Screen.CONTACTS};Screen.CONTACT->{composeNumber=selected.number;composeReturn=Screen.CONTACT;editor.reset();messageText="";screen=Screen.COMPOSE};Screen.CONVERSATION->{composeReturn=Screen.CONVERSATION;editor.reset();messageText="";screen=Screen.COMPOSE};Screen.THREADS->{contactsReturn=Screen.THREADS;searchEditor.reset();contactSearch="";loadContacts();screen=Screen.CONTACTS};Screen.COMPOSE->sendSms();else->select()}}
    private fun softRight(){if(screen==Screen.HOME){threadsReturn=Screen.HOME;loadSms();screen=Screen.THREADS}else back()}
    private fun startTouchShield() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
            try { startService(Intent(this, TouchShieldService::class.java)) }
            catch (_: Exception) { toast("לא ניתן להפעיל חסימת מגע") }
        }
    }
    private fun callOrAnswer() {
        if (screen == Screen.DEMO_CALL) { demoCallActive=true; return }
        if (screen == Screen.HOME) { dial="";dialerReturn=Screen.HOME;if(contacts.isEmpty())loadContacts();open(Screen.DIALER);return }
        if (screen == Screen.CONTACT) dial=selected.number
        if (screen != Screen.DIALER && screen != Screen.CONTACT || dial.isBlank()) return
        if (demoMode) {demoCallActive=false;open(Screen.DEMO_CALL);return}
        if(checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){toast("אין הרשאת שיחות; בדוק את ההגדרות");return}
        try{startActivity(Intent(Intent.ACTION_CALL,Uri.parse("tel:$dial")))}
        catch(e:Exception){toast("לא ניתן לבצע שיחה במכשיר") }
    }
    private fun visibleContacts()=if(contactSearch.isBlank())contacts else contacts.filter{it.number.contains(contactSearch)||it.name.contains(contactSearch,true)}
    private fun dialName():String {val digits=dial.filter{it.isDigit()};if(digits.length<3)return "";return contacts.firstOrNull{person->person.number.filter{it.isDigit()}==digits}?.name.orEmpty()}
    private fun loadContacts() { if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){toast("אין הרשאת אנשי קשר");return}; try { contacts.clear(); val c=contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER),null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" COLLATE LOCALIZED ASC"); c?.use { while(it.moveToNext()) contacts.add(Person(it.getString(0)?:"ללא שם",it.getString(1)?:"")) };ui.invalidate() }catch(_:Exception){toast("לא ניתן לקרוא אנשי קשר")} }
    private fun loadSms(){smsItems.clear();smsAddresses.clear();if(checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED){toast("אין הרשאת הודעות");return};try{contentResolver.query(Uri.parse("content://sms"),arrayOf("address","body","date","type"),null,null,"date DESC")?.use{c->while(c.moveToNext()){val address=c.getString(0)?:"";smsItems.add(SmsItem(address,c.getString(1)?:"",c.getLong(2),c.getInt(3)))}};if(demoMode)smsItems.addAll(demoOutbox);smsItems.sortByDescending{it.date};smsAddresses=smsItems.map{it.address}.filter{it.isNotBlank()}.distinct().toMutableList()}catch(_:Exception){toast("לא ניתן לקרוא הודעות")}}
    private var callRows=mutableListOf<String>()
    private fun loadCallLog(){callRows.clear();if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED){toast("אין הרשאת יומן שיחות");return};try{contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI,arrayOf(android.provider.CallLog.Calls.NUMBER,android.provider.CallLog.Calls.TYPE),null,null,android.provider.CallLog.Calls.DATE+" DESC")?.use{c->while(c.moveToNext()){val t=when(c.getInt(1)){android.provider.CallLog.Calls.INCOMING_TYPE->"נכנסת";android.provider.CallLog.Calls.MISSED_TYPE->"שלא נענתה";else->"יוצאת"};callRows.add("$t   ${c.getString(0)?:""}")}}}catch(_:Exception){toast("לא ניתן לקרוא יומן שיחות")}}
    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==10&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)loadContacts() else if(requestCode==11&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)callOrAnswer() else if(requestCode==12&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)sendSms() else if(requestCode==13&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)loadCallLog() else if(requestCode==14&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)loadSms() else if(grantResults.firstOrNull()!=PackageManager.PERMISSION_GRANTED)toast("ההרשאה לא ניתנה; הפונקציה אינה זמינה");ui.invalidate()}
    private fun sendSms(){if(composeNumber.isBlank()){toast("בחר איש קשר תחילה");return};if(messageText.isBlank()){toast("כתוב הודעה לפני השליחה");return};editor.commit();if(demoMode){val fake=SmsItem(composeNumber,messageText,System.currentTimeMillis(),2);demoOutbox.add(fake);smsItems.add(0,fake);if(!smsAddresses.contains(composeNumber))smsAddresses.add(0,composeNumber);toast("הודעת הדגמה נשמרה — לא נשלח SMS");editor.reset();messageText="";screen=Screen.THREADS;return};if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED){toast("אין הרשאת שליחת SMS");return};try{val sms=SmsManager.getDefault();val parts=sms.divideMessage(messageText);if(parts.size>1)sms.sendMultipartTextMessage(composeNumber,null,ArrayList(parts),null,null) else sms.sendTextMessage(composeNumber,null,messageText,null,null);try{val values=ContentValues().apply{put("address",composeNumber);put("body",messageText);put("date",System.currentTimeMillis());put("read",1);put("seen",1);put("type",2)};contentResolver.insert(Uri.parse("content://sms/sent"),values)}catch(_:Exception){};toast("בקשת השליחה הועברה למערכת");editor.reset();messageText="";screen=Screen.THREADS;loadSms()}catch(e:Exception){toast("שליחת הודעה נכשלה")}}
    private fun launchCamera(){if(demoMode){open(Screen.HELP,"מצלמה מדומה. במצב זה לא מצולמת תמונה.");return};try{startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))}catch(e:Exception){toast("לא נמצאה מצלמה")}}
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
    private fun precedence(op:Char)=if(op=='+'||op=='-')1 else 2
    private fun calculate(input:String):String {
        return try {
            val tokens=Regex("\\d+(?:\\.\\d+)?|[+\\-*/]").findAll(input).map{it.value}.toList()
            val nums=java.util.ArrayDeque<Double>()
            val ops=java.util.ArrayDeque<Char>()
            for(token in tokens){
                if(token.length>1||token[0].isDigit()) nums.addLast(token.toDouble())
                else {
                    val op=token[0]
                    while(ops.isNotEmpty()&&precedence(ops.last())>=precedence(op)){
                        val b=nums.removeLast();val a=nums.removeLast()
                        nums.addLast(applyOp(ops.removeLast(),a,b))
                    }
                    ops.addLast(op)
                }
            }
            while(ops.isNotEmpty()){val b=nums.removeLast();val a=nums.removeLast();nums.addLast(applyOp(ops.removeLast(),a,b))}
            nums.last().toString()
        } catch(_:Exception){input}
    }
    private fun applyOp(op:Char,a:Double,b:Double)=when(op){ '+'->a+b;'-'->a-b;'*'->a*b;else->a/b }
    override fun onDestroy(){handler.removeCallbacks(snakeTick);handler.removeCallbacks(clockTick);if(exitReceiverRegistered)unregisterReceiver(exitReceiver);if(isFinishing)ModeStore.stop(this);stopService(Intent(this,TouchShieldService::class.java));super.onDestroy()}
    private inner class NokiaView:View(this){private val p=Paint(3);private val green=Color.rgb(255,151,59);private val bg=Color.rgb(16,14,29);private var fallbackTaps=0;private var lastTap=0L
        override fun onTouchEvent(event:android.view.MotionEvent):Boolean{
            if(event.action==android.view.MotionEvent.ACTION_UP){
                val now=android.os.SystemClock.elapsedRealtime()
                val corner=event.x<width*0.18f&&event.y<height*0.14f
                fallbackTaps=if(corner){if(now-lastTap<1500)fallbackTaps+1 else 1}else 0
                lastTap=now
                if(fallbackTaps>=10)leaveMode()
            }
            return true
        }
        override fun onDraw(c:Canvas){super.onDraw(c);val sx=width/480f;val sy=height/640f;c.save();c.scale(sx,sy);p.shader=android.graphics.LinearGradient(0f,0f,0f,640f,Color.rgb(55,52,74),bg,android.graphics.Shader.TileMode.CLAMP);c.drawRect(0f,0f,480f,640f,p);p.shader=null;p.color=Color.rgb(49,48,69);c.drawRect(0f,0f,480f,34f,p);txt(c,"NOKIA",18f,23f,15f,Color.WHITE,Paint.Align.LEFT);drawSignal(c,347f,9f);drawBattery(c,379f,9f);txt(c,"$batteryPct%",423f,23f,13f,Color.WHITE);txt(c,SimpleDateFormat("HH:mm",Locale("he","IL")).format(Date()),462f,23f,15f,Color.WHITE,Paint.Align.RIGHT)
            when(screen){Screen.HOME->{drawMoon(c);txt(c,SimpleDateFormat("HH:mm",Locale("he","IL")).format(Date()),240f,172f,70f,Color.WHITE,Paint.Align.CENTER);txt(c,SimpleDateFormat("dd.MM.yyyy  EEEE",Locale("he","IL")).format(Date()),240f,211f,22f,Color.WHITE,Paint.Align.CENTER);txt(c,"◧  019",30f,277f,19f,Color.WHITE,Paint.Align.LEFT);txt(c,"SIM 1",30f,308f,16f,Color.WHITE,Paint.Align.LEFT)}
                Screen.MENU->{drawMoon(c);title(c,menus[cursor]);drawGrid(c)}
                Screen.CONTACTS->{title(c,if(contactSearch.isBlank())"אנשי קשר" else "חיפוש: $contactSearch");val list=visibleContacts().map{it.name+"   "+it.number};if(list.isEmpty())txt(c,"אין התאמות",240f,300f,20f,Color.WHITE,Paint.Align.CENTER) else drawRows(c,list,cursor)}
                Screen.CONTACT->{title(c,selected.name);txt(c,selected.number,240f,260f,28f,Color.WHITE,Paint.Align.CENTER);txt(c,"OK: חייג   CALL: שיחה",240f,500f,17f,green,Paint.Align.CENTER)}
                Screen.DIALER->{title(c,"חייגן");txt(c,dial.ifBlank{"הקלד מספר"},240f,257f,40f,Color.WHITE,Paint.Align.CENTER);val name=dialName();if(name.isNotEmpty())txt(c,name,240f,318f,24f,green,Paint.Align.CENTER);txt(c,"CALL לחיוג · מחיקה לתיקון",240f,480f,19f,Color.WHITE,Paint.Align.CENTER)}
                Screen.DEMO_CALL->{title(c,"שיחה מדומה");p.color=0xFFB5C8DA.toInt();c.drawCircle(240f,224f,92f,p);txt(c,dialName().take(1).ifBlank{"◉"},240f,258f,84f,Color.BLACK,Paint.Align.CENTER);txt(c,dialName().ifBlank{dial},240f,380f,28f,Color.WHITE,Paint.Align.CENTER);txt(c,if(demoCallActive)"שיחה מדומה פעילה" else "מתבצע חיוג מדומה...",240f,415f,20f,green,Paint.Align.CENTER);txt(c,(if(demoSpeaker)"רמקול: פועל" else "רמקול")+"        "+(if(demoMuted)"מושתק" else "השתקה"),240f,519f,19f,Color.WHITE,Paint.Align.CENTER);txt(c,"← רמקול   → השתקה   END סיום",240f,561f,15f,Color.WHITE,Paint.Align.CENTER)}
                Screen.THREADS->{title(c,"הודעות");if(smsAddresses.isEmpty())txt(c,"אין שרשורים או שאין הרשאת SMS",240f,260f,20f,Color.WHITE,Paint.Align.CENTER) else drawRows(c,smsAddresses.map{a->a+"   "+(smsItems.firstOrNull{it.address==a}?.body?:"")},cursor)}
                Screen.CONVERSATION->{title(c,composeNumber);selectedThread.drop(cursor).take(7).forEachIndexed{i,item->val who=if(item.type==2)"אני" else item.address;txt(c,"$who:",430f,120f+i*54f,17f,green);txt(c,item.body.take(38),420f,143f+i*54f,17f,Color.WHITE)}}
                Screen.CALLLOG->{title(c,"יומן שיחות");if(callRows.isEmpty())txt(c,"אין רשומות או שאין הרשאה",240f,260f,20f,Color.WHITE,Paint.Align.CENTER) else drawRows(c,callRows,cursor)}
                Screen.COMPOSE->{title(c,"כתיבת הודעה");txt(c,composeNumber,440f,115f,19f,Color.WHITE);wrap(c,messageText,35f,160f,410f,28f);txt(c,when(editor.mode){MultiTapEngine.Mode.HEBREW->"עברית";MultiTapEngine.Mode.ENGLISH->"English";MultiTapEngine.Mode.NUMBERS->"123"}+"   # החלפה · 0 רווח · * סימנים",440f,524f,17f,green);txt(c,"${messageText.length} תווים",38f,553f,16f,Color.LTGRAY,Paint.Align.LEFT)}
                Screen.GALLERY,Screen.VIDEOS->{title(c,if(screen==Screen.GALLERY)"תמונות" else "סרטונים");txt(c,"פתיחה דרך גלריית Android",240f,280f,20f,Color.WHITE,Paint.Align.CENTER)}
                Screen.CALC->{title(c,"מחשבון");txt(c,calcInput.ifBlank{"0"},450f,250f,47f,Color.WHITE);p.color=0xFFBD422F.toInt();c.drawRect(44f,318f,436f,490f,p);txt(c,"+",290f,376f,52f,Color.WHITE,Paint.Align.CENTER);txt(c,"−",190f,428f,49f,Color.WHITE,Paint.Align.CENTER);txt(c,"÷",190f,376f,49f,Color.WHITE,Paint.Align.CENTER);txt(c,"×",290f,428f,49f,Color.WHITE,Paint.Align.CENTER);txt(c,"חצים: פעולות   * כפל   # חילוק   OK =",240f,539f,16f,Color.WHITE,Paint.Align.CENTER)}
                Screen.SNAKE->{title(c,"Snake  ·  $snakeScore");for(y in 0..19)for(x in 0..19){p.color=if(snakeBody.contains(Pair(x,y)))green else if(x==foodX&&y==foodY)Color.RED else Color.DKGRAY;c.drawRect(35+x*20f,120+y*18f,50+x*20f,133+y*18f,p)};txt(c,"חצים או 2/4/6/8",240f,525f,16f,Color.WHITE,Paint.Align.CENTER)}
                Screen.ZMANIM->{title(c,"זמני היום");listOf("עלות השחר","טלית ותפילין","הנץ החמה","סוף זמן שמע","חצות היום","מנחה גדולה","מנחה קטנה","פלג המנחה","שקיעה","צאת הכוכבים").forEachIndexed{i,s->row(c,i,"$s       —",i==cursor)};txt(c,"הזמנים דורשים מיקום והגדרות הלכתיות",240f,535f,14f,Color.GRAY,Paint.Align.CENTER)}
                Screen.SETTINGS->{title(c,"הגדרות");val minutes=ModeStore.duration(this@MainActivity)/60000;listOf("מצב: "+(if(demoMode)"דמה" else "פעולה מלאה"),"החלף מצב","בדיקת מקשים","מסך הבית","זמן שימוש: ${minutes/60} שעות ${minutes%60} דקות").forEachIndexed{i,s->row(c,i,s,i==cursor)}}
                Screen.KEYS->{title(c,"בדיקת מקשים");txt(c,lastPhysicalKey,440f,220f,19f,Color.WHITE);txt(c,"לחץ על כל מקש כדי לראות את הזיהוי",440f,290f,18f,Color.WHITE);txt(c,"מקש ימני לחזרה",440f,330f,17f,green)}
                Screen.HELP->{title(c,"מידע");wrap(c,notice,35f,120f,410f,30f)} }
            p.color=Color.rgb(29,27,43);c.drawRect(0f,583f,480f,640f,p);txt(c,softLeft(),34f,620f,17f,Color.WHITE,Paint.Align.LEFT);txt(c,if(screen==Screen.MENU)"בחר" else "OK",240f,620f,18f,Color.WHITE,Paint.Align.CENTER);txt(c,softRight(),446f,620f,17f,Color.WHITE,Paint.Align.RIGHT);c.restore()}
            private fun drawMoon(c:Canvas){
                p.shader=android.graphics.RadialGradient(326f,299f,226f,intArrayOf(0xFFE8A7BD.toInt(),0xFF955D8B.toInt(),0xFF463C64.toInt()),null,android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(276f,304f,211f,p);p.shader=null
                val craters=arrayOf(floatArrayOf(189f,238f,30f),floatArrayOf(302f,198f,22f),floatArrayOf(340f,290f,37f),floatArrayOf(237f,358f,44f),floatArrayOf(369f,394f,24f),floatArrayOf(157f,327f,18f))
                for(crater in craters){p.color=0x45622F60;p.style=Paint.Style.STROKE;p.strokeWidth=6f;c.drawCircle(crater[0],crater[1],crater[2],p);p.color=0x33612C5C;p.style=Paint.Style.FILL;c.drawCircle(crater[0]+5f,crater[1]+5f,crater[2]*0.68f,p)}
            }
            private fun drawGrid(c:Canvas){
                val icons=listOf("▧","♟","☏","◉","✉","●","◆","⚙","▶","♫","◷","±","✶","◉","▦","▤","◴")
                val colors=intArrayOf(0xFFFBD54A.toInt(),0xFF27C4E3.toInt(),0xFF33CFB6.toInt(),0xFFEEEAF0.toInt(),0xFF42D3BE.toInt(),0xFFE95B9D.toInt(),0xFF7BB5FE.toInt(),0xFF7EB8EE.toInt(),0xFFE64F9D.toInt(),0xFFFBD54A.toInt(),0xFF69DBE2.toInt(),0xFF4BC4D9.toInt(),0xFFF7E493.toInt(),0xFFE7789D.toInt(),0xFFC4DCF0.toInt(),0xFF8BDC93.toInt(),0xFFF7B675.toInt())
                for(i in menus.indices){
                    val x=86f+(i%3)*154f;val y=125f+(i/3)*76f
                    if(i==cursor){p.color=0xFFFF633E.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=4f;c.drawCircle(x,y,31f,p);p.style=Paint.Style.FILL}
                    txt(c,icons[i],x,y+14f,38f,colors[i],Paint.Align.CENTER)
                }
            }
            private fun drawSignal(c:Canvas,x:Float,y:Float){p.color=Color.WHITE;for(i in 0..3)c.drawRect(x+i*6f,y+13f-i*3f,x+4f+i*6f,22f,p)}
            private fun drawBattery(c:Canvas,x:Float,y:Float){p.color=Color.rgb(242,240,216);p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;c.drawRect(x,y,x+20f,y+13f,p);c.drawRect(x+20f,y+4f,x+23f,y+9f,p);p.style=Paint.Style.FILL;p.color=green;c.drawRect(x+2f,y+2f,x+2f+16f*batteryPct.coerceIn(0,100)/100f,y+11f,p)}
            private fun title(c:Canvas,s:String){p.color=Color.rgb(48,46,67);c.drawRect(0f,34f,480f,78f,p);txt(c,s,23f,64f,21f,Color.WHITE,Paint.Align.LEFT)}
            private fun drawRows(c:Canvas,items:List<String>,selected:Int){val start=(selected-7).coerceAtLeast(0);items.drop(start).take(8).forEachIndexed{i,s->row(c,i,s,start+i==selected)}}
            private fun row(c:Canvas,i:Int,s:String,on:Boolean){val y=83f+i*59f;if(y>555) return;if(on){p.shader=android.graphics.LinearGradient(0f,y,480f,y,0xFFFFAE59.toInt(),0xFFEF6A3A.toInt(),android.graphics.Shader.TileMode.CLAMP);c.drawRect(0f,y,480f,y+57f,p);p.shader=null};val fg=Color.WHITE;txt(c,s.take(35),449f,y+36f,23f,fg,Paint.Align.RIGHT)}
            private fun softLeft()=when(screen){Screen.HOME->"אנשי קשר";Screen.CONTACT,Screen.CONVERSATION->"SMS";Screen.COMPOSE->"שלח";else->"בחר"}
            private fun softRight()=if(screen==Screen.HOME)"הודעות" else "חזרה"
            private fun txt(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,align:Paint.Align=Paint.Align.RIGHT){p.color=color;p.textSize=size;p.typeface=android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.NORMAL);p.textAlign=align;c.drawText(s,x,y,p)}
            private fun wrap(c:Canvas,s:String,x:Float,y:Float,w:Float,lh:Float){val words=s.split(" ");var line="";var yy=y;for(word in words){if(p.measureText(line+word)>w){txt(c,line,x+w,yy,18f,Color.WHITE);line="";yy+=lh};line+=word+" "};txt(c,line,x+w,yy,18f,Color.WHITE)}
        }
}
