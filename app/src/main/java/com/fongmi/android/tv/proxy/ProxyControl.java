package com.fongmi.android.tv.proxy;

import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.nekohasekai.libbox.CommandClient;
import io.nekohasekai.libbox.CommandClientHandler;
import io.nekohasekai.libbox.CommandClientOptions;
import io.nekohasekai.libbox.ConnectionEvents;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LogIterator;
import io.nekohasekai.libbox.OutboundGroup;
import io.nekohasekai.libbox.OutboundGroupItem;
import io.nekohasekai.libbox.OutboundGroupItemIterator;
import io.nekohasekai.libbox.OutboundGroupIterator;
import io.nekohasekai.libbox.ServiceStatusMessage;
import io.nekohasekai.libbox.StatusMessage;
import io.nekohasekai.libbox.StringIterator;

public class ProxyControl implements CommandClientHandler {

    private static final String TAG = "ProxyControl";
    private static final ProxyControl instance = new ProxyControl();

    private final Map<String, Integer> delay = new LinkedHashMap<>();
    private CommandClient client;
    private long lastTotal;
    private long lastActive;

    public static ProxyControl get() {
        return instance;
    }

    public synchronized void connect() {
        try {
            disconnect();
            CommandClientOptions options = new CommandClientOptions();
            options.setStatusInterval(2000);
            options.addCommand(Libbox.CommandStatus);
            options.addCommand(Libbox.CommandGroup);
            client = Libbox.newCommandClient(this, options);
            client.connect();
            lastActive = System.currentTimeMillis();
            lastTotal = -1;
        } catch (Exception e) {
            Log.w(TAG, "connect failed", e);
            client = null;
        }
    }

    public synchronized void disconnect() {
        try {
            if (client != null) client.disconnect();
        } catch (Exception ignored) {
        }
        client = null;
        delay.clear();
    }

    public long idleMillis() {
        return System.currentTimeMillis() - lastActive;
    }

    public void select(String tag) {
        try {
            if (client != null) client.selectOutbound(ProxySetting.SELECT, tag);
        } catch (Exception e) {
            Log.w(TAG, "select failed", e);
        }
    }

    public void urlTest() {
        try {
            if (client != null) client.urlTest(ProxySetting.AUTO);
        } catch (Exception e) {
            Log.w(TAG, "url test failed", e);
        }
    }

    public List<ProxyNode> getNodes() {
        List<ProxyNode> nodes = ProxySetting.getNodes();
        for (ProxyNode node : nodes) {
            Integer value = delay.get(node.getTag());
            node.setDelay(value == null ? 0 : value);
        }
        return nodes;
    }

    @Override
    public void writeStatus(StatusMessage message) {
        try {
            long total = message.getUplinkTotal() + message.getDownlinkTotal();
            if (lastTotal >= 0 && total > lastTotal) lastActive = System.currentTimeMillis();
            lastTotal = total;
        } catch (Exception ignored) {
        }
    }

    @Override
    public void writeGroups(OutboundGroupIterator groups) {
        if (groups == null) return;
        try {
            while (groups.hasNext()) {
                OutboundGroup group = groups.next();
                if (group == null) continue;
                OutboundGroupItemIterator items = group.getItems();
                if (items == null) continue;
                while (items.hasNext()) {
                    OutboundGroupItem item = items.next();
                    if (item == null) continue;
                    delay.put(item.getTag(), item.getURLTestDelay());
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void writeLogs(LogIterator logs) {
    }

    @Override
    public void writeConnectionEvents(ConnectionEvents events) {
    }

    @Override
    public void writeServiceStatus(ServiceStatusMessage message) {
    }

    @Override
    public void initializeClashMode(StringIterator modes, String current) {
    }

    @Override
    public void updateClashMode(String mode) {
    }

    @Override
    public void setDefaultLogLevel(int level) {
    }

    @Override
    public void clearLogs() {
    }

    @Override
    public void connected() {
    }

    @Override
    public void disconnected(String message) {
    }

    public List<String> tags() {
        return new ArrayList<>(delay.keySet());
    }
}
