package com.kncatl.ohmyworld.expr;

import java.util.HashMap;
import java.util.Map;

/**
 * rivernet 的抖动网格节点（1.3.1）。下游与流量按需计算并就地缓存：
 * 下游一旦确定不再变化；流量按"深度预算"分别缓存（flow(d) 递归依赖 flow(d-1)）。
 */
final class RiverNode {

    /** 无下游（终点）：海节点或局部最低点。 */
    static final int NO_DOWN = Integer.MIN_VALUE;
    /** 流量最大深度预算（见指南的"深度 3 封顶"）。 */
    static final int MAX_DEPTH = 3;

    final int i;
    final int j;
    final double x;
    final double z;
    final double elevation;

    int downI = NO_DOWN;
    int downJ = NO_DOWN;
    boolean downReady;

    private final double[] flowByDepth = new double[MAX_DEPTH + 1];
    private final boolean[] flowReady = new boolean[MAX_DEPTH + 1];

    RiverNode(int i, int j, double x, double z, double elevation) {
        this.i = i;
        this.j = j;
        this.x = x;
        this.z = z;
        this.elevation = elevation;
    }

    boolean hasFlow(int depth) {
        return depth >= 0 && depth <= MAX_DEPTH && flowReady[depth];
    }

    double flow(int depth) {
        return flowByDepth[depth];
    }

    void setFlow(int depth, double value) {
        flowByDepth[depth] = value;
        flowReady[depth] = true;
    }
}

/** rivernet 的线程本地节点缓存：按世界种子失效、有容量上限。 */
final class RiverCache {

    private static final int MAX_NODES = 1 << 13;

    private final Map<Long, RiverNode> nodes = new HashMap<>();
    private long seed = Long.MIN_VALUE;

    /** 取当前线程的节点表（必要时按种子清空）。 */
    Map<Long, RiverNode> nodes() {
        long current = Noise.worldSeed;
        if (current != seed) {
            nodes.clear();
            seed = current;
        }
        return nodes;
    }

    static long key(int i, int j) {
        return ((long) i << 32) | (j & 0xFFFFFFFFL);
    }

    void maybeTrim() {
        if (nodes.size() > MAX_NODES) nodes.clear();
    }
}
