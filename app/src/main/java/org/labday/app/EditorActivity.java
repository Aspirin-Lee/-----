package org.labday.app;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.speech.RecognizerIntent;
import android.text.*;
import android.widget.*;
import android.graphics.*;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class EditorActivity extends Activity {
    private Store store;private JSONObject record;private LinearLayout box,photosBox;
    private final Map<String,EditText> fields=new LinkedHashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String id,voiceKey="result",captureName;private CheckBox done;private TextView saved;
    private boolean rendering=false;private final Runnable autosave=()->persistDraft();
    private static final int VOICE=11,PHOTO=12,CAMERA=13;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new Store(this);id=state==null?getIntent().getStringExtra("id"):state.getString("id");
        if(id==null){record=Store.fresh();id=record.optString("id");}
        if(state!=null)captureName=state.getString("captureName");
    }
    @Override protected void onResume(){
        super.onResume();JSONObject latest=store.get("drafts",id);if(latest==null)latest=store.get("records",id);
        if(latest!=null)record=latest;if(record==null)record=Store.fresh();render();
    }
    private void render(){
        rendering=true;fields.clear();box=Ui.screen(this,"实验执行记录");
        saved=Ui.text(box,"自动保存草稿 · 点击保存确认本次修改",14);
        add("title","实验名称",false);add("date","计划日期 YYYY-MM-DD",false);
        Ui.button(box,"选择计划日期",()->{
            LocalDate d;try{d=LocalDate.parse(fields.get("date").getText().toString());}catch(Exception e){d=LocalDate.now();}
            new DatePickerDialog(this,(v,y,m,day)->fields.get("date").setText(LocalDate.of(y,m+1,day).toString()),d.getYear(),d.getMonthValue()-1,d.getDayOfMonth()).show();
        });
        add("project","项目 / 课题",false);add("sample","样本编号 / 分组 / 试剂批次",true);
        add("design","本次实验设计与操作",true);add("result","结果与观察",true);add("anomaly","异常、失败原因、下次改进",true);
        add("imageNotes","整体图片说明",true);
        done=new CheckBox(this);done.setText("本次实验已完成");done.setChecked(record.optBoolean("done"));box.addView(done);
        done.setOnCheckedChangeListener((b,value)->changed());
        Ui.button(box,"语音输入",()->new AlertDialog.Builder(this).setTitle("选择填写位置")
            .setItems(new String[]{"设计与操作","结果与观察","异常与改进","样本与分组"},(d,w)->{
                voiceKey=new String[]{"design","result","anomaly","sample"}[w];
                Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");
                try{startActivityForResult(intent,VOICE);}catch(ActivityNotFoundException e){Ui.toast(this,"手机未提供语音服务，可使用输入法语音");}
            }).show());
        Ui.text(box,"语音转写后请核对样本编号、单位与数值；目前不保存原始录音。",13);
        photosBox=new LinearLayout(this);photosBox.setOrientation(LinearLayout.VERTICAL);box.addView(photosBox);renderPhotos();
        Ui.button(box,"选择图片（每次记录最多3张）",()->{
            if(media().length()>=3){Ui.toast(this,"请建立另一次执行记录添加更多图片");return;}
            persistDraft();startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),PHOTO);
        });
        Ui.button(box,"直接拍照",()->{
            if(media().length()>=3){Ui.toast(this,"每次记录最多3张图片");return;}
            try{
                persistDraft();captureName=UUID.randomUUID()+".jpg";File file=new File(getFilesDir(),captureName);
                Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);
                Intent intent=new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT,uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent,CAMERA);
            }catch(Exception e){Ui.toast(this,"无法打开相机："+e.getMessage());}
        });
        String notion=record.optString("notionId");
        Ui.text(box,notion.isEmpty()?"尚未关联 Notion 实验方案":"已关联 Notion · "+record.optString("notionTitle"),15);
        Ui.button(box,notion.isEmpty()?"关联 Notion 实验方案":"查看 / 重新关联 Notion 方案",()->{persistDraft();startActivity(new Intent(this,NotionActivity.class).putExtra("linkTo",id));});
        if(!record.optString("notionText").isEmpty())Ui.button(box,"查看已缓存的 Notion 原方案",()->{
            TextView t=new TextView(this);t.setText(record.optString("notionText"));t.setPadding(24,16,24,16);t.setTextIsSelectable(true);
            ScrollView scroll=new ScrollView(this);scroll.addView(t);new AlertDialog.Builder(this).setTitle("原方案缓存（只读）").setView(scroll).setPositiveButton("关闭",null).show();
        });
        Ui.button(box,"保存本次记录",()->{if(commit()){Ui.toast(this,"已保存在手机");finish();}});
        Ui.button(box,"保存并排队同步到 Notion",()->{
            if(record.optString("notionId").isEmpty()){Ui.toast(this,"请先关联 Notion 实验方案");return;}
            if(commit()){store.queue(record);SyncEngine.schedule(this);Ui.toast(this,"已保存在手机并加入同步队列；联网后发送");finish();}
        });
        Ui.button(box,"复制为新一次执行",()->{
            persistDraft();JSONObject next=Store.copy(record);Store.put(next,"id",UUID.randomUUID().toString());Store.put(next,"date",LocalDate.now().toString());
            for(String key:new String[]{"result","anomaly","imageNotes","sample"})Store.put(next,key,"");
            Store.put(next,"done",false);Store.put(next,"photos",new JSONArray());store.set("drafts",next.optString("id"),next);
            startActivity(new Intent(this,EditorActivity.class).putExtra("id",next.optString("id")));finish();
        });
        Ui.button(box,"将设计保存为模板",()->{
            collect();JSONObject t=new JSONObject();String tid=UUID.randomUUID().toString();Store.put(t,"id",tid);
            for(String key:new String[]{"title","design","project"})Store.put(t,key,record.optString(key));store.set("templates",tid,t);Ui.toast(this,"模板已保存");
        });
        Ui.button(box,"放弃未保存修改",()->new AlertDialog.Builder(this).setMessage("删除此草稿，保留之前已保存的记录。")
            .setPositiveButton("放弃草稿",(d,w)->{handler.removeCallbacks(autosave);rendering=true;store.remove("drafts",id);finish();})
            .setNegativeButton("继续编辑",null).show());
        rendering=false;
    }
    private JSONArray media(){JSONArray photos=record.optJSONArray("photos");if(photos==null){photos=new JSONArray();Store.put(record,"photos",photos);}return photos;}
    private void add(String key,String label,boolean multi){
        EditText e=Ui.field(box,label,record.optString(key),multi);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(key.equals("title")?300:12000)});fields.put(key,e);
        e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){changed();}public void afterTextChanged(Editable e){}});
    }
    private void changed(){if(rendering)return;handler.removeCallbacks(autosave);handler.postDelayed(autosave,400);}
    private void collect(){for(Map.Entry<String,EditText> entry:fields.entrySet())Store.put(record,entry.getKey(),entry.getValue().getText().toString());if(done!=null)Store.put(record,"done",done.isChecked());}
    private void persistDraft(){
        if(rendering||record==null||fields.isEmpty())return;
        collect();Store.put(record,"updatedAt",Instant.now().toString());store.set("drafts",id,record);
        if(saved!=null)saved.setText("草稿已保存到手机 · "+java.time.LocalTime.now().withNano(0));
    }
    private boolean commit(){
        collect();if(record.optString("title").trim().isEmpty()){fields.get("title").setError("请输入实验名称");return false;}
        try{LocalDate.parse(record.optString("date"));}catch(Exception e){fields.get("date").setError("请输入有效日期 YYYY-MM-DD");return false;}
        handler.removeCallbacks(autosave);store.saveRecord(record);rendering=true;return true;
    }
    private void renderPhotos(){
        photosBox.removeAllViews();JSONArray photos=media();Ui.text(photosBox,"图片 "+photos.length()+" 张 · 原图保存在本机",16);
        for(int i=0;i<photos.length();i++){
            final int index=i;JSONObject photo=photos.optJSONObject(i);if(photo==null)continue;
            String name=photo.optString("file");File file=new File(getFilesDir(),name);
            ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setMaxHeight(Ui.dp(this,240));
            try{image.setImageBitmap(Media.bitmap(file,600));}catch(IOException e){Ui.text(photosBox,"原图不可读："+name,14);}photosBox.addView(image);
            EditText caption=Ui.field(photosBox,"此图对应的样本、条件和观察",photo.optString("caption"),true);
            caption.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
            caption.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){Store.put(photo,"caption",s.toString());changed();}public void afterTextChanged(Editable e){}});
            Ui.button(photosBox,"查看原图",()->{
                try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
                catch(Exception e){Ui.toast(this,"没有可用的图片查看器");}
            });
            Ui.button(photosBox,"从本次记录移除此图",()->{media().remove(index);persistDraft();renderPhotos();});
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==VOICE&&result==RESULT_OK&&data!=null){
            ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(words!=null&&!words.isEmpty()){EditText target=fields.get(voiceKey);if(target!=null){target.append("\n"+words.get(0));persistDraft();}}
        }
        if(request==PHOTO&&result==RESULT_OK&&data!=null&&data.getData()!=null)copyImage(data.getData());
        if(request==CAMERA&&captureName!=null){
            File file=new File(getFilesDir(),captureName);
            if(result==RESULT_OK&&file.exists()&&file.length()>0&&file.length()<=25*1024*1024)addPhoto(captureName);
            else{file.delete();Ui.toast(this,"拍照已取消或图片超过25MB");}
            captureName=null;
        }
    }
    private void copyImage(Uri uri){
        String name=UUID.randomUUID()+".image";Ui.toast(this,"正在保存原图");
        worker.execute(()->{
            File file=new File(getFilesDir(),name);String failure=null;
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
                if(in==null)throw new IOException("图片不可读");byte[] b=new byte[8192];int n;long count=0;
                while((n=in.read(b))!=-1){count+=n;if(count>25*1024*1024)throw new IOException("图片超过25MB");out.write(b,0,n);}
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),options);
                if(options.outWidth<=0)throw new IOException("无法识别此图片格式");
            }catch(Exception e){file.delete();failure=e.getMessage();}
            final String error=failure;runOnUiThread(()->{if(isDestroyed())return;if(error==null)addPhoto(name);else Ui.toast(this,"图片保存失败："+error);});
        });
    }
    private void addPhoto(String name){JSONObject p=new JSONObject();Store.put(p,"file",name);Store.put(p,"caption","");media().put(p);persistDraft();renderPhotos();}
    @Override protected void onPause(){handler.removeCallbacks(autosave);persistDraft();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){persistDraft();out.putString("id",id);out.putString("captureName",captureName);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){handler.removeCallbacks(autosave);worker.shutdown();store.close();super.onDestroy();}
}
