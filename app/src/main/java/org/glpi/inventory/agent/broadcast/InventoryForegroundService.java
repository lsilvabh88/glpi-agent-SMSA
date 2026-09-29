/**
 * ---------------------------------------------------------------------
 * GLPI Android Inventory Agent
 * Copyright (C) 2019 Teclib.
 *
 * https://glpi-project.org
 *
 * Based on Flyve MDM Inventory Agent For Android
 * Copyright © 2018 Teclib. All rights reserved.
 *
 * ---------------------------------------------------------------------
 *
 *  LICENSE
 *
 *  This file is part of GLPI Android Inventory Agent.
 *
 *  GLPI Android Inventory Agent is a subproject of GLPI.
 *
 *  GLPI Android Inventory Agent is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 3
 *  of the License, or (at your option) any later version.
 *
 *  GLPI Android Inventory Agent is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  ---------------------------------------------------------------------
 *  @copyright Copyright © 2019 Teclib. All rights reserved.
 *  @license   GPLv3 https://www.gnu.org/licenses/gpl-3.0.html
 *  @link      https://github.com/glpi-project/android-inventory-agent
 *  @link      https://glpi-project.org/glpi-network/
 *  ---------------------------------------------------------------------
 */

package org.glpi.inventory.agent.broadcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.flyve.inventory.InventoryTask;
import org.glpi.inventory.agent.R;
import org.glpi.inventory.agent.schema.ServerSchema;
import org.glpi.inventory.agent.utils.AgentLog;
import org.glpi.inventory.agent.utils.Helpers;
import org.glpi.inventory.agent.utils.HttpInventory;
import org.glpi.inventory.agent.utils.LocalPreferences;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class InventoryForegroundService extends Service {

    private static final String CHANNEL_ID = "glpi_forced_inventory";
    private static final int NOTIFICATION_ID = 7788;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AgentLog.d("InventoryForegroundService: Started by MDM command.");

        // Show persistent foreground notification (required by Android 8+)
        startForeground(NOTIFICATION_ID, buildNotification());

        // Run inventory in background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runInventory();
                } catch (Exception e) {
                    AgentLog.e("InventoryForegroundService: Exception: " + e.getMessage());
                } finally {
                    AgentLog.d("InventoryForegroundService: All done. Stopping service.");
                    stopSelf();
                }
            }
        }).start();

        // Do not restart service if killed
        return START_NOT_STICKY;
    }

    private void runInventory() {
        final Context context = getApplicationContext();
        AgentLog.d("InventoryForegroundService: Running forced inventory.");

        final InventoryTask inventory = new InventoryTask(context, Helpers.getAgentDescription(context), true);
        final HttpInventory httpInventory = new HttpInventory(context);
        ArrayList<String> serverArray = new LocalPreferences(context).loadServer();

        if (serverArray.isEmpty()) {
            AgentLog.d("InventoryForegroundService: No servers configured.");
            Helpers.sendToNotificationBar(context, context.getResources().getString(R.string.inventory_no_server));
            return;
        }

        AgentLog.d("InventoryForegroundService: Sending to " + serverArray.size() + " server(s).");

        // Track pending async tasks so we know when all are done
        final AtomicInteger pending = new AtomicInteger(serverArray.size());
        final Object lock = new Object();

        for (final String serverName : serverArray) {
            final ServerSchema model = httpInventory.setServerModel(serverName);
            inventory.setTag(model.getTag());
            inventory.setAssetItemtype(model.getItemtype());

            inventory.getXML(new InventoryTask.OnTaskCompleted() {
                @Override
                public void onTaskSuccess(String data) {
                    ServerSchema currentModel = httpInventory.setServerModel(serverName);
                    if (!currentModel.getSerial().trim().isEmpty()) {
                        data = data.replaceAll("<SSN>(.*)</SSN>", "<SSN>" + currentModel.getSerial() + "</SSN>");
                    }
                    if (!currentModel.getName().trim().isEmpty()) {
                        data = data.replaceAll("<NAME>(.*)</NAME>", "<NAME>" + currentModel.getName() + "</NAME>");
                    }

                    httpInventory.sendInventory(data, currentModel, new HttpInventory.OnTaskCompleted() {
                        @Override
                        public void onTaskSuccess(String data) {
                            AgentLog.d("InventoryForegroundService: Success sending to " + serverName);
                            Helpers.sendToNotificationBar(context, context.getResources().getString(R.string.inventory_notification_sent));
                            decrementAndNotify(pending, lock);
                        }

                        @Override
                        public void onTaskError(String error) {
                            AgentLog.e("InventoryForegroundService: Error sending to " + serverName + ": " + error);
                            Helpers.sendToNotificationBar(context, context.getResources().getString(R.string.inventory_notification_fail));
                            decrementAndNotify(pending, lock);
                        }
                    });
                }

                @Override
                public void onTaskError(Throwable error) {
                    AgentLog.e("InventoryForegroundService: XML error: " + error.getMessage());
                    Helpers.sendToNotificationBar(context, context.getResources().getString(R.string.inventory_notification_fail));
                    decrementAndNotify(pending, lock);
                }
            });
        }

        // Wait until all async callbacks complete (max 3 minutes)
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 3 * 60 * 1000;
            while (pending.get() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void decrementAndNotify(AtomicInteger pending, Object lock) {
        if (pending.decrementAndGet() <= 0) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GLPI Inventory",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setLightColor(Color.BLUE);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Enviando inventário...")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
