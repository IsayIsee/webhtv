package com.github.catvod.net;

import com.github.catvod.crawler.SpiderDebug;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyHealthChecker {

    private static final long TTL_MS = 30_000;       // 健康状态缓存 30 秒
    private static final long UNHEALTHY_TTL_MS = 10_000; // 不健康状态缓存 10 秒
    private static final int TIMEOUT_MS = 3_000;     // TCP 探测超时 3 秒

    private record CacheEntry(boolean healthy, long expiresAt) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static final ProxyHealthChecker INSTANCE = new ProxyHealthChecker();

    public static ProxyHealthChecker get() {
        return INSTANCE;
    }

    private ProxyHealthChecker() {}

    /**
     * 检查代理是否可用，带缓存。
     * @param host 代理主机
     * @param port 代理端口
     * @return true=可用，false=不可用（临时直连）
     */
    public boolean isHealthy(String host, int port) {
        String key = host + ":" + port;
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && now < entry.expiresAt()) {
            return entry.healthy();
        }
        boolean healthy = probe(host, port);
        long ttl = healthy ? TTL_MS : UNHEALTHY_TTL_MS;
        cache.put(key, new CacheEntry(healthy, now + ttl));
        SpiderDebug.log("proxy", "health check host=%s port=%d healthy=%b", host, port, healthy);
        return healthy;
    }

    /** 强制清除缓存，下次 select() 时重新探测 */
    public void invalidate() {
        cache.clear();
    }

    private boolean probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            SpiderDebug.log("proxy", "health probe failed host=%s port=%d error=%s", host, port, e.getMessage());
            return false;
        }
    }
}