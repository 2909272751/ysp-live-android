package com.gary.ysplive.desktop;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 用 JavaFX 自己的 {@code snapshot()} 把界面渲染成 PNG。
 *
 * <p><b>为什么不用系统截图：</b>当前开发会话的窗口在屏幕外、
 * 且 {@code PrintWindow} 对 JavaFX 的 D3D/软件表面会有偏移，抓出来是错位的。
 * {@code scene.snapshot()} 由 JavaFX 渲染管线直接输出，像素级准确，
 * 正好用来验证「毛玻璃 + 对比度」的实际观感。
 */
public final class SnapshotCheck {

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "ui.png";
        final CountDownLatch done = new CountDownLatch(1);

        Platform.startup(() -> {
            try {
                App app = new App();
                Stage stage = new Stage();
                app.start(stage);

                Thread worker = new Thread(() -> {
                    try {
                        // 等引擎与布局稳定，也让异步台标有机会加载
                        // 台标走 4 线程池异步加载，给足时间让可见的十几张都下完。
                        // 太短会拍到文字占位（看起来像"台标又坏了"，其实是还没加载完）。
                        Thread.sleep(18000L);
                    } catch (InterruptedException ignored) {
                        // ignore
                    }
                    Platform.runLater(() -> {
                        try {
                            Scene scene = stage.getScene();
                            WritableImage img = scene.snapshot(null);
                            int w = (int) img.getWidth();
                            int h = (int) img.getHeight();
                            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                            PixelReader pr = img.getPixelReader();
                            for (int y = 0; y < h; y++) {
                                for (int x = 0; x < w; x++) {
                                    bi.setRGB(x, y, pr.getArgb(x, y) & 0xFFFFFF);
                                }
                            }
                            ImageIO.write(bi, "png", new File(outPath));
                            System.out.println("snapshot saved: " + outPath + " " + w + "x" + h);
                        } catch (Throwable t) {
                            System.out.println("snapshot failed: " + t);
                            t.printStackTrace();
                        }
                        done.countDown();
                    });
                }, "snap-wait");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable t) {
                System.out.println("start failed: " + t);
                done.countDown();
            }
        });

        done.await(60, TimeUnit.SECONDS);
        System.exit(0);
    }
}
