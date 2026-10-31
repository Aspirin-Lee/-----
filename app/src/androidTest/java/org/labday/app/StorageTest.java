package org.labday.app;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.database.DatabaseErrorHandler;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class StorageTest {
    private Context context;private File root;private Store store;
    @Before public void setup(){
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        root=new File(base.getCacheDir(),"test-"+UUID.randomUUID());assertTrue(root.mkdirs());
        context=new ContextWrapper(base){
            @Override public File getFilesDir(){return root;}
            @Override public File getDatabasePath(String name){return new File(root,name);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
        };
        store=new Store(context);
    }
    @After public void cleanup(){store.close();delete(root);}
    private void delete(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)delete(child);f.delete();}
    @Test public void draftsSurviveReopenAndCommitSeparately()throws Exception{
        JSONObject r=Store.fresh();Store.put(r,"title","Synthetic execution");store.set("drafts",r.getString("id"),r);
        store.close();store=new Store(context);assertEquals("Synthetic execution",store.get("drafts",r.getString("id")).getString("title"));
        store.saveRecord(r);assertNull(store.get("drafts",r.getString("id")));assertNotNull(store.get("records",r.getString("id")));
    }
    @Test public void queuedSnapshotsAreImmutableAndDeduplicated()throws Exception{
        JSONObject r=Store.fresh();Store.put(r,"title","Synthetic execution");store.queue(r);Store.put(r,"updatedAt","later-fixture-timestamp");store.queue(r);assertEquals(1,store.all("outbox").length());
        Store.put(r,"result","New observation");store.queue(r);assertEquals(2,store.all("outbox").length());
        String first=store.all("outbox").getJSONObject(1).getString("snapshot");assertFalse(first.contains("New observation"));
    }
    @Test public void backupRestoresImagesAndPreservesExistingRecords()throws Exception{
        JSONObject r=Store.fresh();Store.put(r,"title","Synthetic execution");
        File image=new File(root,"fixture.image");try(FileOutputStream out=new FileOutputStream(image)){out.write(new byte[]{1,2,3});}
        JSONObject photo=new JSONObject();photo.put("file","fixture.image");photo.put("caption","Synthetic caption");
        r.getJSONArray("photos").put(photo);store.saveRecord(r);
        File archive=new File(root,"backup.zip");Archive.export(context,Uri.fromFile(archive));Archive.restore(context,Uri.fromFile(archive));
        JSONArray rows=store.all("records");assertEquals(2,rows.length());
        assertNotEquals(rows.getJSONObject(0).getString("id"),rows.getJSONObject(1).getString("id"));
        String restored=rows.getJSONObject(0).getJSONArray("photos").getJSONObject(0).getString("file");
        assertTrue(new File(root,restored).exists());assertEquals(0,store.all("outbox").length());
    }
    @Test public void maliciousBackupPathsAreRejectedWithoutChangingRecords()throws Exception{
        File archive=new File(root,"bad.zip");try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(new FileOutputStream(archive))){
            zip.putNextEntry(new java.util.zip.ZipEntry("photos/../../escape"));zip.write(1);zip.closeEntry();
        }
        try{Archive.restore(context,Uri.fromFile(archive));fail("Traversal accepted");}catch(IOException expected){}
        assertEquals(0,store.all("records").length());
    }
}
