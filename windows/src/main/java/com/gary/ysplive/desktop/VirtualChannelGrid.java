package com.gary.ysplive.desktop;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.spi.Log;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 视口虚拟化频道网格。
 *
 * <h2>为什么需要</h2>
 * 实测（见 NodeScalingProbe）：在软件渲染下，滚动 12 张卡片 64 FPS，
 * 64 张时掉到 24 FPS。原因是 {@link FlowPane} 每帧都要重绘全部子节点，
 * 而屏幕实际只看得到约 15 张。多出来的 49 张是纯浪费。
 *
 * <h2>做法</h2>
 * 这是成熟客户端（Android RecyclerView、WPF VirtualizingStackPanel、
 * PotPlayer 播放列表）的标准方案：
 * <ol>
 *   <li>用一个固定高度的占位面板撑出完整滚动范围</li>
 *   <li>只把「当前视口 ± 预取行」的卡片放进场景图</li>
 *   <li>滚动时回收离开视口的卡片、复用给新进入视口的项</li>
 * </ol>
 * 这样场景图里恒定只有约 20 张卡片，与总频道数无关。
 *
 * <h2>实现取舍</h2>
 * JavaFX 没有现成的虚拟化容器（TableView 有，但我们要卡片网格）。
 * 这里手写一个简化版：因为卡片宽高固定（178x~150），可以直接算出
 * 行高与可见行号，不需要复杂的测量逻辑。
 */
final class VirtualChannelGrid {

    private static final String TAG = "vgrid";

    private static final double HGAP = 12;
    private static final double VGAP = 12;

    /** 实际使用的卡片尺寸（软件渲染时更小，减少单帧光栅化面积）。 */
    private final double cardW = RenderQuality.cardWidth();
    private final double cardH = RenderQuality.cardHeight();

    private final ScrollPane scroll;
    private final FlowPane canvas;

    /** 全部频道（逻辑数据，不建节点）。 */
    private List<ChannelSpec> all = new ArrayList<>();
    /** 当前已实体化的卡片：索引 -> 节点。 */
    private final Map<Integer, Region> realized = new HashMap<>();

    /**
     * 卡片持久缓存：slug -> 节点。
     *
     * <p>为什么需要：虚拟化会随滚动回收卡片。如果回收即丢弃，
     * 每次滚回来都要重建节点、重新触发台标下载（实测表现为台标长时间
     * 停留在文字占位）。这里保留已建好的卡片，回收只是从场景图移除，
     * 节点与已加载的台标仍在内存里，滚回去可立刻复用。
     */
    private final Map<String, Region> pool = new HashMap<>();
    /** 卡片构建器（由 App 提供，含台标加载与点击逻辑）。 */
    private Function<ChannelSpec, Region> factory;
    /** 预取行数：视口上下各多渲染几行，滚动时不至于露白。
     *  取 1 而不是 2——SW 管线下多一行就是多 5 张卡片的重绘成本。 */
    private static final int OVERSCAN_ROWS = 1;

    private int columns = 5;
    private int rowsTotal;
    private int lastFirstRow = -1;
    private int lastLastRow = -1;

    VirtualChannelGrid() {
        canvas = new FlowPane();
        canvas.setHgap(HGAP);
        canvas.setVgap(VGAP);
        canvas.setPadding(new Insets(14, 18, 18, 18));
        canvas.getStyleClass().add("grid");

        scroll = new ScrollPane(canvas);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("grid-scroll");

        // 视口尺寸变化时重算列数
        scroll.viewportBoundsProperty().addListener((o, a, b) -> {
            if (b != null && b.getWidth() > 0) {
                int cols = Math.max(1,
                        (int) ((b.getWidth() - 36 + HGAP) / (cardW + HGAP)));
                if (cols != columns) {
                    columns = cols;
                    rebuild();
                } else {
                    refresh();
                }
            }
        });

        // 滚动时只替换可见区
        scroll.vvalueProperty().addListener((o, a, b) -> refresh());
    }

    ScrollPane node() {
        return scroll;
    }

    /** 设置数据源与卡片工厂，并重建。 */
    void setItems(List<ChannelSpec> items, Function<ChannelSpec, Region> factory) {
        this.factory = factory;
        this.all = items == null ? new ArrayList<>() : new ArrayList<>(items);
        rebuild();
    }

    List<ChannelSpec> items() {
        return all;
    }

    /** 强制重建（筛选切换、卡片外观变化时调用）。
     *  注意只清视口映射，保留 pool 里已建好的卡片与已加载的台标。 */
    void rebuild() {
        realized.clear();
        canvas.getChildren().clear();
        rowsTotal = (int) Math.ceil(all.size() / (double) columns);
        // 用一块透明占位撑出完整滚动高度
        double h = rowsTotal * (cardH + VGAP) + 28;
        canvas.setMinHeight(h);
        canvas.setPrefHeight(h);
        lastFirstRow = -1;
        lastLastRow = -1;
        refresh();
    }

    /** 按当前滚动位置实体化可见行。 */
    private void refresh() {
        if (factory == null || all.isEmpty()) {
            return;
        }
        double viewportH = scroll.getViewportBounds().getHeight();
        if (viewportH <= 0) {
            return;
        }
        double contentH = canvas.getPrefHeight();
        double maxScroll = Math.max(1, contentH - viewportH);
        double topPx = scroll.getVvalue() * maxScroll;

        int firstRow = (int) Math.floor(topPx / (cardH + VGAP)) - OVERSCAN_ROWS;
        int lastRow = (int) Math.ceil((topPx + viewportH) / (cardH + VGAP)) + OVERSCAN_ROWS;
        firstRow = Math.max(0, firstRow);
        lastRow = Math.min(rowsTotal - 1, lastRow);

        if (firstRow == lastFirstRow && lastRow == lastLastRow) {
            return;   // 可见范围没变，不动场景图
        }
        lastFirstRow = firstRow;
        lastLastRow = lastRow;

        int fromIdx = firstRow * columns;
        int toIdx = Math.min(all.size() - 1, (lastRow + 1) * columns - 1);

        // 回收不在范围内的卡片
        realized.entrySet().removeIf(e -> {
            boolean out = e.getKey() < fromIdx || e.getKey() > toIdx;
            if (out) {
                canvas.getChildren().remove(e.getValue());
            }
            return out;
        });

        // 补上缺失的卡片，并保持顺序
        List<Node> ordered = new ArrayList<>(toIdx - fromIdx + 1);
        for (int i = fromIdx; i <= toIdx; i++) {
            Region card = realized.get(i);
            if (card == null) {
                ChannelSpec spec = all.get(i);
                // 优先从持久池里取已建好的卡片（保住已加载的台标）
                card = pool.get(spec.slug);
                if (card == null) {
                    try {
                        card = factory.apply(spec);
                        // 关键性能优化：卡片内容是静态的（台标+文字），滚动时只是平移。
                        // 缓存成位图后滚动直接 blit，不必每帧重绘文字/边框/圆角。
                        card.setCache(true);
                        card.setCacheHint(javafx.scene.CacheHint.SPEED);
                        pool.put(spec.slug, card);
                    } catch (Throwable t) {
                        Log.w(TAG, "构建卡片失败 idx=" + i + ": " + t);
                        continue;
                    }
                }
                realized.put(i, card);
            }
            ordered.add(card);
        }
        // 只在顺序不一致时才整体替换，减少节点增删次数
        if (!canvas.getChildren().equals(ordered)) {
            canvas.getChildren().setAll(ordered);
        }
        Log.i(TAG, "实体化行 " + firstRow + ".." + lastRow
                + " 共 " + canvas.getChildren().size() + " 张卡片");
    }

    /** 某频道当前的卡片节点（可能未实体化）。 */
    Region cardOf(ChannelSpec spec) {
        int idx = all.indexOf(spec);
        return idx < 0 ? null : realized.get(idx);
    }

    /** 遍历当前已实体化的卡片。 */
    Iterable<Region> visibleCards() {
        return new ArrayList<>(realized.values());
    }

    /** 滚动到指定频道（若未实体化则先移动滚动位置）。 */
    void scrollTo(ChannelSpec spec) {
        int idx = all.indexOf(spec);
        if (idx < 0) {
            return;
        }
        int row = idx / columns;
        double contentH = canvas.getPrefHeight();
        double viewportH = scroll.getViewportBounds().getHeight();
        double maxScroll = Math.max(1, contentH - viewportH);
        double target = Math.min(1.0, (row * (cardH + VGAP)) / maxScroll);
        scroll.setVvalue(target);
    }
}
