package com.github.catvod.net;

import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.DebugEventLimiter;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class OkProxySelector extends ProxySelector {

    private static final long DEBUG_LOG_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);

    private final List<Proxy> proxy;
    private final ProxySelector system;
    private final DebugEventLimiter debugLogLimiter;
    private boolean authSet;

    public OkProxySelector() {
        proxy = new CopyOnWriteArrayList<>();
        system = ProxySelector.getDefault();
        debugLogLimiter = new DebugEventLimiter(64);
        Authenticator.setDefault(new ProxyAuthenticator(this));
    }

    public synchronized void addAll(List<Proxy> items) {
        if (items.isEmpty()) return;
        Authenticator.setDefault(new ProxyAuthenticator(this));
        items.forEach(Proxy::init);
        proxy.addAll(items);
        proxy.sort(null);
        SpiderDebug.log("proxy", "selector add rules=%s total=%s", items.size(), proxy.size());
    }

    public synchronized void remove(String name) {
        int before = proxy.size();
        proxy.removeIf(item -> item.getName().equals(name));
        int removed = before - proxy.size();
        if (removed > 0) SpiderDebug.log("proxy", "selector remove name=%s removed=%s total=%s", name, removed, proxy.size());
    }

    public synchronized void clear() {
        Authenticator.setDefault(null);
        proxy.clear();
        SpiderDebug.log("proxy", "selector clear");
    }

    public List<Proxy> getProxy() {
        return proxy;
    }

    private List<java.net.Proxy> fallback(URI uri) {
        return system != null ? system.select(uri) : List.of(java.net.Proxy.NO_PROXY);
    }

    @Override
    public List<java.net.Proxy> select(URI uri) {
        String host = uri.getHost();
        if (proxy.isEmpty()) return fallback(uri, "no-rule");
        if (host == null) return fallback(uri, "no-host");
        if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) return fallback(uri, "local-target");
        for (Proxy item : proxy) {
            for (String rule : item.getHosts()) {
                if (!matches(host, rule)) continue;
                List<java.net.Proxy> selected = item.getProxies().isEmpty() ? fallback(uri, "empty-proxy") : item.getProxies();
                logSelection(uri, "hit", host, rule, item.getName(), selected.size());
                return selected;
            }
        }
        return fallback(uri, "no-match");
    }

    private List<java.net.Proxy> fallback(URI uri, String reason) {
        List<java.net.Proxy> selected = fallback(uri);
        logFallback(uri, reason, selected.size());
        return selected;
    }

    private boolean matches(String host, String rule) {
        return "*".equals(rule) || Util.containOrMatch(host, rule);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
        logConnectFailed(uri, socketAddress, e);
        if (system != null) system.connectFailed(uri, socketAddress, e);
    }

    private void logSelection(URI uri, String reason, String host, String rule, String name, int count) {
        if (!SpiderDebug.isEnabled()) return;
        DebugEventLimiter.Decision decision = acquire("select", reason, uri, null);
        if (!decision.allowed()) return;
        SpiderDebug.log("proxy", "select hit uri=%s host=%s ruleHash=%s nameHash=%s proxyCount=%s suppressed=%s", OkHttpLogPolicy.redactUri(uri), OkHttpLogPolicy.redactHost(host), OkHttpLogPolicy.redactHost(rule), OkHttpLogPolicy.redactHost(name), count, decision.suppressedCount());
    }

    private void logFallback(URI uri, String reason, int count) {
        if (!SpiderDebug.isEnabled()) return;
        DebugEventLimiter.Decision decision = acquire("fallback", reason, uri, null);
        if (!decision.allowed()) return;
        SpiderDebug.log("proxy", "select fallback reason=%s uri=%s proxyCount=%s suppressed=%s", reason, OkHttpLogPolicy.redactUri(uri), count, decision.suppressedCount());
    }

    private void logConnectFailed(URI uri, SocketAddress address, IOException error) {
        if (!SpiderDebug.isEnabled()) return;
        String addressType = address == null ? "none" : address.getClass().getSimpleName();
        DebugEventLimiter.Decision decision = acquire("failure", addressType, uri, error);
        if (!decision.allowed()) return;
        SpiderDebug.log("proxy", "connectFailed uri=%s addressType=%s error=%s suppressed=%s", OkHttpLogPolicy.redactUri(uri), addressType, OkHttpLogPolicy.errorChain(error), decision.suppressedCount());
    }

    private DebugEventLimiter.Decision acquire(String event, String detail, URI uri, Throwable error) {
        String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost();
        String errorType = error == null ? "" : error.getClass().getName();
        String key = event + '|' + detail + '|' + scheme + '|' + host + '|' + errorType;
        long nowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        return debugLogLimiter.acquire(key, nowMs, DEBUG_LOG_INTERVAL_MS);
    }
}
