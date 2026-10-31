package org.labday.app;
import android.app.job.*;
import android.content.*;
import android.graphics.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.time.Instant;

public class SyncEngine {
    private static final Object LOCK=new Object();
    public static void schedule(Context c){
        JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        scheduler.schedule(new JobInfo.Builder(200,new ComponentName(c,SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
    public static boolean hasPending(Context c){
        Store store=new Store(c);try{JSONArray jobs=store.all("outbox");for(int i=0;i<jobs.length();i++){JSONObject j=jobs.optJSONObject(i);if(j!=null&&j.optString("state").equals("pending"))return true;}return false;}finally{store.close();}
    }
    public static String run(Context context) {
        synchronized(LOCK){
            Store store=new Store(context);int success=0,failed=0;
            try{
                JSONArray jobs=store.all("outbox");
                for(int i=jobs.length()-1;i>=0;i--){
                    JSONObject job=jobs.optJSONObject(i);if(job==null||!job.optString("state").equals("pending"))continue;
                    try{
                        JSONObject record=new JSONObject(job.getString("snapshot"));
                        JSONObject body=Store.copy(record);Store.put(body,"operationId",job.getString("id"));
                        JSONArray images=new JSONArray(),photos=record.optJSONArray("photos");
                        if(photos!=null&&photos.length()>3)throw new IOException("每次同步最多3张图片，请拆分为多次执行记录");
                        if(photos!=null)for(int p=0;p<photos.length();p++){
                            JSONObject photo=photos.getJSONObject(p);
                            String name=photo.getString("file");if(!name.matches("[A-Za-z0-9._-]+"))throw new IOException("图片路径无效");
                            File file=new File(context.getFilesDir(),name);if(!file.exists())throw new IOException("本机原图缺失，请恢复备份后重试");
                            Bitmap bitmap=Media.bitmap(file,1600);
                            byte[] bytes;try(ByteArrayOutputStream out=new ByteArrayOutputStream()){bitmap.compress(Bitmap.CompressFormat.JPEG,85,out);bytes=out.toByteArray();}finally{bitmap.recycle();}
                            if(bytes.length>2*1024*1024)throw new IOException("图片压缩后仍超过2MB");
                            JSONObject image=new JSONObject();Store.put(image,"mime","image/jpeg");Store.put(image,"data",Base64.encodeToString(bytes,Base64.NO_WRAP));Store.put(image,"caption",photo.optString("caption"));images.put(image);
                        }
                        Store.put(body,"images",images);body.remove("photos");body.remove("notionText");
                        // Only forward stable scientific fields. Device timestamps and paths stay local.
                        JSONObject payload=new JSONObject();
                        for(String key:new String[]{"operationId","title","date","design","result","sample","project","anomaly","imageNotes","done","images"})
                            if(body.has(key))Store.put(payload,key,body.get(key));
                        JSONObject response=Gateway.request(context,"/experiments/"+record.getString("notionId")+"/logs",payload);
                        if(!response.optBoolean("synced"))throw new IOException("服务未确认同步结果");
                        Store.put(job,"state","synced");Store.put(job,"message","已同步 "+Instant.now());success++;
                    }catch(Exception e){
                        boolean uncertain=e instanceof Gateway.Failure&&((Gateway.Failure)e).code.equals("write_uncertain");
                        int attempts=job.optInt("attempts")+1;Store.put(job,"attempts",attempts);
                        boolean transientFailure=e instanceof Gateway.Failure &&
                            java.util.Arrays.asList("notion_unreachable","notion_rate_limited","gateway_busy","image_upload_incomplete").contains(((Gateway.Failure)e).code);
                        boolean retry=transientFailure&&attempts<5;
                        Store.put(job,"state",uncertain?"uncertain":retry?"pending":"failed");
                        Store.put(job,"message",(uncertain?"需核查：":retry?"等待网络重试：":"同步失败：")+e.getMessage());failed++;
                    }
                    store.set("outbox",job.optString("id"),job);
                }
            }finally{store.close();}
            return "同步成功 "+success+" 项；失败或需核查 "+failed+" 项";
        }
    }
}
