package com.gary.ysplive.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.gary.ysplive.core.spi.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简异步图片加载器（只用于台标）。
 * <p>HttpURLConnection + BitmapFactory.decodeStream + {@link LruCache}，不引第三方库。
 * <p>用法：给 {@link ImageView} 打上 tag（URL），加载完成后只有 tag 仍然匹配才回填，
 * 因此 GridView 复用视图时不会串图。
 */
final class LogoLoader {

    private static final String TAG = "logo";

    /** 列表滚动/复用判定用的 tag 键。 */
    static final int TAG_URL = 0x7f0f0001;

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_ENTRIES = 96;

    private static final LruCache<String, Bitmap> CACHE;
    private static final ExecutorService POOL;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 正在下载的 URL，避免同一张图被重复排队。 */
    private static final ConcurrentHashMap<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();

    static {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        final int cacheKb = Math.max(4096, maxKb / 8);
        CACHE = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue,
                                        Bitmap newValue) {
                // 交给 GC 即可，这里不做 recycle，避免仍有视图在用。
            }
        };
        POOL = Executors.newFixedThreadPool(3, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ysp-logo-" + seq.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });
    }

    private LogoLoader() {
    }

    /** 同步取缓存，未命中返回 null。 */
    static Bitmap cached(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        return CACHE.get(url);
    }

    /**
     * 异步加载台标到 {@code view}；命中缓存时同步回填。
     * <p>调用方负责在此之前把占位图显示好；{@code onLoaded} 在图片真正贴上后回调（主线程）。
     */
    static void load(final String url, final ImageView view, final Runnable onLoaded) {
        if (view == null) {
            return;
        }
        if (url == null || url.isEmpty()) {
            view.setTag(TAG_URL, null);
            view.setImageDrawable(null);
            return;
        }
        view.setTag(TAG_URL, url);

        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            if (onLoaded != null) {
                onLoaded.run();
            }
            return;
        }
        view.setImageDrawable(null);

        if (IN_FLIGHT.putIfAbsent(url, Boolean.TRUE) != null) {
            return;
        }

        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                HttpURLConnection conn = null;
                InputStream in = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "YSPLive/9.5 (Android)");
                    conn.setRequestProperty("Accept", "image/*");
                    int status = conn.getResponseCode();
                    if (status >= 200 && status < 300) {
                        in = conn.getInputStream();
                        bitmap = BitmapFactory.decodeStream(in);
                    }
                } catch (Throwable t) {
                    Log.d(TAG, "台标加载失败：" + url + " " + t);
                } finally {
                    closeQuietly(in);
                    if (conn != null) {
                        conn.disconnect();
                    }
                    IN_FLIGHT.remove(url);
                }

                if (bitmap == null) {
                    return;
                }
                CACHE.put(url, bitmap);
                final Bitmap ready = bitmap;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(view.getTag(TAG_URL))) {
                            view.setImageBitmap(ready);
                            if (onLoaded != null) {
                                onLoaded.run();
                            }
                        }
                    }
                });
            }
        });
    }

    /** 视图滚出屏幕时调用，取消 tag 以免回调串图。 */
    static void detach(ImageView view) {
        if (view != null) {
            view.setTag(TAG_URL, null);
        }
    }

    static void clear() {
        CACHE.evictAll();
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
    }
}
