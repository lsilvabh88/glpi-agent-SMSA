package org.glpi.inventory.agent.broadcast;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;

import org.glpi.inventory.agent.utils.AgentLog;

public class RunInventoryReceiver extends BroadcastReceiver {

    public static final int FORCE_INVENTORY_JOB_ID = 4492016;

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("org.glpi.inventory.agent.ACTION_RUN_NOW".equals(intent.getAction())) {
            AgentLog.d("RunInventoryReceiver received ACTION_RUN_NOW. Forcing inventory run.");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                if (jobScheduler != null) {
                    PersistableBundle bundle = new PersistableBundle();
                    bundle.putBoolean("forceRun", true);

                    JobInfo jobInfo = new JobInfo.Builder(FORCE_INVENTORY_JOB_ID,
                            new ComponentName(context, InventoryJobScheduler.class))
                            .setMinimumLatency(1) // Run almost immediately
                            .setOverrideDeadline(1)
                            .setExtras(bundle)
                            .build();

                    jobScheduler.schedule(jobInfo);
                    AgentLog.d("Scheduled forced InventoryJobScheduler.");
                }
            } else {
                AgentLog.d("Forced inventory not supported via JobScheduler on SDK < 21.");
            }
        }
    }
}
