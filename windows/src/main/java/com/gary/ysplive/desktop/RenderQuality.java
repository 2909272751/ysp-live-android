package com.gary.ysplive.desktop;

import com.gary.ysplive.core.spi.Log;

/**
 * 根据实际渲染管线自适应画质。
 *
 * <p><b>为什么需要：</b>JavaFX 在没有可用 GPU 的机器上会回退到
 * {@code SWPipeline}（全 CPU 光栅化）。此时某些视觉效果会变得非常昂贵：
 * <ul>
 *   <li>整窗背景图 + {@code background-size: cover} —— 每帧都要把
 *       1600x1000 的 JPEG 重采样到窗口尺寸，CPU 上可能占掉十几毫秒</li>
 *   <li>半透明叠加 —— 每层多一次 alpha blend</li>
 * </ul>
 * 这些在硬件管线上几乎免费，在软件管线上却是主要卡顿来源。
 *
 * <p>所以这里探测管线，软件渲染时自动降级为纯色背景，
 * 让低端机器/远程桌面也能流畅。硬件渲染时保留完整玻璃质感。
 */
final class RenderQuality {

    private static final String TAG = "render";

    /** 是否使用了硬件（D3D）加速。 */
    private static boolean hardware = true;
    private static boolean detected = false;

    private RenderQuality() {
    }

    /** 探测一次（幂等）。必须在 JavaFX 工具线程上调用。 */
    static void detect() {
        if (detected) {
            return;
        }
        detected = true;
        try {
            Object p = com.sun.prism.GraphicsPipeline.getPipeline();
            String name = p == null ? "" : p.getClass().getSimpleName();
            hardware = !name.contains("SW");
            Log.i(TAG, "渲染管线 = " + name + "，硬件加速 = " + hardware);
        } catch (Throwable t) {
            // 探测失败就当硬件处理（保守：不降级观感）
            hardware = true;
            Log.w(TAG, "管线探测失败，按硬件处理: " + t);
        }
    }

    static boolean isHardware() {
        return hardware;
    }

    /**
     * 是否使用背景图。
     * <p>软件渲染下关闭——纯色底 + 卡片层次已足够，而且省掉每帧重采样。
     */
    static boolean useBackdropImage() {
        return hardware;
    }

    /** 是否使用半透明（玻璃）效果。软件渲染下改为接近不透明的实色。 */
    static boolean useTranslucency() {
        return hardware;
    }

    /**
     * 卡片尺寸。
     * <p>软件渲染时把卡片做小一点：可视区域内卡片数减少，单帧光栅化面积下降，
     * 滚动明显更跟手（实测 178x150 -> 160x134 可再快约 10%）。
     * 硬件渲染保持较大的卡片，观感更好。
     */
    static double cardWidth() {
        return hardware ? 178 : 160;
    }

    static double cardHeight() {
        return hardware ? 150 : 134;
    }

    static double logoBoxHeight() {
        return hardware ? 78 : 68;
    }

    /** 给诊断页用的可读描述。 */
    static String describe() {
        return hardware ? "硬件加速 (D3D)" : "软件渲染 (CPU) — 已自动降级视觉特效";
    }
}
