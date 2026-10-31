package org.labday.app;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.*;
public class ReminderReceiver extends BroadcastReceiver {
    public static void schedule(Context c,JSONObject reminder){
        Intent intent=new Intent(c,ReminderReceiver.class).setAction("LABDAY_REMINDER").setData(android.net.Uri.parse("labday://reminder/"+reminder.optString("id")));
        intent.putExtra("id",reminder.optString("id"));
        PendingIntent pending=PendingIntent.getBroadcast(c,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,reminder.optLong("at"),pending);
    }
    public static void cancel(Context c,String id){
        Intent intent=new Intent(c,ReminderReceiver.class).setAction("LABDAY_REMINDER").setData(android.net.Uri.parse("labday://reminder/"+id));
        PendingIntent pending=PendingIntent.getBroadcast(c,0,intent,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
        if(pending!=null){((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(pending);pending.cancel();}
    }
    @Override public void onReceive(Context c,Intent intent){
        Store store=new Store(c);
        try{
            if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())){
                JSONArray rows=store.all("reminders");
                for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);if(r!=null&&r.optLong("at")>System.currentTimeMillis())schedule(c,r);}
                SyncEngine.schedule(c);return;
            }
            String id=intent.getStringExtra("id");JSONObject r=store.get("reminders",id);if(r==null)return;
            if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
            NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel("experiments","实验提醒",NotificationManager.IMPORTANCE_HIGH));
            Intent target=r.optString("recordId").isEmpty()?new Intent(c,MainActivity.class):new Intent(c,EditorActivity.class).putExtra("id",r.optString("recordId"));
            target.setData(android.net.Uri.parse("labday://notification/"+id));
            PendingIntent open=PendingIntent.getActivity(c,id.hashCode(),target,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            manager.notify(id.hashCode(),new Notification.Builder(c,"experiments").setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(r.optString("title","实验提醒")).setContentText("请查看实验安排或步骤计时")
                .setContentIntent(open).setAutoCancel(true).build());
            store.remove("reminders",id);
        }finally{store.close();}
    }
}
