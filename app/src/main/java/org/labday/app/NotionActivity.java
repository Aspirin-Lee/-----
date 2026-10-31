package org.labday.app;
import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.net.URLEncoder;
import java.util.concurrent.*;

public class NotionActivity extends Activity {
    private Store store;private LinearLayout items;private EditText endpoint,token;private TextView status;
    private Button connect,more;private JSONArray cached=new JSONArray();private String cursor=null;
    private boolean loading=false;private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new Store(this);getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout box=Ui.screen(this,"Notion 实验方案");
        Ui.text(box,"选择方案，安排为一次独立实验。结果以追加记录同步，不替换原方案。",15);
        endpoint=Ui.field(box,"HTTPS 服务地址","",false);endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        token=Ui.field(box,"App 访问密钥（非 Notion 原始令牌）","",false);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        try{JSONObject saved=Credentials.read(this);if(saved!=null){endpoint.setText(saved.optString("url"));token.setHint("已加密保存；留空使用现有密钥");}}
        catch(Exception e){Ui.toast(this,"无法解密旧连接，请重新填写访问密钥");}
        connect=Ui.button(box,"保存连接并读取任务",()->{
            try{
                String url=Gateway.validate(endpoint.getText().toString());String secret=token.getText().toString().trim();
                JSONObject old=Credentials.read(this);
                if(secret.isEmpty()){
                    if(old==null||!url.equals(old.optString("url")))throw new Exception("服务地址改变时，请同时填写访问密钥");
                }else{
                    if(secret.length()<32||secret.contains("\r")||secret.contains("\n"))throw new Exception("访问密钥至少32个字符");
                    Credentials.save(this,url,secret);token.setText("");token.setHint("已加密保存");
                }
                readList(false);
            }catch(Exception e){Ui.toast(this,e.getMessage());}
        });
        Ui.button(box,"清除本机连接密钥",()->new AlertDialog.Builder(this).setMessage("清除后需重新输入连接信息，实验记录保留。")
            .setPositiveButton("清除",(d,w)->{Credentials.clear(this);token.setText("");token.setHint("请输入 App 访问密钥");Ui.toast(this,"已清除");}).setNegativeButton("取消",null).show());
        status=Ui.text(box,"尚未刷新 · 下方可能是本机缓存",14);
        items=new LinearLayout(this);items.setOrientation(LinearLayout.VERTICAL);box.addView(items);
        more=Ui.button(box,"加载下一页",()->readList(true));more.setVisibility(View.GONE);
        JSONObject cache=store.get("cache","notion-list");
        if(cache!=null){cached=cache.optJSONArray("items");if(cached==null)cached=new JSONArray();cursor=cache.isNull("nextCursor")?null:cache.optString("nextCursor",null);status.setText("本机缓存 · "+cache.optString("cachedAt"));renderList();}
    }
    private void renderList(){
        items.removeAllViews();
        for(int i=0;i<cached.length();i++){
            JSONObject row=cached.optJSONObject(i);if(row==null)continue;
            LinearLayout card=Ui.card(items);Ui.text(card,row.optString("title"),18);
            Ui.text(card,(row.isNull("date")?"待安排":row.optString("date"))+" · "+(row.isNull("status")?"未设置状态":row.optString("status")),14);
            Ui.button(card,"查看设计 / 安排实验",()->detail(row.optString("id")));
        }
        if(cached.length()==0)Ui.text(items,"暂无缓存任务，请连接并读取。",15);
        more.setVisibility(cursor==null?View.GONE:View.VISIBLE);
    }
    private void readList(boolean next){
        if(loading)return;loading=true;connect.setEnabled(false);more.setEnabled(false);
        status.setText("正在读取；免费服务唤醒可能需要约一分钟…");String requested=next?cursor:null;
        worker.execute(()->{
            JSONObject data=null;String failure=null;
            try{data=Gateway.request(this,"/experiments"+(requested==null?"":"?cursor="+URLEncoder.encode(requested,"UTF-8")),null);}
            catch(Exception e){failure=e.getMessage();}
            JSONObject result=data;String error=failure;
            runOnUiThread(()->{
                if(isDestroyed())return;loading=false;connect.setEnabled(true);more.setEnabled(true);
                if(error!=null){status.setText(error+" · 已保留本机缓存");return;}
                JSONArray rows=result.optJSONArray("items");if(rows==null){status.setText("服务响应缺少任务列表");return;}
                if(!next)cached=new JSONArray();for(int i=0;i<rows.length();i++)cached.put(rows.optJSONObject(i));
                cursor=result.isNull("nextCursor")?null:result.optString("nextCursor",null);
                JSONObject cache=new JSONObject();Store.put(cache,"items",cached);Store.put(cache,"nextCursor",cursor==null?JSONObject.NULL:cursor);Store.put(cache,"cachedAt",Instant.now().toString());
                store.set("cache","notion-list",cache);status.setText("已读取 "+cached.length()+" 条"+(cursor==null?" · 已加载全部":" · 还有下一页"));renderList();
            });
        });
    }
    private void detail(String id){
        status.setText("正在读取实验详情…");
        worker.execute(()->{
            JSONObject result=null;String error=null;
            try{result=Gateway.request(this,"/experiments/"+id,null);}
            catch(Exception e){error=e.getMessage();}
            JSONObject fetched=result;String failure=error;
            runOnUiThread(()->{
                if(isDestroyed())return;
                JSONObject data=fetched;
                if(data!=null)store.set("cache","detail-"+id,data);
                else{data=store.get("cache","detail-"+id);if(data==null){Ui.toast(this,failure);status.setText(failure);return;}Ui.toast(this,"读取失败，展示已缓存的原方案");}
                showDetail(data);
            });
        });
    }
    private void showDetail(JSONObject detail){
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(24,12,24,12);
        TextView text=Ui.text(body,detail.optString("text","此页面暂无文字内容"),16);text.setTextIsSelectable(true);
        if(detail.optBoolean("truncated")||detail.optInt("unsupported")>0)Ui.text(body,"部分内容未展开或包含暂不支持的块，请打开 Notion 查看完整原文。",14);
        JSONArray images=detail.optJSONArray("images");
        if(images!=null)for(int i=0;i<images.length();i++){
            JSONObject image=images.optJSONObject(i);if(image!=null)Ui.button(body,"查看原方案图片："+image.optString("caption","图片"),()->open(image.optString("url")));
        }
        Ui.text(body,"图片链接可能过期，重新读取详情可更新。导入后原方案文字可离线查看。",13);
        if(!detail.optString("url").isEmpty())Ui.button(body,"在 Notion 中打开",()->open(detail.optString("url")));
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        new AlertDialog.Builder(this).setTitle(detail.optString("title")).setView(scroll)
            .setPositiveButton(getIntent().hasExtra("linkTo")?"关联当前记录":"安排本次实验",(d,w)->{
                String link=getIntent().getStringExtra("linkTo");JSONObject record=link==null?Store.fresh():store.get("drafts",link);
                if(record==null&&link!=null)record=store.get("records",link);
                if(record==null)record=Store.fresh();
                Store.put(record,"notionId",detail.optString("id"));Store.put(record,"notionTitle",detail.optString("title"));Store.put(record,"notionText",detail.optString("text"));
                if(record.optString("title").isEmpty())Store.put(record,"title",detail.optString("title"));
                if(link==null){
                    String date=detail.isNull("date")?"":detail.optString("date");if(date.length()>=10)try{LocalDate.parse(date.substring(0,10));Store.put(record,"date",date.substring(0,10));}catch(Exception ignored){}
                    String design=detail.optString("text");Store.put(record,"design",design.length()>12000?design.substring(0,12000):design);
                }
                store.set("drafts",record.optString("id"),record);
                if(link==null)startActivity(new Intent(this,EditorActivity.class).putExtra("id",record.optString("id")));
                else finish();
            }).setNegativeButton("关闭",null).show();
    }
    private void open(String value){
        try{Uri uri=Uri.parse(value);if(!"https".equals(uri.getScheme()))throw new Exception();startActivity(new Intent(Intent.ACTION_VIEW,uri));}
        catch(Exception e){Ui.toast(this,"无法打开链接");}
    }
    @Override protected void onDestroy(){worker.shutdown();store.close();super.onDestroy();}
}
