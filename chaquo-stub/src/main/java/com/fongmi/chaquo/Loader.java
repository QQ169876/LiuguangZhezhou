package com.fongmi.chaquo;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

/**
 * 老盒子兼容版用的空壳实现。
 * 兼容版不带 Python 运行环境（体积减半、minSdk 降到 21），
 * 遇到 .py 类型的采集源直接返回空源，不会崩溃。
 */
public class Loader {

    public Loader() {
    }

    public Spider spider(String api) {
        return new SpiderNull();
    }
}
