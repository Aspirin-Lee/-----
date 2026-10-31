package org.labday.app;
import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;

@RunWith(AndroidJUnit4.class)
public class WorkflowTest {
    private EditText field(Activity activity,String label){
        return find(activity.getWindow().getDecorView(),label);
    }
    private EditText find(View view,String label){
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                View child=group.getChildAt(i);
                if(child instanceof TextView&&((TextView)child).getText().toString().equals(label)&&i+1<group.getChildCount()&&group.getChildAt(i+1) instanceof EditText)
                    return (EditText)group.getChildAt(i+1);
                EditText found=find(child,label);if(found!=null)return found;
            }
        }return null;
    }
    @Test public void editorRetainsDraftAcrossActivityRecreation()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Store store=new Store(c);JSONObject record=Store.fresh();Store.put(record,"title","Synthetic workflow");
        String id=record.getString("id");store.set("drafts",id,record);
        try(ActivityScenario<EditorActivity> scenario=ActivityScenario.launch(new Intent(c,EditorActivity.class).putExtra("id",id))){
            scenario.onActivity(a->{EditText result=field(a,"结果与观察");assertNotNull(result);result.setText("Synthetic observation after recreation");});
            scenario.recreate();
            scenario.onActivity(a->assertEquals("Synthetic observation after recreation",field(a,"结果与观察").getText().toString()));
        }
        assertEquals("Synthetic observation after recreation",store.get("drafts",id).getString("result"));
        store.remove("drafts",id);store.close();
    }
    @Test public void mainActivityLaunchesAndCapturesPreview()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->assertNotNull(field(a,"搜索名称、样本、项目、设计和结果")));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);File dir=new File(c.getExternalFilesDir(null),"screenshots");dir.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(dir,"main.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}
            screenshot.recycle();
            try(android.os.ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("screencap -p /sdcard/Download/labday-main.png");
                InputStream output=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){
                byte[] bytes=new byte[1024];while(output.read(bytes)!=-1){}
            }
        }
    }
}
