package com.ewa.admin;

import android.app.Activity;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore.SecretKeyEntry;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import android.util.Base64;

public class MainActivity extends Activity {
    private static final String API = "/wp-json/ewa/v1";
    private static final String APP_VERSION = "0.7.2";
    private static final String KEY_ALIAS = "EWA_ADMIN_TOKEN_KEY";
    private SharedPreferences prefs;
    private LinearLayout root, content;
    private TextView status, pageTitle;
    private boolean startupQueueFetched = false;
    private final Handler liveHandler=new Handler(Looper.getMainLooper());
    private String currentSection="Dashboard";
    private final Runnable liveTick=new Runnable(){public void run(){if(!readToken().isEmpty())syncLiveActivity();liveHandler.postDelayed(this,60000);}};

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ewa_admin", MODE_PRIVATE);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},700);
        if (!readToken().isEmpty() && !cleanBase(prefs.getString("base", "")).isEmpty()) showApp(); else showLogin();
    }

    @Override protected void onResume(){super.onResume();liveHandler.removeCallbacks(liveTick);if(!readToken().isEmpty())syncLiveActivity();liveHandler.postDelayed(liveTick,60000);}
    @Override protected void onPause(){super.onPause();liveHandler.removeCallbacks(liveTick);}

    private void syncLiveActivity(){
        long since=prefs.getLong("last_event_id",0);
        fetch("/admin/app/activity?since_id="+since,obj->{
            JSONObject j=asObject(obj);if(j==null)return;JSONArray events=j.optJSONArray("events");long latest=j.optLong("latest_id",since);
            if(events!=null)for(int i=0;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(e!=null)showLocalEvent(e);}
            if(latest>since)prefs.edit().putLong("last_event_id",latest).apply();
            // Refresh the visible section after each one-minute sync so counts/lists stay current.
            if(events!=null&&events.length()>0)open(currentSection);
        });
    }
    private void showLocalEvent(JSONObject e){
        if(e.optLong("id",0)<=prefs.getLong("last_notified_event_id",0))return;
        android.app.NotificationManager nm=(android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new android.app.NotificationChannel("ewa_live_updates","EWA Live Updates",android.app.NotificationManager.IMPORTANCE_HIGH));
        Intent i=new Intent(this,MainActivity.class).putExtra("ewa_section",e.optString("section","Dashboard"));
        android.app.PendingIntent pi=android.app.PendingIntent.getActivity(this,(int)e.optLong("id"),i,android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b=new NotificationCompat.Builder(this,"ewa_live_updates").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(e.optString("title","EWA Update")).setContentText(e.optString("body","New activity"))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(e.optString("body","New activity"))).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pi).setDefaults(NotificationCompat.DEFAULT_ALL);
        nm.notify((int)e.optLong("id"),b.build());
        prefs.edit().putLong("last_notified_event_id",e.optLong("id")).apply();
    }

    private void showLogin() {
        root = base();
        LinearLayout box = cardBox("EWA Admin", "Administrator Control Center");
        EditText base = input("WordPress HTTPS URL", false); base.setText(prefs.getString("base", ""));
        EditText username = input("Administrator username", false);
        EditText password = input("Password", true);
        box.addView(base); box.addView(username); box.addView(password);
        Button sign = button("Sign in"); box.addView(sign);
        status = text(""); box.addView(status);
        sign.setOnClickListener(v -> {
            String server = cleanBase(base.getText().toString());
            String user = username.getText().toString().trim();
            String pass = password.getText().toString();
            if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) { status.setText("Server URL, username and password are required."); return; }
            if (!server.startsWith("https://")) { status.setText("Use an HTTPS WordPress URL."); return; }
            status.setText("Connecting to WordPress...");
            sign.setEnabled(false);
            new Thread(() -> {
                try {
                    String normalized = normalizeServerUrl(server);
                    JSONObject body = new JSONObject().put("username", user).put("password", pass).put("device_label", "EWA Admin Android " + APP_VERSION);
                    String raw;
                    try {
                        raw = request(normalized + API + "/admin/app/login", "POST", body.toString(), null);
                    } catch (IOException first) {
                        // Some WordPress/hosting configurations do not expose pretty REST URLs.
                        // Retry the exact same REST route through index.php?rest_route=.
                        if (first.getMessage() != null && first.getMessage().contains("HTTP 404")) {
                            raw = request(normalized + "/index.php?rest_route=" + enc("/ewa/v1/admin/app/login"), "POST", body.toString(), null);
                        } else {
                            throw first;
                        }
                    }
                    JSONObject r = new JSONObject(raw);
                    String token = r.optString("token", "");
                    if (token.isEmpty()) throw new IOException(extractApiError(r, "Server did not return an administrator token."));
                    writeToken(token);
                    prefs.edit().putString("base", normalized).apply();
                    runOnUiThread(this::showApp);
                } catch (Exception e) {
                    runOnUiThread(() -> { sign.setEnabled(true); status.setText("Sign-in failed: " + safeError(e)); });
                }
            }).start();
        });
        root.addView(box); setContentView(root);
    }

    private void showApp() {
        root = base();
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        pageTitle = title("EWA Admin"); header.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button logout = button("Sign out"); header.addView(logout); logout.setOnClickListener(v -> logout()); root.addView(header);
        status = text(""); root.addView(status);
        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        LinearLayout nav = new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"Dashboard", "Students", "Memberships", "Courses", "Orders", "Video Sessions", "Analytics", "Fee & Accounts", "Notifications", "WhatsApp"};
        for (String label : labels) { Button b = button(label); nav.addView(b); b.setOnClickListener(v -> open(label)); }
        navScroll.addView(nav); root.addView(navScroll);
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        String target=getIntent().getStringExtra("ewa_section");
        open(target==null||target.isEmpty()?"Dashboard":target);
        fetchQueueSilently();
    }

    private void open(String section) {
        currentSection=section;
        content.removeAllViews(); status.setText(""); pageTitle.setText(section);
        switch(section) {
            case "Dashboard": loadOverview(); break;
            case "Students": loadStudents(); break;
            case "Memberships": loadMemberships(); break;
            case "Courses": loadCourses(); break;
            case "Orders": loadOrders(); break;
            case "Video Sessions": loadVideoSessions(); break;
            case "Analytics": loadAnalytics(); break;
            case "Fee & Accounts": loadPaymentDetails(); break;
            case "Notifications": loadNotifications(); break;
            case "WhatsApp": loadWhatsApp(); break;
        }
    }

    private void loadOverview() {
        content.addView(card("Welcome back, Teacher","Your EWA control center is ready. Monitor students, memberships, orders, analytics and WhatsApp communication from one place."));
        Button refresh=button("Refresh Dashboard"); content.addView(refresh); refresh.setOnClickListener(v->open("Dashboard"));
        fetch("/admin/app/overview", obj -> { JSONObject j=asObject(obj); if(j==null){content.addView(card("Dashboard unavailable","No overview data was returned by WordPress."));return;}
            addMetric("Students",j.optInt("students")); addMetric("Active Members",j.optInt("members")); addMetric("Pending Memberships",j.optInt("pending_memberships")); addMetric("Orders",j.optInt("orders")); addMetric("WhatsApp Queue",j.optInt("whatsapp_queue")); });
    }
    private void loadStudents() {
        LinearLayout row=new LinearLayout(this); EditText q=input("Search students",false); Button go=button("Search"); row.addView(q,new LinearLayout.LayoutParams(0,-2,1));row.addView(go);content.addView(row);
        Runnable load=()->fetch("/admin/app/students?limit=100&q="+enc(q.getText().toString()),obj->{content.removeViews(1,Math.max(0,content.getChildCount()-1));JSONArray a=asArray(obj);if(a!=null)for(int i=0;i<a.length();i++)addStudent(a.optJSONObject(i));}); go.setOnClickListener(v->load.run());load.run();
    }
    private void addStudent(JSONObject x){if(x==null)return;content.addView(card(x.optString("name","Student"),"Email: "+x.optString("email","-")+"\nClass: "+x.optString("class_name","-")+"\nWhatsApp: "+x.optString("whatsapp","-")+"\nState: "+x.optString("account_state","-")));}
    private void loadMemberships(){fetch("/admin/app/memberships?limit=100",obj->{JSONArray a=asArray(obj);if(a!=null)for(int i=0;i<a.length();i++)addMembership(a.optJSONObject(i));});}
    private void addMembership(JSONObject x){
        if(x==null)return;
        String st=x.optString("status","PENDING").toUpperCase(Locale.US);
        String displayStatus="ACTIVE".equals(st)?"APPROVED":st;
        String body="Status: "+displayStatus+"\nEmail: "+x.optString("email","-")+"\nFather: "+x.optString("father_name","-")+"\nMobile: "+x.optString("mobile",x.optString("whatsapp","-"))+"\nClass: "+x.optString("class_name","-")+"\nPayment reference: "+x.optString("payment_reference","-")+"\nSubmitted: "+x.optString("payment_submitted_at","-");
        LinearLayout box=cardBox(x.optString("name","Student"),body);
        String proof=x.optString("payment_proof_url","");
        if(!proof.isEmpty()){
            Button view=button("Open Payment Screenshot"); box.addView(view);
            view.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(proof)));}catch(Exception e){status.setText("Unable to open payment screenshot.");}});
        }
        int id=x.optInt("id");
        LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setPadding(0,4,0,0);
        Button approve=button("Approve"); Button reject=button("Reject"); Button suspend=button("Suspend");
        actions.addView(approve,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(reject,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(suspend,new LinearLayout.LayoutParams(0,-2,1));
        box.addView(actions);
        approve.setEnabled(!"ACTIVE".equals(st));
        reject.setEnabled(!"REJECTED".equals(st));
        suspend.setEnabled("ACTIVE".equals(st));
        approve.setOnClickListener(v->membershipAction(id,"approve"));
        reject.setOnClickListener(v->membershipAction(id,"reject"));
        suspend.setOnClickListener(v->membershipAction(id,"suspend"));
        content.addView(box);
    }
    private void membershipAction(int id,String action){
        try{
            JSONObject b=new JSONObject().put("action",action);
            post("/admin/app/memberships/"+id+"/action",b,o->{
                JSONObject r=asObject(o);
                String result=r==null?"Membership updated.":"Membership updated: "+r.optString("status",action.toUpperCase(Locale.US));
                status.setText(result+". WhatsApp notification has been queued when applicable.");
                open("Memberships");
            });
        }catch(Exception e){status.setText("Could not update membership: "+safeError(e));}
    }
    private void loadPaymentDetails(){fetch("/admin/app/payment-details",obj->{JSONObject j=asObject(obj);if(j==null)return;LinearLayout box=cardBox("Fee & Account Details","Set the membership fee and payment accounts used by the student dashboard.");EditText fee=input("Membership fee",false),jnum=input("JazzCash number",false),jtitle=input("JazzCash account title",false),enum_=input("Easypaisa number",false),etitle=input("Easypaisa account title",false),bank=input("Bank name",false),acct=input("Bank account number",false),btitle=input("Bank account title",false),instructions=input("Payment instructions",false);instructions.setMinLines(3);fee.setText(j.optString("membership_fee",""));jnum.setText(j.optString("jazzcash_number",""));jtitle.setText(j.optString("jazzcash_title",""));enum_.setText(j.optString("easypaisa_number",""));etitle.setText(j.optString("easypaisa_title",""));bank.setText(j.optString("bank_name",""));acct.setText(j.optString("bank_account",""));btitle.setText(j.optString("bank_title",""));instructions.setText(j.optString("payment_instructions",""));for(EditText e:new EditText[]{fee,jnum,jtitle,enum_,etitle,bank,acct,btitle,instructions})box.addView(e);Button save=button("Save Fee & Account Details");box.addView(save);save.setOnClickListener(v->{try{JSONObject b=new JSONObject().put("membership_fee",fee.getText().toString()).put("jazzcash_number",jnum.getText().toString()).put("jazzcash_title",jtitle.getText().toString()).put("easypaisa_number",enum_.getText().toString()).put("easypaisa_title",etitle.getText().toString()).put("bank_name",bank.getText().toString()).put("bank_account",acct.getText().toString()).put("bank_title",btitle.getText().toString()).put("payment_instructions",instructions.getText().toString());post("/admin/app/payment-details",b,o->open("Fee & Accounts"));}catch(Exception e){status.setText("Could not save fee and account details.");}});content.addView(box);});}
    private void loadCourses(){fetch("/admin/app/courses",obj->{JSONArray a=asArray(obj);if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null){JSONArray cn=x.optJSONArray("class_names");String classes="-";if(cn!=null&&cn.length()>0){StringBuilder cb=new StringBuilder();for(int k=0;k<cn.length();k++){if(k>0)cb.append(", ");cb.append(cn.optString(k));}classes=cb.toString();}content.addView(card(x.optString("name","Course"),"Classes: "+classes+"\nLessons: "+x.optInt("lesson_count")+"\nPublished: "+(x.optInt("published")==1?"Yes":"No")));};}});}
    private void loadOrders(){fetch("/admin/app/orders?limit=100",obj->{JSONArray a=asArray(obj);if(a==null||a.length()==0){content.addView(card("No orders","No book orders were returned by EWA Core."));return;}for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;String customer=x.optString("name",x.optString("user_name","-"));String contact=x.optString("contact1","-");String contact2=x.optString("contact2","");String body="Customer: "+customer+"\nContact: "+contact+(contact2.isEmpty()?"":" / "+contact2)+"\nCity: "+x.optString("city","-")+"\nDelivery Address: "+x.optString("address","-")+"\nQuantity: "+x.optInt("quantity",1)+"\nAmount: Rs. "+x.optString("amount","0")+"\nStatus: "+x.optString("status","-")+"\nCreated: "+x.optString("created_at","-");content.addView(card(x.optString("order_code","Order"),body));}});}
    private void loadVideoSessions(){
        Button refresh=button("Refresh Video Sessions"); content.addView(refresh); refresh.setOnClickListener(v->open("Video Sessions"));
        fetch("/admin/app/video-sessions?limit=100",obj->{JSONArray a=asArray(obj);if(a==null||a.length()==0){content.addView(card("No video sessions","No one-on-one video session requests were returned."));return;}for(int i=0;i<a.length();i++)addVideoSession(a.optJSONObject(i));});
    }
    private void addVideoSession(JSONObject x){
        if(x==null)return;
        String st=x.optString("status","-").toUpperCase(Locale.US);
        String requested=x.optString("requested_date","-")+" "+shortTime(x.optString("requested_time",""));
        String confirmed=x.optString("confirmed_date","");
        if(!confirmed.isEmpty())confirmed+=" "+shortTime(x.optString("confirmed_time",""));
        String body="Status: "+st+"\nFather: "+x.optString("father_name","-")+"\nClass: "+x.optString("class_name","-")+"\nCity: "+x.optString("city","-")+"\nMobile: "+x.optString("mobile","-")+"\nWhatsApp: "+x.optString("whatsapp","-")+"\nRequested: "+requested+"\nFee: Rs. "+x.optString("fee","0")+(confirmed.isEmpty()?"":"\nConfirmed: "+confirmed);
        LinearLayout box=cardBox(x.optString("name","Student"),body);
        String proof=x.optString("payment_proof_url","");
        if(!proof.isEmpty()){Button view=button("Open Payment Screenshot");box.addView(view);view.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(proof)));}catch(Exception e){status.setText("Unable to open payment screenshot.");}});}
        if("PENDING_VERIFICATION".equals(st)){
            EditText date=input("Confirmed date (YYYY-MM-DD)",false); date.setText(x.optString("requested_date",""));
            EditText time=input("Confirmed time (HH:MM)",false); time.setText(shortTime(x.optString("requested_time","")));
            box.addView(date); box.addView(time);
            LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
            Button confirm=button("Confirm");Button reject=button("Reject");actions.addView(confirm,new LinearLayout.LayoutParams(0,-2,1));actions.addView(reject,new LinearLayout.LayoutParams(0,-2,1));box.addView(actions);
            int id=x.optInt("id");
            confirm.setOnClickListener(v->videoSessionAction(id,"confirm",date.getText().toString().trim(),time.getText().toString().trim(),""));
            reject.setOnClickListener(v->showVideoReject(id));
        }
        content.addView(box);
    }
    private String shortTime(String t){if(t==null)return "";return t.length()>=5?t.substring(0,5):t;}
    private void showVideoReject(int id){
        LinearLayout box=cardBox("Reject Video Session","Add an optional rejection reason.");
        EditText reason=input("Rejection reason",false);reason.setMinLines(2);box.addView(reason);
        Button send=button("Reject Request");box.addView(send);content.addView(box);
        send.setOnClickListener(v->videoSessionAction(id,"reject","","",reason.getText().toString().trim()));
    }
    private void videoSessionAction(int id,String action,String date,String time,String reason){
        try{
            JSONObject b=new JSONObject().put("action",action);
            if("confirm".equals(action)){b.put("confirmed_date",date).put("confirmed_time",time);}
            else b.put("rejection_reason",reason);
            post("/admin/app/video-sessions/"+id+"/action",b,o->{JSONObject r=asObject(o);status.setText(r==null?"Video session updated.":"Video session updated: "+r.optString("status",action.toUpperCase(Locale.US)));open("Video Sessions");});
        }catch(Exception e){status.setText("Could not update video session: "+safeError(e));}
    }

    private void loadAnalytics(){
        Button refresh=button("Refresh Analytics"); content.addView(refresh); refresh.setOnClickListener(v->open("Analytics"));
        fetch("/admin/app/analytics",obj->{
            JSONObject j=asObject(obj); if(j==null){content.addView(card("Analytics unavailable","The server returned no analytics data."));return;}
            addMetric("Students Created",j.optInt("students_created",0));
            addMetric("Active Members",j.optInt("active_members",0));
            addMetric("Orders",j.optInt("orders",0));
            addMetric("Revenue", "Rs. "+String.format(Locale.US,"%,.2f",j.optDouble("revenue",0)));
            addMetric("Membership Submissions",j.optInt("membership_submissions",0));
            addMetric("Approved Memberships",j.optInt("approved_memberships",0));
            addMetric("Membership Conversion",j.optDouble("membership_conversion",0)+"%");
            addMetric("Quiz Attempts",j.optInt("quiz_attempts",0));
            addMetric("Average Quiz Score",j.optDouble("average_quiz_score",0)+"%");
            addMetric("Courses Completed",j.optInt("courses_completed",0));
            addMetric("Active Learners",j.optInt("active_learners",0));
            addMetric("WhatsApp Sent",j.optInt("whatsapp_sent",0));
        });
    }
    private void loadNotifications(){fetch("/admin/app/notifications",obj->{JSONArray a=asArray(obj);if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null)content.addView(card(x.optString("title","Notification"),x.optString("message","")+"\nType: "+x.optString("type","-")+"\n"+x.optString("created_at","")));}});}
    private void loadWhatsApp(){
        Button refresh=button("Refresh Queue"); content.addView(refresh);
        Button create=button("Create WhatsApp Message"); content.addView(create);
        refresh.setOnClickListener(v->open("WhatsApp")); create.setOnClickListener(v->showMessageComposer());
        fetchQueueAll();
    }

    private void fetchQueueSilently(){
        if(startupQueueFetched)return; startupQueueFetched=true;
        new Thread(()->{try{
            String token=readToken(); String base=cleanBase(prefs.getString("base",""));
            if(token.isEmpty()||base.isEmpty())return;
            // Warm the queue on every app launch. The visible WhatsApp page fetches all pages again.
            requestAllQueue(base,token);
        }catch(Exception ignored){}
        }).start();
    }

    private void fetchQueueAll(){
        status.setText("Loading WhatsApp queue...");
        new Thread(()->{
            try{
                String token=readToken(); String base=cleanBase(prefs.getString("base",""));
                if(token.isEmpty()||base.isEmpty())throw new IOException("Administrator session expired. Please sign in again.");
                JSONArray all=requestAllQueue(base,token);
                runOnUiThread(()->{
                    status.setText(all.length()+" queued message"+(all.length()==1?"":"s"));
                    if(all.length()==0) content.addView(card("Queue is empty","No pending WhatsApp messages right now."));
                    for(int i=0;i<all.length();i++) addQueue(all.optJSONObject(i));
                });
            }catch(Exception e){runOnUiThread(()->status.setText("Queue failed: "+safeError(e)));}
        }).start();
    }

    private JSONArray requestAllQueue(String base,String token)throws Exception{
        JSONArray all=new JSONArray(); int after=0;
        for(int page=0;page<20;page++){
            String raw=request(base+API+"/admin/app/queue?limit=100&after="+after,"GET",null,token);
            JSONObject wrapper=new JSONObject(raw); JSONArray items=wrapper.optJSONArray("items");
            if(items==null||items.length()==0)break;
            for(int i=0;i<items.length();i++){
                JSONObject item=items.optJSONObject(i); if(item==null)continue;
                all.put(item); after=Math.max(after,item.optInt("id",after));
            }
            if(items.length()<100)break;
        }
        return all;
    }

    private void addQueue(JSONObject x){
        if(x==null)return;
        String recipient=x.optString("recipient","");
        String type=x.optString("type","GENERAL");
        String state=x.optString("status","PENDING");
        String body="Type: "+type+"\nStatus: "+state+"\nCreated: "+x.optString("created_at","")+"\n\n"+x.optString("message","");
        LinearLayout c=cardBox(recipient,body);
        Button open=button("Open in WhatsApp Business");c.addView(open);open.setOnClickListener(v->openWhatsApp(x));
        Button sent=button("Mark Sent");c.addView(sent);sent.setOnClickListener(v->transitionWithoutReload(x.optInt("id"),"sent",sent));
        Button failed=button("Mark Failed");c.addView(failed);failed.setOnClickListener(v->transitionWithoutReload(x.optInt("id"),"failed",failed));
        content.addView(c);
    }
    private void showMessageComposer(){LinearLayout box=cardBox("Create WhatsApp Message","The message will be queued. Sending remains a manual action in WhatsApp Business.");EditText r=input("Recipient WhatsApp number",false),m=input("Message",false);m.setMinLines(4);box.addView(r);box.addView(m);Button q=button("Add to Queue");box.addView(q);content.addView(box);q.setOnClickListener(v->{try{JSONObject b=new JSONObject().put("recipient",r.getText().toString()).put("message",m.getText().toString()).put("type","GENERAL");post("/admin/app/queue",b,o->open("WhatsApp"));}catch(Exception e){status.setText("Could not create message.");}});}
    private void openWhatsApp(JSONObject x){
        try{
            String p=x.optString("recipient","").replaceAll("[^0-9]","");
            if(p.isEmpty())throw new IllegalArgumentException("Recipient WhatsApp number is missing.");
            String m=enc(x.optString("message",""));
            Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+p+"?text="+m));
            i.setPackage("com.whatsapp.w4b");
            startActivity(i);
            // Do not reload the queue here. OPENED messages remain in the server queue by design,
            // and an immediate reload used to make the first item appear stuck forever.
            post("/admin/app/queue/"+x.optInt("id")+"/opened",new JSONObject(),o->{});
        }catch(Exception e){status.setText("Could not open WhatsApp Business: "+safeError(e));}
    }
    private void transitionWithoutReload(int id,String state,Button source){
        source.setEnabled(false);
        post("/admin/app/queue/"+id+"/"+state,new JSONObject(),o->{source.setText(state.equals("sent")?"Sent":"Failed");status.setText("Message "+state+".");});
    }
    private void transition(int id,String state){post("/admin/app/queue/"+id+"/"+state,new JSONObject(),o->open("WhatsApp"));}
    private void logout(){post("/admin/app/logout",new JSONObject(),o->{prefs.edit().clear().apply();showLogin();});}

    private void fetch(String path,Callback cb){status.setText("Loading...");new Thread(()->{try{String token=readToken();if(token.isEmpty())throw new IOException("Administrator session expired. Please sign in again.");Object j=parse(request(cleanBase(prefs.getString("base",""))+API+path,"GET",null,token));runOnUiThread(()->{status.setText("");cb.ok(j);});}catch(Exception e){runOnUiThread(()->status.setText("Request failed: "+safeError(e)));}}).start();}
    private void post(String path,JSONObject body,Callback cb){new Thread(()->{try{String token=readToken();if(token.isEmpty())throw new IOException("Administrator session expired. Please sign in again.");Object j=parse(request(cleanBase(prefs.getString("base",""))+API+path,"POST",body.toString(),token));runOnUiThread(()->cb.ok(j));}catch(Exception e){runOnUiThread(()->status.setText("Request failed: "+safeError(e)));}}).start();}
    interface Callback{void ok(Object j);} private Object parse(String s)throws Exception{return new JSONTokener(s).nextValue();} private JSONObject asObject(Object o){return o instanceof JSONObject?(JSONObject)o:null;} private JSONArray asArray(Object o){return o instanceof JSONArray?(JSONArray)o:null;} private JSONArray asArrayField(Object o,String k){JSONObject j=asObject(o);return j==null?null:j.optJSONArray(k);}
    private String request(String url,String method,String body,String token)throws Exception{
        String current=url;
        for(int redirects=0; redirects<4; redirects++){
            URL endpoint=new URL(current);
            HttpURLConnection c=(HttpURLConnection)endpoint.openConnection();
            c.setRequestMethod(method); c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            c.setRequestProperty("Accept","application/json");
            c.setRequestProperty("User-Agent","EWA-Admin-Android/"+APP_VERSION);
            c.setRequestProperty("Connection","close");
            if(token!=null&&!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
            if(body!=null){
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream o=c.getOutputStream()){o.write(bytes);}
            }
            int code=c.getResponseCode();
            if(code>=300&&code<400){
                String loc=c.getHeaderField("Location");
                if(loc==null||loc.trim().isEmpty()) throw new IOException("Server returned HTTP "+code+" without a redirect location.");
                URL next=new URL(endpoint,loc);
                if(!"https".equalsIgnoreCase(next.getProtocol())) throw new IOException("Server redirected to a non-HTTPS URL. Use the final HTTPS WordPress URL.");
                current=next.toString();
                continue;
            }
            InputStream in=code>=400?c.getErrorStream():c.getInputStream();
            StringBuilder out=new StringBuilder();
            if(in!=null)try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)out.append(line);}
            String response=out.toString();
            if(code>=400){
                String detail=response;
                try{JSONObject e=new JSONObject(response);detail=extractApiError(e,response);}catch(Exception ignored){}
                throw new IOException("HTTP "+code+": "+detail);
            }
            if(response.trim().isEmpty())throw new IOException("WordPress returned an empty response.");
            return response;
        }
        throw new IOException("Too many HTTPS redirects. Enter the final WordPress HTTPS URL.");
    }
    private String normalizeServerUrl(String raw){String s=cleanBase(raw);String lower=s.toLowerCase(Locale.US);String[] suffixes={"/wp-json/ewa/v1","/wp-json/ewa","/wp-json","/wp-admin","/wp-login.php"};for(String suffix:suffixes)if(lower.endsWith(suffix))return s.substring(0,s.length()-suffix.length());return s;}
    private String extractApiError(JSONObject j,String fallback){if(j==null)return fallback;String msg=j.optString("message","");if(!msg.isEmpty())return msg;JSONObject data=j.optJSONObject("data");if(data!=null&&!data.optString("message","").isEmpty())return data.optString("message");return fallback;}
    private SecretKey getOrCreateKey()throws Exception{java.security.KeyStore ks=java.security.KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(KEY_ALIAS))return ((SecretKeyEntry)ks.getEntry(KEY_ALIAS,null)).getSecretKey();KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return kg.generateKey();}
    private void writeToken(String token){try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,getOrCreateKey());prefs.edit().putString("token_iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).putString("token_ct",Base64.encodeToString(c.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).apply();}catch(Exception e){throw new IllegalStateException("Unable to secure administrator token.",e);}}
    private String readToken(){try{String ivs=prefs.getString("token_iv",""),cts=prefs.getString("token_ct","");if(ivs.isEmpty()||cts.isEmpty())return "";Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,getOrCreateKey(),new GCMParameterSpec(128,Base64.decode(ivs,Base64.NO_WRAP)));return new String(c.doFinal(Base64.decode(cts,Base64.NO_WRAP)),StandardCharsets.UTF_8);}catch(Exception e){return "";}}
    private String cleanBase(String s){s=s.trim();while(s.endsWith("/"))s=s.substring(0,s.length()-1);return s;} private String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}} private String safeError(Exception e){return e.getMessage()==null?"Unknown error":e.getMessage();}
    private LinearLayout base(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(18,18,18,18);GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(11,18,45),Color.rgb(62,31,92),Color.rgb(8,74,91)});l.setBackground(g);return l;} private GradientDrawable bg(int color,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(28);g.setStroke(1,stroke);return g;} private LinearLayout cardBox(String h,String body){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(20,18,20,18);l.setBackground(bg(Color.argb(225,28,34,64),Color.argb(150,130,120,255)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,14);l.setLayoutParams(lp);TextView a=title(h);l.addView(a);if(!body.isEmpty())l.addView(text(body));return l;} private TextView title(String s){TextView t=text(s);t.setTextSize(21);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setTextColor(Color.WHITE);return t;} private TextView text(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(15);t.setTextColor(Color.rgb(232,236,248));t.setPadding(0,7,0,7);return t;} private EditText input(String hint,boolean password){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Color.rgb(130,139,158));e.setTextColor(Color.WHITE);e.setSingleLine(!hint.toLowerCase().contains("instructions"));if(password)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setPadding(14,10,14,10);e.setBackground(bg(Color.argb(105,255,255,255),Color.argb(150,255,255,255)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,10);e.setLayoutParams(lp);return e;} private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.BLACK);b.setTextSize(13);b.setAllCaps(false);b.setBackground(bg(Color.rgb(104,226,255),Color.rgb(190,105,255)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(0,4,8,8);b.setLayoutParams(lp);return b;} private LinearLayout card(String h,String body){return cardBox(h,body);} private void addMetric(String k,Object v){content.addView(card(k,String.valueOf(v)));}
}
