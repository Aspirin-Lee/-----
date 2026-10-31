package org.labday.app;
import android.app.job.*;
import java.util.concurrent.*;
public class SyncJob extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public boolean onStartJob(JobParameters params){
        worker.execute(()->{SyncEngine.run(getApplicationContext());jobFinished(params,SyncEngine.hasPending(getApplicationContext()));});return true;
    }
    @Override public boolean onStopJob(JobParameters params){return true;}
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
}
