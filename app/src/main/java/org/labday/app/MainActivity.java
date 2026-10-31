package org.labday.app;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.*;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private Store store;private LinearLayout rows;private EditText search;
    private String filter=LocalDate.now().toString();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final int EXPORT=20,IMPORT=21;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new Store(this);store.migrate(this);
        LinearLayout box=Ui.screen(this,"实验日程");
        Ui.text(box,"安排 · 执行 · 结果 · 追溯",15);
        Ui.button(box,"＋ 新建实验记录",()->openDraft(Store.fresh()));
        LinearLayout tabs=new LinearLayout(this);box.addView(tabs);
        for(int i=0;i<3;i++){final int n=i;Ui.button(tabs,new String[]{"今天","明天","后天"}[i],()->{filter=LocalDate.now().plusDays(n).toString();load();});}
        Ui.button(box,"日历选择日期",()->{
            LocalDate now=LocalDate.now();new DatePickerDialog(this,(v,y,m,d)->{filter=LocalDate.of(y,m+1,d).toString();load();},now.getYear(),now.getMonthValue()-1,now.getDayOfMonth()).show();
        });
        Ui.button(box,"全部记录 / 历史",()->{filter="";load();});
        search=Ui.field(box,"搜索名称、样本、项目、设计和结果","",false);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){load();}public void afterTextChanged(Editable e){}});
        Ui.button(box,"Notion 任务与连接",()->startActivity(new Intent(this,NotionActivity.class)));
        Ui.button(box,"同步队列与重试",this::queue);
        Ui.button(box,"实验模板",this::templates);
        Ui.button(box,"提醒与步骤计时",this::reminders);
        Ui.button(box,"导出完整备份 ZIP（含原图与 Markdown）",()->{
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_TITLE,"LabDay-"+LocalDate.now()+".zip");startActivityForResult(intent,EXPORT);
        });
        Ui.button(box,"从 ZIP 恢复（保留现有记录）",()->new AlertDialog.Builder(this).setMessage("恢复会新增副本，保留现有记录。不会恢复待发送任务或密钥。请先导出当前数据。")
            .setPositiveButton("选择备份",(d,w)->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE),IMPORT)).setNegativeButton("取消",null).show());
        rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);box.addView(rows);load();
    }
    private void openDraft(JSONObject record){store.set("drafts",record.optString("id"),record);startActivity(new Intent(this,EditorActivity.class).putExtra("id",record.optString("id")));}
    @Override protected void onResume(){super.onResume();if(rows!=null)load();}
    private void load(){
        if(rows==null)return;rows.removeAllViews();
        String query=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);
        Ui.text(rows,filter.isEmpty()?"全部记录":filter+" 的安排",20);
        JSONArray drafts=store.all("drafts");
        if(drafts.length()>0){
            Ui.text(rows,"未提交草稿（自动保存）",18);
            for(int i=0;i<drafts.length();i++){JSONObject draft=drafts.optJSONObject(i);if(draft!=null)Ui.button(rows,"恢复草稿 · "+draft.optString("title","未命名"),()->startActivity(new Intent(this,EditorActivity.class).putExtra("id",draft.optString("id"))));}
        }
        JSONArray records=store.all("records");List<JSONObject> visible=new ArrayList<>();
        for(int i=0;i<records.length();i++){
            JSONObject o=records.optJSONObject(i);if(o==null)continue;
            if(!filter.isEmpty()&&!filter.equals(o.optString("date")))continue;
            String haystack=(o.optString("title")+" "+o.optString("sample")+" "+o.optString("project")+" "+o.optString("design")+" "+o.optString("result")+" "+o.optString("anomaly")).toLowerCase(Locale.ROOT);
            if(!haystack.contains(query))continue;visible.add(o);
        }
        visible.sort((a,b)->b.optString("date").compareTo(a.optString("date")));
        for(JSONObject o:visible){
            LinearLayout card=Ui.card(rows);
            Ui.text(card,(o.optBoolean("done")?"✓ ":"○ ")+o.optString("title"),19);
            Ui.text(card,o.optString("date")+" · "+o.optString("project")+" · "+o.optString("sample"),14);
            Ui.text(card,store.syncState(o.optString("id")),13);
            String result=o.optString("result");Ui.text(card,result.length()>140?result.substring(0,140)+"…":result,15);
            Ui.button(card,"查看 / 记录结果",()->startActivity(new Intent(this,EditorActivity.class).putExtra("id",o.optString("id"))));
        }
        if(visible.isEmpty())Ui.text(rows,"暂无符合条件的记录。",16);
    }
    private void templates(){
        JSONArray templates=store.all("templates");List<String> names=new ArrayList<>();names.add("通用实验记录模板");
        for(int i=0;i<templates.length();i++)names.add(templates.optJSONObject(i).optString("title"));
        new AlertDialog.Builder(this).setTitle("从模板建立本次执行").setItems(names.toArray(new String[0]),(d,w)->{
            JSONObject record=Store.fresh();
            if(w==0){Store.put(record,"title","新实验");Store.put(record,"design","目的：\n样本与分组：\n对照：\n关键条件：\n操作步骤：\n预期观察：");}
            else{JSONObject t=templates.optJSONObject(w-1);for(String key:new String[]{"title","design","project"})Store.put(record,key,t.optString(key));}
            openDraft(record);
        }).setNegativeButton("关闭",null).show();
    }
    private void queue(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(20,10,20,10);
        Ui.text(box,"任务保存的是当次快照。修改记录后，需重新提交新的同步任务。",14);
        JSONArray jobs=store.all("outbox");
        for(int i=0;i<jobs.length();i++){
            JSONObject job=jobs.optJSONObject(i);if(job==null)continue;
            String title=job.optString("recordId");try{title=new JSONObject(job.optString("snapshot")).optString("title");}catch(JSONException ignored){}
            Ui.text(box,title+"\n"+job.optString("message"),15);
            if(!job.optString("state").equals("synced"))Ui.button(box,"重试此任务",()->new AlertDialog.Builder(this)
                .setMessage("若显示写入结果未知，请先查看 Notion，确认是否已经出现本次记录。同一任务使用原操作编号重试，不要另建任务。")
                .setPositiveButton("用原编号重试",(d,w)->{Store.put(job,"state","pending");Store.put(job,"message","等待重试");store.set("outbox",job.optString("id"),job);syncNow();}).setNegativeButton("取消",null).show());
        }
        Ui.button(box,"立即同步待发送任务",this::syncNow);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("同步队列").setView(scroll).setPositiveButton("关闭",(d,w)->load()).show();
    }
    private void syncNow(){
        Ui.toast(this,"正在同步，可继续记录。");worker.execute(()->{String result=SyncEngine.run(getApplicationContext());runOnUiThread(()->{if(!isFinishing()){Ui.toast(this,result);load();}});});
    }
    private void reminders(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        EditText title=Ui.field(box,"提醒名称","实验步骤计时",false),minutes=Ui.field(box,"几分钟后提醒","30",false);minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        Ui.text(box,"系统可能延迟通知；不适合要求秒级精度的关键步骤。重启后会恢复尚未到期提醒。",14);
        Ui.button(box,"开始计时",()->{
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},90);Ui.toast(this,"请允许通知后再次开始计时");return;
            }
            try{
                long n=Long.parseLong(minutes.getText().toString());if(n<1||n>10080)throw new Exception();
                JSONObject r=new JSONObject();String id=UUID.randomUUID().toString();Store.put(r,"id",id);Store.put(r,"title",title.getText().toString());Store.put(r,"at",System.currentTimeMillis()+n*60000);
                store.set("reminders",id,r);ReminderReceiver.schedule(this,r);Ui.toast(this,"已设置提醒");
            }catch(Exception e){minutes.setError("请输入1至10080分钟");}
        });
        JSONArray rs=store.all("reminders");
        for(int i=0;i<rs.length();i++){JSONObject r=rs.optJSONObject(i);if(r!=null)Ui.button(box,"取消："+r.optString("title")+" · "+Instant.ofEpochMilli(r.optLong("at")).atZone(ZoneId.systemDefault()).toLocalDateTime(),()->{ReminderReceiver.cancel(this,r.optString("id"));store.remove("reminders",r.optString("id"));Ui.toast(this,"已取消提醒");});}
        ScrollView scroll=new ScrollView(this);scroll.addView(box);new AlertDialog.Builder(this).setTitle("提醒与步骤计时").setView(scroll).setPositiveButton("关闭",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==EXPORT||request==IMPORT){
            android.net.Uri uri=data.getData();Ui.toast(this,request==EXPORT?"正在导出，请勿退出应用":"正在恢复，请勿退出应用");
            worker.execute(()->{String message;try{message=request==EXPORT?Archive.export(this,uri):Archive.restore(this,uri);}catch(Exception e){message="操作失败："+e.getMessage();}
                final String text=message;runOnUiThread(()->{if(!isFinishing()){Ui.toast(this,text);load();}});});
        }
    }
    @Override protected void onDestroy(){worker.shutdown();if(store!=null)store.close();super.onDestroy();}
}
