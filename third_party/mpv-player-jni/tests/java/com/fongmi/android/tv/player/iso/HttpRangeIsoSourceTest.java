package com.fongmi.android.tv.player.iso;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import static org.junit.Assert.*;

public class HttpRangeIsoSourceTest {
    private final MockWebServer server = new MockWebServer();
    private final ExecutorService readers = Executors.newFixedThreadPool(2);
    private HttpRangeIsoSource source;

    @After
    public void tearDown() {
        if (source != null) source.close();
        readers.shutdownNow();
        server.close();
    }

    @Test
    public void closeCancelsTwoConcurrentResponseBodiesAfterHeaders() throws Exception {
        CountDownLatch headers = new CountDownLatch(2);
        OkHttpClient client = new OkHttpClient.Builder().eventListener(new EventListener() {
            @Override public void responseHeadersEnd(Call call, Response response) {
                if (!call.request().header("Range").equals("bytes=0-0")) headers.countDown();
            }
        }).build();
        server.enqueue(range("bytes 0-0/16", "a", "one").build());
        // Identical ranges deliberately exercise two active Calls, not the page cache's dedup.
        server.enqueue(range("bytes 1-1/16", "b", "one").bodyDelay(30, TimeUnit.SECONDS).build());
        server.enqueue(range("bytes 1-1/16", "b", "one").bodyDelay(30, TimeUnit.SECONDS).build());
        server.start();
        source = new HttpRangeIsoSource(server.url("/disc.iso").toString(), Map.of(), client);
        assertEquals(16, source.length());
        Future<?> first = readers.submit(() -> assertThrows(IOException.class, () -> source.readAt(1, new byte[1], 0, 1)));
        Future<?> second = readers.submit(() -> assertThrows(IOException.class, () -> source.readAt(1, new byte[1], 0, 1)));
        assertTrue(headers.await(3, TimeUnit.SECONDS));
        source.close();
        first.get(3, TimeUnit.SECONDS);
        second.get(3, TimeUnit.SECONDS);
    }

    @Test
    public void validatorChangeIsRejectedAndCallerRangeHeadersAreOverridden() throws Exception {
        server.enqueue(range("bytes 0-0/16", "a", "one").build());
        server.enqueue(range("bytes 2-2/16", "c", "two").build());
        server.start();
        source = new HttpRangeIsoSource(server.url("/disc.iso").toString(),
                Map.of("Range", "bytes=500-600", "Accept-Encoding", "gzip"), new OkHttpClient());
        assertEquals(16, source.length());
        assertEquals(IsoSourceException.Reason.SOURCE_CHANGED,
                assertThrows(IsoSourceException.class, () -> source.readAt(2, new byte[1], 0, 1)).reason());
        assertEquals("bytes=0-0", server.takeRequest(3, TimeUnit.SECONDS).getHeaders().get("Range"));
        assertEquals("identity", server.takeRequest(3, TimeUnit.SECONDS).getHeaders().get("Accept-Encoding"));
    }

    @Test
    public void ignoredRangeStillFailsWithoutReadingWholeIso() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("not a range").build());
        server.start();
        source = new HttpRangeIsoSource(server.url("/disc.iso").toString(), Map.of(), new OkHttpClient());
        assertEquals(IsoSourceException.Reason.RANGE_UNSUPPORTED,
                assertThrows(IsoSourceException.class, () -> source.length()).reason());
    }

    private static MockResponse.Builder range(String range, String body, String validator) {
        return new MockResponse.Builder().code(206).addHeader("Content-Range", range)
                .addHeader("ETag", validator).body(body);
    }
}
