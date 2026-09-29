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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.flyve.inventory.InventoryTask;
import org.glpi.inventory.agent.R;
import org.glpi.inventory.agent.schema.ServerSchema;
import org.glpi.inventory.agent.utils.AgentLog;
import org.glpi.inventory.agent.utils.Helpers;
import org.glpi.inventory.agent.utils.HttpInventory;
import org.glpi.inventory.agent.utils.LocalPreferences;

import java.util.ArrayList;

public class RunInventoryReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN_NOW = "org.glpi.inventory.agent.ACTION_RUN_NOW";

    @Override
    public void onReceive(Context context, Intent intent) {
        AgentLog.d("RunInventoryReceiver: onReceive called with action=" + intent.getAction());

        if (ACTION_RUN_NOW.equals(intent.getAction())) {
            AgentLog.d("RunInventoryReceiver: ACTION_RUN_NOW matched. Starting forced inventory.");

            // Use goAsync() to extend the BroadcastReceiver's life beyond 10 seconds
            final PendingResult pendingResult = goAsync();
            final Context appContext = context.getApplicationContext();

            // Run inventory in a background thread
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        doForcedInventory(appContext);
                    } catch (Exception e) {
                        AgentLog.e("RunInventoryReceiver: Error during forced inventory: " + e.getMessage());
                    } finally {
                        // Signal that we're done
                        pendingResult.finish();
                    }
                }
            }).start();
        }
    }

    private void doForcedInventory(final Context context) {
        AgentLog.d("RunInventoryReceiver: Starting forced inventory execution");

        final InventoryTask inventory = new InventoryTask(context, Helpers.getAgentDescription(context), true);
        final HttpInventory httpInventory = new HttpInventory(context);
        ArrayList<String> serverArray = new LocalPreferences(context).loadServer();

        if (!serverArray.isEmpty()) {
            AgentLog.d("RunInventoryReceiver: Found " + serverArray.size() + " server(s). Sending inventory.");
            for (final String serverName : serverArray) {
                final ServerSchema model = httpInventory.setServerModel(serverName);
                inventory.setTag(model.getTag());
                inventory.setAssetItemtype(model.getItemtype());
                inventory.getXML(new InventoryTask.OnTaskCompleted() {
                    @Override
                    public void onTaskSuccess(String data) {
                        AgentLog.d("RunInventoryReceiver: XML generated successfully for server: " + serverName);
                        ServerSchema model = httpInventory.setServerModel(serverName);
                        if (!model.getSerial().trim().isEmpty()) {
                            data = data.replaceAll("<SSN>(.*)</SSN>", "<SSN>" + model.getSerial() + "</SSN>");
                        }

                        if (!model.getName().trim().isEmpty()) {
                            data = data.replaceAll("<NAME>(.*)</NAME>", "<NAME>" + model.getName() + "</NAME>");
                        }
                        httpInventory.sendInventory(data, model, new HttpInventory.OnTaskCompleted() {
                            @Override
                            public void onTaskSuccess(String data) {
                                AgentLog.d("RunInventoryReceiver: Inventory sent successfully to " + serverName);
                                Helpers.sendToNotificationBar(context, context.getResources().getString(R.string.inventory_notification_sent));
                            }

                            @Override
                            public void onTaskError(String error) {
                                AgentLog.e("RunInventoryReceiver: Error sending inventory to " + serverName + ": " + error);
                                Helpers.sendToNotificationBar(context, context.getResources().getString(R.string.inventory_notification_fail));
                            }
                        });
                    }

                    @Override
                    public void onTaskError(Throwable error) {
                        AgentLog.e("RunInventoryReceiver: Error generating XML: " + error.getMessage());
                        Helpers.sendToNotificationBar(context, context.getResources().getString(R.string.inventory_notification_fail));
                    }
                });
            }
        } else {
            AgentLog.d("RunInventoryReceiver: No servers configured. Cannot send inventory.");
            Helpers.sendToNotificationBar(context, context.getResources().getString(R.string.inventory_no_server));
        }
    }
}
