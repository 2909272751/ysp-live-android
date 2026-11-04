import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 决定性实验 2：JavaFX WebView 是否支持 MSE（Media Source Extensions）？
 * <p>如果支持，就可以用 hls.js 做纯 Java 的播放器，不需要任何外部 exe。
 */
public final class WebViewProbe {

    private static final String HLS_JS = "hls.js";

    public static void main(String[] args) throws Exception {
        String hlsJsPath = args.length > 0 ? args[0] : "hls.min.js";
        String url = args.length > 1 ? args[1] : "http://127.0.0.1:18900/cctv1.m3u8";

        File hlsFile = new File(hlsJsPath);
        if (!hlsFile.isFile()) {
            System.out.println("missing hls.js at " + hlsFile.getAbsolutePath());
            System.exit(2);
        }
        String hlsJs = new String(Files.readAllBytes(hlsFile.toPath()), StandardCharsets.UTF_8);

        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>"
                + "body{margin:0;background:#000;color:#0f0;font:12px monospace}"
                + "video{width:100%;height:70vh;background:#111}</style></head><body>"
                + "<div id=\"log\"></div><video id=\"v\" muted playsinline></video>"
                + "<script>" + hlsJs + "</script>"
                + "<script>"
                + "var log=document.getElementById('log');"
                + "function L(m){log.innerHTML+=m+'<br>';console.log(m);}"
                + "window.__result='pending';"
                + "try{"
                + "  var v=document.getElementById('v');"
                + "  L('MSE supported: '+('MediaSource' in window));"
                + "  L('canPlayType(hls): '+v.canPlayType('application/vnd.apple.mpegurl'));"
                + "  L('Hls.isSupported: '+(window.Hls?Hls.isSupported():'noHls'));"
                + "  if(window.Hls){"
                + "    var h=new Hls({debug:false,enableWorker:false});"
                + "    h.on(Hls.Events.MANIFEST_PARSED,function(){L('MANIFEST_PARSED');v.play();});"
                + "    h.on(Hls.Events.ERROR,function(e,d){L('HLS ERROR: '+d.type+'/'+d.details);"
                + "       window.__result='error:'+d.details;});"
                + "    h.loadSource('" + url + "');"
                + "    h.attachMedia(v);"
                + "    v.addEventListener('playing',function(){L('VIDEO PLAYING');"
                + "       window.__result='OK:playing t='+v.currentTime;});"
                + "    v.addEventListener('timeupdate',function(){"
                + "       if(v.currentTime>0.2&&window.__result.indexOf('OK')!==0){"
                + "         window.__result='OK:timeupdate t='+v.currentTime;}});"
                + "  }"
                + "}catch(e){L('EXC '+e);window.__result='exc:'+e;}"
                + "</script></body></html>";

        System.out.println("starting WebView probe...");
        final AtomicReference<String> result = new AtomicReference<>("timeout");
        final CountDownLatch done = new CountDownLatch(1);

        Platform.startup(() -> {
            WebView view = new WebView();
            WebEngine engine = view.getEngine();
            engine.setJavaScriptEnabled(true);

            engine.getLoadWorker().stateProperty().addListener((o, a, b) -> {
                System.out.println("[load] " + b);
                if (b == Worker.State.SUCCEEDED) {
                    System.out.println("page loaded");
                }
            });

            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 900, 600));
            stage.setTitle("webview probe");
            stage.show();

            File f = new File("probe-page.html");
            try {
                Files.write(f.toPath(), html.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.out.println("write html failed: " + e);
            }
            engine.load(f.toURI().toString());

            new Thread(() -> {
                for (int i = 0; i < 60; i++) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    final String[] r = new String[1];
                    final CountDownLatch got = new CountDownLatch(1);
                    Platform.runLater(() -> {
                        try {
                            Object v = engine.executeScript("window.__result");
                            r[0] = v == null ? "null" : v.toString();
                        } catch (Throwable t) {
                            r[0] = "exec-fail:" + t.getMessage();
                        }
                        got.countDown();
                    });
                    try {
                        got.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        // ignore
                    }
                    String s = r[0];
                    if (s != null && s.startsWith("OK")) {
                        result.set(s);
                        done.countDown();
                        return;
                    }
                    if (s != null && (s.startsWith("error") || s.startsWith("exc"))) {
                        result.set(s);
                        done.countDown();
                        return;
                    }
                    if (i % 6 == 0) {
                        System.out.println("  t=" + (i / 2) + "s result=" + s);
                    }
                }
                result.set("FAIL: 30s 内未播放");
                done.countDown();
            }, "watch").start();
        });

        done.await(40, TimeUnit.SECONDS);
        System.out.println();
        System.out.println("VERDICT: " + result.get());
        Platform.exit();
        System.exit(result.get().startsWith("OK") ? 0 : 1);
    }
}
