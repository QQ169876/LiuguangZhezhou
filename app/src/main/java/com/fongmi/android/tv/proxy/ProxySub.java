package com.fongmi.android.tv.proxy;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.List;

public class ProxySub {

    public interface Callback {
        void onResult(int count, String error);
    }

    public static void update(Callback callback) {
        Task.execute(() -> {
            int count = 0;
            String error = null;
            try {
                String url = ProxySetting.getSub();
                if (!url.startsWith("http")) error = "invalid url";
                else {
                    String text = OkHttp.string(url);
                    List<ProxyNode> nodes = ProxyParser.parse(text);
                    if (nodes.isEmpty()) error = "empty";
                    else {
                        ProxySetting.putNodes(nodes);
                        ProxySetting.putUpdate(System.currentTimeMillis());
                        count = nodes.size();
                    }
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            final int result = count;
            final String message = error;
            App.post(() -> callback.onResult(result, message));
        });
    }

    public static int add(String link) {
        List<ProxyNode> nodes = new ArrayList<>();
        for (String line : link.split("\n")) {
            ProxyNode node = ProxyParser.uri(line.trim());
            if (node != null) nodes.add(node);
        }
        if (nodes.isEmpty()) return 0;
        List<ProxyNode> all = ProxySetting.getNodes();
        all.addAll(nodes);
        ProxySetting.putNodes(all);
        return nodes.size();
    }
}
