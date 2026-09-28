package com.fongmi.android.tv.proxy;

import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.StringBox;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ProxyPlatform implements PlatformInterface {

    private final ProxyService service;

    public ProxyPlatform(ProxyService service) {
        this.service = service;
    }

    @Override
    public int openTun(TunOptions options) throws Exception {
        if (VpnService.prepare(service) != null) throw new Exception("android: missing vpn permission");
        VpnService.Builder builder = service.new Builder();
        builder.setSession("Liuguang");
        builder.setMtu(options.getMTU());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);
        addAddress(builder, options.getInet4Address());
        addAddress(builder, options.getInet6Address());
        if (options.getAutoRoute()) {
            try {
                StringBox dns = options.getDNSServerAddress();
                if (dns != null && dns.getValue() != null && !dns.getValue().isEmpty()) builder.addDnsServer(dns.getValue());
            } catch (Exception ignored) {
            }
            boolean has4 = addRoute(builder, options.getInet4RouteAddress());
            if (!has4) addRoute(builder, options.getInet4RouteRange());
            boolean has6 = addRoute(builder, options.getInet6RouteAddress());
            if (!has6) addRoute(builder, options.getInet6RouteRange());
            if (!has4 && !has6) builder.addRoute("0.0.0.0", 0);
            StringIterator include = options.getIncludePackage();
            if (include != null) {
                while (include.hasNext()) {
                    try {
                        builder.addAllowedApplication(include.next());
                    } catch (Exception ignored) {
                    }
                }
            }
            StringIterator exclude = options.getExcludePackage();
            if (exclude != null) {
                while (exclude.hasNext()) {
                    try {
                        builder.addDisallowedApplication(exclude.next());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        ParcelFileDescriptor pfd = builder.establish();
        if (pfd == null) throw new Exception("android: establish failed");
        service.setFd(pfd);
        return pfd.getFd();
    }

    private void addAddress(VpnService.Builder builder, RoutePrefixIterator iterator) {
        if (iterator == null) return;
        while (iterator.hasNext()) {
            RoutePrefix prefix = iterator.next();
            builder.addAddress(prefix.address(), prefix.prefix());
        }
    }

    private boolean addRoute(VpnService.Builder builder, RoutePrefixIterator iterator) {
        if (iterator == null) return false;
        boolean added = false;
        while (iterator.hasNext()) {
            RoutePrefix prefix = iterator.next();
            builder.addRoute(prefix.address(), prefix.prefix());
            added = true;
        }
        return added;
    }

    @Override
    public void autoDetectInterfaceControl(int fd) {
        service.protect(fd);
    }

    @Override
    public void clearDNSCache() {
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
    }

    @Override
    public ConnectionOwner findConnectionOwner(int protocol, String source, int sourcePort, String destination, int destinationPort) {
        return null;
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        final Iterator<NetworkInterface> empty = new ArrayList<NetworkInterface>().iterator();
        return new NetworkInterfaceIterator() {
            @Override
            public boolean hasNext() {
                return empty.hasNext();
            }

            @Override
            public NetworkInterface next() {
                return empty.next();
            }
        };
    }

    @Override
    public boolean includeAllNetworks() {
        return true;
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public WIFIState readWIFIState() {
        return null;
    }

    @Override
    public void sendNotification(Notification notification) {
        if (notification == null) return;
        service.notifyUser(notification.getTitle(), notification.getBody());
    }

    @Override
    public StringIterator systemCertificates() {
        final Iterator<String> empty = new ArrayList<String>().iterator();
        return new StringIterator() {
            @Override
            public boolean hasNext() {
                return empty.hasNext();
            }

            @Override
            public int len() {
                return 0;
            }

            @Override
            public String next() {
                return empty.next();
            }
        };
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return true;
    }

    @Override
    public boolean useProcFS() {
        return false;
    }

    public static StringIterator strings(List<String> list) {
        final Iterator<String> iterator = list.iterator();
        return new StringIterator() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public int len() {
                return list.size();
            }

            @Override
            public String next() {
                return iterator.next();
            }
        };
    }
}
