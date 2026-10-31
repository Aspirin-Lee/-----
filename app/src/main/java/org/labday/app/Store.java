package org.labday.app;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import org.json.*;
import java.time.*;
import java.util.*;

public class Store extends SQLiteOpenHelper {
    public Store(Context c){super(c,"labday.db",null,1);}
    public void onCreate(SQLiteDatabase db){
        for(String name:new String[]{"records","drafts","templates","cache","outbox","reminders"})
            db.execSQL("CREATE TABLE "+name+" (id TEXT PRIMARY KEY, json TEXT NOT NULL)");
    }
    public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
    public static void put(JSONObject o,String k,Object v){try{o.put(k,v);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    public static JSONObject copy(JSONObject o){try{return new JSONObject(o.toString());}catch(JSONException e){throw new IllegalArgumentException(e);}}
    public static JSONObject fresh(){
        JSONObject o=new JSONObject();put(o,"id",UUID.randomUUID().toString());put(o,"date",LocalDate.now().toString());
        put(o,"title","");put(o,"photos",new JSONArray());put(o,"done",false);put(o,"updatedAt",Instant.now().toString());return o;
    }
    private void table(String name){if(!Arrays.asList("records","drafts","templates","cache","outbox","reminders").contains(name))throw new IllegalArgumentException();}
    public synchronized void set(String table,String id,JSONObject o){
        table(table);ContentValues cv=new ContentValues();cv.put("id",id);cv.put("json",o.toString());
        if(getWritableDatabase().insertWithOnConflict(table,null,cv,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("本机存储写入失败");
    }
    public synchronized JSONObject get(String table,String id){
        table(table);try(Cursor c=getReadableDatabase().query(table,new String[]{"json"},"id=?",new String[]{id},null,null,null)){
            if(c.moveToFirst())try{return new JSONObject(c.getString(0));}catch(JSONException e){throw new IllegalStateException("本机数据损坏，请先导出备份",e);}
        }return null;
    }
    public synchronized JSONArray all(String table){
        table(table);JSONArray rows=new JSONArray();
        try(Cursor c=getReadableDatabase().query(table,new String[]{"json"},null,null,null,null,"rowid DESC")){
            while(c.moveToNext())try{rows.put(new JSONObject(c.getString(0)));}catch(JSONException e){throw new IllegalStateException("本机数据损坏",e);}
        }return rows;
    }
    public synchronized void remove(String table,String id){table(table);getWritableDatabase().delete(table,"id=?",new String[]{id});}
    public synchronized void saveRecord(JSONObject record){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{put(record,"updatedAt",Instant.now().toString());set("records",record.optString("id"),record);
            remove("drafts",record.optString("id"));db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized void migrate(Context c){
        if(c.getSharedPreferences("migration",0).getBoolean("done",false))return;
        String json=c.getSharedPreferences("MainActivity",0).getString("records","[]");
        try{
            JSONArray old=new JSONArray(json);
            for(int i=0;i<old.length();i++){
                JSONObject o=old.getJSONObject(i);
                if(o.optString("id").isEmpty())put(o,"id",UUID.randomUUID().toString());
                JSONArray oldPhotos=o.optJSONArray("photos"), photos=new JSONArray();
                if(oldPhotos!=null)for(int j=0;j<oldPhotos.length();j++){
                    Object item=oldPhotos.get(j);
                    if(item instanceof String){JSONObject p=new JSONObject();put(p,"file",item);put(p,"caption","");photos.put(p);}
                    else photos.put(item);
                }
                put(o,"photos",photos);
                if(get("records",o.optString("id"))==null)set("records",o.optString("id"),o);
            }
            c.getSharedPreferences("migration",0).edit().putBoolean("done",true).commit();
        }catch(JSONException e){throw new IllegalStateException("旧记录迁移失败，请勿卸载应用",e);}
    }
    public synchronized void queue(JSONObject record){
        JSONArray pending=all("outbox");
        String digest=record.toString();
        for(int i=0;i<pending.length();i++){
            JSONObject job=pending.optJSONObject(i);
            if(job!=null && job.optString("recordId").equals(record.optString("id")) && job.optString("snapshot").equals(digest))
                return;
        }
        JSONObject job=new JSONObject();String id=UUID.randomUUID().toString();
        put(job,"id",id);put(job,"recordId",record.optString("id"));put(job,"snapshot",digest);
        put(job,"state","pending");put(job,"message","等待同步");put(job,"createdAt",Instant.now().toString());
        set("outbox",id,job);
    }
    public synchronized String syncState(String recordId){
        JSONArray rows=all("outbox");
        for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o!=null&&o.optString("recordId").equals(recordId))return o.optString("message");}
        return "仅保存在手机";
    }
}
