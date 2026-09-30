package com.fongmi.android.tv.api.config;
import java.util.Collections;

import com.fongmi.android.tv.bean.Rule;

import java.util.ArrayList;
import java.util.List;

public class RuleConfig {

    private List<String> ads = Collections.emptyList();
    private List<Rule> rules = Collections.emptyList();
    private boolean dirty;

    public static RuleConfig get() {
        return Loader.INSTANCE;
    }

    public List<String> getAds() {
        if (dirty) merge();
        return ads;
    }

    public List<Rule> getRules() {
        if (dirty) merge();
        return rules;
    }

    void invalidate() {
        dirty = true;
    }

    private void merge() {
        List<String> ads = new ArrayList<>(VodConfig.get().getAds());
        ads.addAll(LiveConfig.get().getAds());
        this.ads = ads;
        List<Rule> rules = new ArrayList<>(VodConfig.get().getRules());
        rules.addAll(LiveConfig.get().getRules());
        this.rules = rules;
        dirty = false;
    }

    private static class Loader {
        static volatile RuleConfig INSTANCE = new RuleConfig();
    }
}
