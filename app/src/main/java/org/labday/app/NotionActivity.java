package org.labday.app;

import android.app.*;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

public class NotionActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout box, items;
    private EditText endpoint, token;
    private TextView status;
    private Button connect, more;
    private String base = "", sessionToken = "", cursor = null;
    private volatile boolean closed = false;
    private volatile HttpURLConnection active;
    private boolean loading = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        box = new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,36,24,24);
        scroll.addView(box);setContentView(scroll);
        TextView title = new TextView(this);title.setText("Notion 实验任务 · 只读");title.setTextSize(22);box.addView(title);
        TextView note = new TextView(this);note.setText("填写部署后的 HTTPS 服务地址和 App 访问密钥。不要填写 Notion 原始令牌。密钥仅在本次页面会话中使用，关闭页面后需重新填写。");box.addView(note);
        endpoint = new EditText(this);endpoint.setHint("https://你的服务.onrender.com");endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);box.addView(endpoint);
        token = new EditText(this);token.setHint("APP_ACCESS_TOKEN");token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(token);
        connect = new Button(this);connect.setText("连接并读取任务");box.addView(connect);
        status = new TextView(this);status.setText("尚未连接 · 不会修改 Notion");box.addView(status);
        items = new LinearLayout(this);items.setOrientation(LinearLayout.VERTICAL);box.addView(items);
        more = new Button(this);more.setText("加载下一页");more.setVisibility(android.view.View.GONE);box.addView(more);
        connect.setOnClickListener(v -> {
            try {
                URI uri = new URI(endpoint.getText().toString().trim());
                if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||"/".equals(uri.getPath())))throw new Exception();
                String secret=token.getText().toString().trim();
                if(secret.length()<32||secret.contains("\n")||secret.contains("\r")){token.setError("请填写服务端生成的 App 访问密钥");return;}
                base = "https://"+uri.getRawAuthority();sessionToken=secret;cursor=null;items.removeAllViews();
                load();
            } catch(Exception e) { endpoint.setError("请填写 HTTPS 根地址，不含路径、参数或用户名"); }
        });
        more.setOnClickListener(v -> load());
    }
    private void load() {
        if(loading)return;loading=true;connect.setEnabled(false);more.setEnabled(false);endpoint.setEnabled(false);token.setEnabled(false);
        status.setText("正在读取；免费服务首次唤醒可能需要约一分钟…");
        final String requestedCursor=cursor;
        worker.execute(() -> {
            JSONObject data = null; String error = null;
            HttpURLConnection connection = null;
            try {
                String url=base+"/experiments"+(requestedCursor==null?"":"?cursor="+URLEncoder.encode(requestedCursor,"UTF-8"));
                connection=(HttpURLConnection)new URL(url).openConnection();active=connection;
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(20000);connection.setReadTimeout(90000);
                connection.setRequestProperty("Authorization","Bearer "+sessionToken);
                connection.setRequestProperty("Accept","application/json");
                int code=connection.getResponseCode();
                if(code!=200) {
                    error=code==401?"App 访问密钥不正确。":code==502?"Notion 暂不可读，请检查服务端令牌、数据源 ID 和共享权限。":code==503?"Notion 请求繁忙，请稍后重试。":"服务暂不可用（HTTP "+code+"），请稍后重试。";
                } else {
                    try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                        byte[] buffer=new byte[8192];int n;int total=0;
                        while((n=input.read(buffer))!=-1){total+=n;if(total>2*1024*1024)throw new IOException();output.write(buffer,0,n);}
                        data=new JSONObject(output.toString("UTF-8"));
                        if(data.optJSONArray("items")==null)throw new IOException();
                    }
                }
            } catch(Exception e) { error="连接失败，请检查网络和服务地址后重试。"; }
            finally { if(connection!=null)connection.disconnect();active=null; }
            final JSONObject result=data;final String failure=error;
            if(!closed)runOnUiThread(() -> {
                if(closed)return;
                loading=false;connect.setEnabled(true);more.setEnabled(true);endpoint.setEnabled(true);token.setEnabled(true);
                if(failure!=null){status.setText(failure+" 已加载条目仍保留。");return;}
                JSONArray rows=result.optJSONArray("items");
                for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                    TextView item=new TextView(this);item.setTextSize(17);item.setPadding(0,16,0,16);
                    String date=row.isNull("date")?"待安排":row.optString("date");
                    String state=row.isNull("status")?"未设置状态":row.optString("status");
                    item.setText(row.optString("title","未命名")+"\n"+date+" · "+state);items.addView(item);
                }
                cursor=result.isNull("nextCursor")?null:result.optString("nextCursor",null);
                more.setVisibility(cursor==null?android.view.View.GONE:android.view.View.VISIBLE);
                status.setText(items.getChildCount()==0?"连接成功，当前数据源没有可见任务。":"已读取 "+items.getChildCount()+" 条"+(cursor==null?" · 已加载全部":" · 还有下一页")+"\n只读查看，尚未导入本机日程。");
            });
        });
    }
    @Override protected void onDestroy(){
        closed=true;sessionToken="";HttpURLConnection connection=active;if(connection!=null)connection.disconnect();worker.shutdownNow();super.onDestroy();
    }
}
