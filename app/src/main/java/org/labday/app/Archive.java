package org.labday.app;
import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class Archive {
    private static String safe(String name)throws IOException{
        if(name==null||!name.matches("[A-Za-z0-9._-]+")||name.equals(".")||name.equals(".."))throw new IOException("备份图片名称无效");return name;
    }
    private static void write(ZipOutputStream zip,String path,byte[] bytes)throws IOException{
        zip.putNextEntry(new ZipEntry(path));zip.write(bytes);zip.closeEntry();
    }
    public static String export(Context c,Uri uri)throws Exception{
        Store store=new Store(c);JSONObject data=new JSONObject();JSONArray records=store.all("records"),drafts=store.all("drafts");
        Store.put(data,"format","LabDay");Store.put(data,"version",2);Store.put(data,"records",records);Store.put(data,"drafts",drafts);
        Store.put(data,"templates",store.all("templates"));store.close();
        Set<String> photos=new HashSet<>();
        for(JSONArray rows:new JSONArray[]{records,drafts})for(int i=0;i<rows.length();i++){
            JSONArray media=rows.getJSONObject(i).optJSONArray("photos");
            if(media!=null)for(int j=0;j<media.length();j++)photos.add(safe(media.getJSONObject(j).getString("file")));
        }
        byte[] manifestBytes=data.toString(2).getBytes(StandardCharsets.UTF_8);
        StringBuilder markdown=new StringBuilder("# 实验记录导出\n\n");
        for(int i=0;i<records.length();i++){
            JSONObject o=records.getJSONObject(i);markdown.append("## ").append(o.optString("title")).append("\n\n");
            for(String key:new String[]{"date","project","sample","design","result","anomaly","imageNotes"})
                markdown.append("**").append(key).append("**\n\n").append(o.optString(key)).append("\n\n");
            JSONArray media=o.optJSONArray("photos");
            if(media!=null)for(int p=0;p<media.length();p++){JSONObject photo=media.getJSONObject(p);markdown.append("![").append(photo.optString("caption").replace("]","")).append("](photos/").append(safe(photo.getString("file"))).append(")\n\n");}
        }
        byte[] markdownBytes=markdown.toString().getBytes(StandardCharsets.UTF_8);
        if(manifestBytes.length>25*1024*1024||markdownBytes.length>25*1024*1024)throw new IOException("文字数据超过单次备份限制25MB");
        long totalBytes=manifestBytes.length+markdownBytes.length;
        for(String name:photos){File image=new File(c.getFilesDir(),name);if(!image.exists())throw new IOException("原图缺失："+name);
            if(image.length()>25*1024*1024)throw new IOException("单张图片超过25MB");totalBytes+=image.length();}
        if(totalBytes>300L*1024*1024)throw new IOException("本次备份超过300MB，尚不支持分卷；请先将部分图片另行归档。");
        try(OutputStream raw=c.getContentResolver().openOutputStream(uri,"w")){
            if(raw==null)throw new IOException("无法打开备份文件");
            try(ZipOutputStream zip=new ZipOutputStream(raw)){
                write(zip,"labday.json",manifestBytes);
                for(String name:photos){
                    File file=new File(c.getFilesDir(),name);if(!file.exists())throw new IOException("原图缺失："+name);
                    zip.putNextEntry(new ZipEntry("photos/"+name));
                    try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);}zip.closeEntry();
                }
                write(zip,"records.md",markdownBytes);
            }
        }
        return "备份完成："+records.length()+" 条记录、"+drafts.length()+" 份草稿、"+photos.size()+" 张原图。连接密钥不在备份中。";
    }
    public static String restore(Context c,Uri uri)throws Exception{
        Map<String,File> staged=new HashMap<>();JSONObject data=null;long total=0;
        List<File> created=new ArrayList<>();boolean committed=false;
        try{
            try(InputStream raw=c.getContentResolver().openInputStream(uri)){
                if(raw==null)throw new IOException("无法打开备份");
                try(ZipInputStream zip=new ZipInputStream(raw)){
                    ZipEntry entry;int entries=0;
                    while((entry=zip.getNextEntry())!=null){
                        if(++entries>5000)throw new IOException("备份条目过多");
                        if(entry.isDirectory())continue;
                        String name=entry.getName();
                        if(!name.equals("labday.json")&&!name.equals("records.md")&&!name.startsWith("photos/"))throw new IOException("备份包含未知文件");
                        if(name.equals("records.md")){
                            byte[] ignored=new byte[8192];int n;long item=0;
                            while((n=zip.read(ignored))!=-1){item+=n;total+=n;if(item>25*1024*1024||total>300L*1024*1024)throw new IOException("备份超过导入大小限制");}
                            continue;
                        }
                        String key=name.equals("labday.json")?name:"photos/"+safe(name.substring(7));
                        if(staged.containsKey(key))throw new IOException("备份包含重复文件");
                        File tmp=File.createTempFile("restore-",".tmp",c.getCacheDir());staged.put(key,tmp);
                        try(OutputStream out=new FileOutputStream(tmp)){
                            byte[] b=new byte[8192];int n;long item=0;
                            while((n=zip.read(b))!=-1){
                                item+=n;total+=n;
                                if(item>25*1024*1024||total>300L*1024*1024)throw new IOException("备份超过导入大小限制");
                                out.write(b,0,n);
                            }
                        }
                    }
                }
            }
            File manifest=staged.get("labday.json");if(manifest==null)throw new IOException("缺少备份清单");
            data=new JSONObject(new String(java.nio.file.Files.readAllBytes(manifest.toPath()),StandardCharsets.UTF_8));
            if(!data.optString("format").equals("LabDay")||data.optInt("version")!=2)throw new IOException("不支持的备份格式");
            Map<String,String> photoNames=new HashMap<>(),ids=new HashMap<>();
            for(String key:staged.keySet())if(key.startsWith("photos/")){
                String name=UUID.randomUUID()+".image";File dest=new File(c.getFilesDir(),name);
                java.nio.file.Files.copy(staged.get(key).toPath(),dest.toPath());created.add(dest);photoNames.put(key.substring(7),name);
            }
            Store store=new Store(c);var db=store.getWritableDatabase();db.beginTransaction();int count=0;
            try{
                for(String table:new String[]{"records","drafts","templates"}){
                    JSONArray rows=data.optJSONArray(table);if(rows==null)throw new IOException("备份缺少数据表");
                    if(rows.length()>10000)throw new IOException("记录数量超出限制");
                    for(int i=0;i<rows.length();i++){
                        JSONObject o=Store.copy(rows.getJSONObject(i));String old=o.getString("id");
                        if(old.length()>100)throw new IOException("记录编号无效");
                        String id=ids.computeIfAbsent(old,k->UUID.randomUUID().toString());Store.put(o,"id",id);
                        JSONArray photos=o.optJSONArray("photos");
                        if(photos!=null)for(int p=0;p<photos.length();p++){
                            JSONObject photo=photos.getJSONObject(p);String mapped=photoNames.get(safe(photo.getString("file")));
                            if(mapped==null)throw new IOException("备份缺少原图");Store.put(photo,"file",mapped);
                        }
                        Store.put(o,"restored",true);store.set(table,id,o);count++;
                    }
                }
                db.setTransactionSuccessful();committed=true;
            }finally{db.endTransaction();store.close();}
            return "已恢复 "+count+" 项；保留原有记录。同步队列、提醒和连接密钥未恢复，避免重复写入。";
        }finally{
            for(File file:staged.values())file.delete();
            if(!committed)for(File file:created)file.delete();
        }
    }
}
