package org.labday.app;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.net.Uri;
import android.speech.RecognizerIntent;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.LocalDate;
import java.util.*;
import java.io.*;

public class MainActivity extends Activity {
    private JSONArray records = new JSONArray();
    private LinearLayout list;
    private int offset = 0;
    private EditText voiceTarget;
    private JSONObject photoTarget;
    private static final int VOICE = 11, PHOTO = 12;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { records = new JSONArray(getPreferences(0).getString("records", "[]")); }
        catch (JSONException e) { toast("无法读取记录，请保留应用数据并联系维护者"); }
        render();
    }
    private void save() {
        getPreferences(0).edit().putString("records", records.toString()).apply();
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private void put(JSONObject o, String k, Object v) {
        try { o.put(k,v); } catch(JSONException e) { throw new IllegalStateException(e); }
    }
    private Button button(String s, LinearLayout parent, Runnable action) {
        Button b = new Button(this); b.setText(s); parent.addView(b);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void render() {
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(24,36,24,24);
        scroll.addView(list); setContentView(scroll);
        TextView title = new TextView(this); title.setText("实验日程 · "+LocalDate.now().plusDays(offset)); title.setTextSize(23); list.addView(title);
        TextView status = new TextView(this); status.setText("本机离线记录 · 尚未连接 Notion"); list.addView(status);
        LinearLayout tabs = new LinearLayout(this); list.addView(tabs);
        for(int i=0;i<3;i++) { final int day=i; button(new String[]{"今天","明天","后天"}[i], tabs, ()->{offset=day;render();}); }
        button("＋ 新建实验",list,()->edit(null));
        button("Notion 任务（只读连接）",list,()->startActivity(new Intent(this,NotionActivity.class)));
        int count=0;
        for(int i=0;i<records.length();i++) {
            JSONObject o=records.optJSONObject(i); if(o==null) continue;
            if(!o.optString("date").equals(LocalDate.now().plusDays(offset).toString())) continue;
            count++;
            button((o.optBoolean("done")?"✓ ":"○ ")+o.optString("title"),list,()->edit(o));
            TextView result=new TextView(this);
            result.setText("结果："+o.optString("result","")+"\n图片："+o.optJSONArray("photos").length()+" 张");
            list.addView(result);
        }
        if(count==0) { TextView empty=new TextView(this); empty.setText("当天暂无实验。点击上方按钮添加。"); list.addView(empty); }
        button("查看功能与同步说明",list,()->new AlertDialog.Builder(this).setTitle("原型 0.1")
            .setMessage("记录保存在本机。卸载会丢失，请勿用于唯一原始记录。语音由系统识别服务提供，可能需要联网；专业术语和数值请核对。图片复制到应用私有目录。Notion 同步和导出尚未实现。").setPositiveButton("知道了",null).show());
    }
    private EditText field(LinearLayout box,String hint,String value) {
        EditText e=new EditText(this); e.setHint(hint); e.setText(value); box.addView(e); return e;
    }
    private void edit(JSONObject existing) {
        JSONObject o=existing==null?new JSONObject():existing;
        if(existing==null) { put(o,"id",UUID.randomUUID().toString());put(o,"date",LocalDate.now().plusDays(offset).toString());put(o,"photos",new JSONArray()); }
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(28,12,28,12);
        EditText title=field(box,"实验名称",o.optString("title"));
        EditText date=field(box,"日期 YYYY-MM-DD",o.optString("date"));
        EditText design=field(box,"实验设计、样本编号和条件",o.optString("design"));
        EditText result=field(box,"观察、结果与异常",o.optString("result"));
        CheckBox done=new CheckBox(this);done.setText("已完成");done.setChecked(o.optBoolean("done"));box.addView(done);
        button("语音填写结果",box,()->{
            voiceTarget=result;
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");
            try{startActivityForResult(intent,VOICE);}catch(ActivityNotFoundException e){toast("此手机未安装语音识别服务，可使用输入法语音输入");}
        });
        TextView photos=new TextView(this);photos.setText("已保存图片："+o.optJSONArray("photos").length()+" 张");box.addView(photos);
        button("添加图片（先保存实验）",box,()->toast("保存后可从列表中的图片按钮添加"));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("实验记录").setView(scroll)
            .setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
            if(title.getText().toString().trim().isEmpty()){title.setError("请输入名称");return;}
            try{LocalDate.parse(date.getText().toString());}catch(Exception e){date.setError("日期格式应为 YYYY-MM-DD");return;}
            put(o,"title",title.getText().toString().trim());put(o,"date",date.getText().toString());put(o,"design",design.getText().toString());put(o,"result",result.getText().toString());put(o,"done",done.isChecked());
            if(existing==null)records.put(o);save();dialog.dismiss();render();
        }));
        dialog.show();
        if(existing!=null) {
            button("添加图片",box,()->{
                photoTarget=o;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
                try{startActivityForResult(intent,PHOTO);}catch(ActivityNotFoundException e){toast("未找到图片选择器");}
            });
            JSONArray pictures=o.optJSONArray("photos");
            for(int i=0;i<pictures.length();i++){
                String path=pictures.optString(i);ImageView image=new ImageView(this);
                image.setAdjustViewBounds(true);image.setMaxHeight(400);image.setImageURI(Uri.fromFile(new File(getFilesDir(),path)));box.addView(image);
            }
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(request==VOICE&&voiceTarget!=null){
            ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(words!=null&&!words.isEmpty())voiceTarget.append((voiceTarget.length()>0?"\n":"")+words.get(0));
        }
        if(request==PHOTO&&photoTarget!=null&&data.getData()!=null){
            File dest=new File(getFilesDir(),UUID.randomUUID()+".image");
            try(InputStream in=getContentResolver().openInputStream(data.getData());OutputStream out=new FileOutputStream(dest)){
                if(in==null)throw new IOException("图片不可读");
                byte[] b=new byte[8192];int n;long total=0;
                while((n=in.read(b))!=-1){total+=n;if(total>20*1024*1024)throw new IOException("图片超过20MB");out.write(b,0,n);}
                photoTarget.optJSONArray("photos").put(dest.getName());save();toast("图片已保存，重新打开记录查看");render();
            }catch(IOException e){dest.delete();toast("图片保存失败："+e.getMessage());}
        }
    }
}
