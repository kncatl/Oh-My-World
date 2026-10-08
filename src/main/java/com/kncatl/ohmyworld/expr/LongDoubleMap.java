package com.kncatl.ohmyworld.expr;

import java.util.Arrays;

/**
 * 线程本地、开放寻址的 long→double 映射（1.3.1 的 cache2d/cache3d 用）。
 *
 * <p>为热路径设计：不装箱、不分配键对象。容量按需翻倍，到达上限后整体清空
 * （值都是纯函数的缓存，清空只损失性能、不影响结果）。
 *
 * <p>本类非线程安全——每个线程各持一份（见 {@link CellCache}）。
 */
final class LongDoubleMap {

    private static final int DEFAULT_CAPACITY = 1 << 10;
    /** 容量上限（装载因子 0.75 时约 1.2 万条）；到顶后清空重来。 */
    private static final int MAX_CAPACITY = 1 << 14;

    private long[] keys = new long[DEFAULT_CAPACITY];
    private double[] values = new double[DEFAULT_CAPACITY];
    private boolean[] used = new boolean[DEFAULT_CAPACITY];
    private int size;

    /** 返回下标，或 -1（不存在）。 */
    int find(long key) {
        int mask = keys.length - 1;
        int index = (int) (mix(key) & mask);
        while (used[index]) {
            if (keys[index] == key) return index;
            index = (index + 1) & mask;
        }
        return -1;
    }

    double valueAt(int index) {
        return values[index];
    }

    void put(long key, double value) {
        if (size * 4 >= keys.length * 3) {
            if (keys.length >= MAX_CAPACITY) clear();
            else resize();
        }
        int mask = keys.length - 1;
        int index = (int) (mix(key) & mask);
        while (used[index]) {
            if (keys[index] == key) {
                values[index] = value;
                return;
            }
            index = (index + 1) & mask;
        }
        used[index] = true;
        keys[index] = key;
        values[index] = value;
        size++;
    }

    private void resize() {
        long[] oldKeys = keys;
        double[] oldValues = values;
        boolean[] oldUsed = used;
        int grown = keys.length << 1;
        keys = new long[grown];
        values = new double[grown];
        used = new boolean[grown];
        size = 0;
        for (int i = 0; i < oldUsed.length; i++) {
            if (oldUsed[i]) put(oldKeys[i], oldValues[i]);
        }
    }

    void clear() {
        Arrays.fill(used, false);
        size = 0;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return z ^ (z >>> 33);
    }
}
