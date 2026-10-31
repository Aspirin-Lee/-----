package org.labday.app;
import android.content.Context;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class Gateway {
    public static class Failure extends IOException {
        public final String code;public final int status;
        Failure(int status,String code){super(message(code));this.status=status;this.code=code;}
        private static String message(String code){
            switch(code){
                case "unauthorized":return "App 访问密钥不正确";
                case "notion_authorization_required":return "Notion 权限不足，请检查读取/插入权限和页面授权";
                case "notion_source_not_shared":return "Notion 数据源 ID 或共享权限需检查";
                case "page_outside_configured_source":return "此页面不属于配置的任务表";
                case "write_uncertain":return "写入结果未知，请先查看 Notion，再手动重试";
                case "image_upload_incomplete":return "图片上传未完成";
                default:return "同步失败："+code;
            }
        }
    }
    public static String validate(String input) throws Exception {
        URI uri=new URI(input.trim());
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||
            uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||"/".equals(uri.getPath())))
            throw new IOException("请填写 HTTPS 根地址");
        return "https://"+uri.getRawAuthority();
    }
    public static JSONObject request(Context c,String path,JSONObject body) throws Exception {
        JSONObject credentials=Credentials.read(c);if(credentials==null)throw new IOException("请先配置 Notion 连接");
        HttpURLConnection connection=(HttpURLConnection)new URL(credentials.getString("url")+path).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(20000);connection.setReadTimeout(110000);
        connection.setRequestProperty("Authorization","Bearer "+credentials.getString("token"));
        connection.setRequestProperty("Accept","application/json");
        boolean sent=false;
        try{
            if(body!=null){
                connection.setRequestMethod("POST");connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=connection.getOutputStream()){sent=true;out.write(bytes);}
            }
            int status=connection.getResponseCode();
            InputStream stream=status<400?connection.getInputStream():connection.getErrorStream();
            if(stream==null)throw new IOException("服务未返回响应");
            JSONObject result;
            try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n,total=0;
                while((n=in.read(buffer))!=-1){total+=n;if(total>4*1024*1024)throw new IOException("响应过大");out.write(buffer,0,n);}
                try{result=new JSONObject(out.toString("UTF-8"));}catch(JSONException e){throw new IOException("服务正在唤醒或响应异常，请稍后重试");}
            }
            if(status!=200)throw new Failure(status,result.optString("error","http_"+status));
            return result;
        }catch(Failure e){throw e;}catch(Exception e){
            if(body!=null&&sent)throw new Failure(502,"write_uncertain");throw e;
        }finally{connection.disconnect();}
    }
}
