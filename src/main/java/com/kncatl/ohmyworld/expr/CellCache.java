package com.kncatl.ohmyworld.expr;

/**
 * 与 {@link LongDoubleMap} 配套的线程本地缓存单元：记录写入时的世界种子，
 * 种子变化后首次访问即清空（缓存值都依赖世界种子）。
 */
final class CellCache {

    private final LongDoubleMap map = new LongDoubleMap();
    private long seed = Long.MIN_VALUE;

    /** 取当前线程的映射（必要时按种子清空）。 */
    LongDoubleMap map() {
        long current = Noise.worldSeed;
        if (current != seed) {
            map.clear();
            seed = current;
        }
        return map;
    }
}
