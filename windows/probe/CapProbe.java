import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 只检查 JavaFX WebView 的 MSE / HLS 能力，不播放。 */
public final class CapProbe {

    public static void main(String[] args) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);

        String html = "<!DOCTYPE html><html><body><pre id='o'></pre><script>"
                + "var o=document.getElementById('o');"
                + "var r={"
                + "  mediaSource: ('MediaSource' in window),"
                + "  managedMediaSource: ('ManagedMediaSource' in window),"
                + "  sourceBuffer: (typeof SourceBuffer!=='undefined'),"
                + "  canPlayHls: document.createElement('video').canPlayType('application/vnd.apple.mpegurl'),"
                + "  canPlayMp4: document.createElement('video').canPlayType('video/mp4'),"
                + "  ua: navigator.userAgent,"
                + "  mseType: (window.MediaSource && MediaSource.isTypeSupported) ? MediaSource.isTypeSupported('video/mp4; codecs=\"avc1.42E01E\"') : 'n/a'"
                + "};"
                + "window.__cap=JSON.stringify(r,null,1);"
                + "o.textContent=window.__cap;"
                + "</script></body></html>";

        Path f = Path.of("cap-probe.html");
        Files.write(f, html.getBytes(StandardCharsets.UTF_8));

        Platform.startup(() -> {
            WebView view = new WebView();
            WebEngine engine = view.getEngine();
            engine.getLoadWorker().stateProperty().addListener((o, a, b) -> {
                if (b == Worker.State.SUCCEEDED) {
                    Object cap = engine.executeScript("window.__cap");
                    System.out.println("===== CAPABILITIES =====");
                    System.out.println(cap);
                    System.out.println("========================");
                    done.countDown();
                }
            });
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 700, 400));
            stage.setTitle("cap probe");
            stage.show();
            engine.load(f.toUri().toString());
        });

        done.await(30, TimeUnit.SECONDS);
        Platform.exit();
        System.exit(0);
    }
}
