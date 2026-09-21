package com.kncatl.ohmyworld;

import java.util.List;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprNode;

public class CyclicLayerDef {
    private final int yStart, yEnd;
    private final long cycleLength;
    private final List<Entry> entries;
    /** 所有条目都不引用 ly，因此同一列的结果只取决于 pos（以 cycleLength 为周期）。 */
    private final boolean columnInvariant;

    public CyclicLayerDef(int yStart, int yEnd, List<Entry> entries) {
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.entries = entries;
        long sum = 0;
        for (Entry e : entries) sum = Math.addExact(sum, e.thickness());
        this.cycleLength = sum;
        boolean invariant = true;
        for (Entry e : entries) {
            if (e.lyDependent()) {
                invariant = false;
                break;
            }
        }
        this.columnInvariant = invariant && sum <= Integer.MAX_VALUE;
    }

    public int yStart() { return yStart; }
    public int yEnd() { return yEnd; }

    /**
     * 返回列的周期：结果只取决于 {@code posOf(y)}，取值 0 表示与 y 相关、无法按列缓存。
     */
    public int columnPeriod() {
        return columnInvariant ? (int) cycleLength : 0;
    }

    /** globalY 在该列循环中的位置。 */
    public int posOf(int globalY) {
        if (cycleLength == 0) return 0;
        return (int) Math.floorMod((long) globalY - yStart, cycleLength);
    }

    /** 按 pos 取方块（跳过重复的 floorMod 计算），供按列缓存时使用。 */
    public BlockState getBlockForPos(int worldX, int worldZ, int pos, int layerY) {
        long acc = 0;
        for (Entry e : entries) {
            if (pos < acc + e.thickness()) {
                return ExprEvaluator.evalToBlock(e.expression(), worldX, worldZ, layerY);
            }
            acc += e.thickness();
        }
        return Blocks.AIR.defaultBlockState();
    }

    public BlockState getBlock(int worldX, int worldZ, int globalY) {
        if (cycleLength == 0) return Blocks.AIR.defaultBlockState();
        return getBlockForPos(worldX, worldZ, posOf(globalY), globalY - yStart);
    }

    /**
     * @param expression  已编译的条目表达式
     * @param lyDependent 该条目是否引用 ly，需在编译前判定
     */
    public record Entry(int thickness, ExprNode expression, boolean lyDependent) {}
}
