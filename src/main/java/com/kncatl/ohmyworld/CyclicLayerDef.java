package com.kncatl.ohmyworld;

import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprNode;

public class CyclicLayerDef {
    private final int yStart, yEnd;
    private final int cycleLength;
    private final List<Entry> entries;
    public CyclicLayerDef(int yStart, int yEnd, List<Entry> entries) {
        this.yStart = yStart; this.yEnd = yEnd; this.entries = entries;
        int sum = 0; for (Entry e : entries) sum += e.thickness(); this.cycleLength = sum;
    }
    public int yStart() { return yStart; }
    public int yEnd() { return yEnd; }
    public BlockState getBlock(int worldX, int worldZ, int globalY) {
        if (cycleLength == 0) return Blocks.AIR.defaultBlockState();
        int pos = (globalY - yStart) % cycleLength; int acc = 0;
        for (Entry e : entries) {
            if (pos < acc + e.thickness()) return ExprEvaluator.evalToBlock(e.expression(), worldX, worldZ, pos - acc);
            acc += e.thickness();
        }
        return Blocks.AIR.defaultBlockState();
    }
    public record Entry(int thickness, ExprNode expression) {}
}
