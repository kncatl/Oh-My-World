package com.kncatl.flatpattern.expr;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.flatpattern.expr.ExprNode.BinaryOp;

public class ExprEvaluator {

    public static Object eval(ExprNode node, int x, int z, int ly) {
        return switch (node) {
            case ExprNode.NumberNode n -> n.value();
            case ExprNode.VariableNode v -> varValue(v.name(), x, z, ly);
            case ExprNode.BlockNode b -> BlockResolver.resolve(b.blockId());
            case ExprNode.BinaryNode b -> evalBinary(b, x, z, ly);
            case ExprNode.UnaryNode u -> evalUnary(u, x, z, ly);
            case ExprNode.ConditionalNode c -> evalConditional(c, x, z, ly);
            case ExprNode.FuncCallNode f -> evalFunc(f, x, z, ly);
        };
    }

    private static double varValue(String name, int x, int z, int ly) {
        return switch (name) { case "x" -> x; case "z" -> z; case "ly" -> ly; default -> 0; };
    }

    private static Object evalBinary(ExprNode.BinaryNode b, int x, int z, int ly) {
        Object left = eval(b.left(), x, z, ly); Object right = eval(b.right(), x, z, ly);
        BinaryOp op = b.op();
        if (op == BinaryOp.AND || op == BinaryOp.OR) { return op == BinaryOp.AND ? (toBool(left) && toBool(right)) : (toBool(left) || toBool(right)); }
        if (op == BinaryOp.EQ || op == BinaryOp.NE) return compEqNe(op, left, right);
        if (op == BinaryOp.LT || op == BinaryOp.GT || op == BinaryOp.LE || op == BinaryOp.GE) {
            double la = toDouble(left); double ra = toDouble(right);
            return switch (op) { case LT -> la < ra; case GT -> la > ra; case LE -> la <= ra; case GE -> la >= ra; default -> false; };
        }
        double la = toDouble(left); double ra = toDouble(right);
        return switch (op) {
            case ADD -> la + ra; case SUB -> la - ra; case MUL -> la * ra;
            case DIV -> (ra == 0 ? 0 : la / ra); case MOD -> (ra == 0 ? 0 : la % ra); default -> 0d;
        };
    }

    private static boolean compEqNe(BinaryOp op, Object left, Object right) {
        boolean eq;
        if (left instanceof String ls && right instanceof String rs) eq = ls.equals(rs);
        else if (left instanceof Boolean lb && right instanceof Boolean rb) eq = lb == rb;
        else eq = Math.abs(toDouble(left) - toDouble(right)) < 1e-9;
        return op == BinaryOp.EQ ? eq : !eq;
    }

    private static Object evalUnary(ExprNode.UnaryNode u, int x, int z, int ly) {
        Object val = eval(u.operand(), x, z, ly);
        return switch (u.op()) { case NOT -> !toBool(val); case NEG -> -toDouble(val); };
    }

    private static Object evalConditional(ExprNode.ConditionalNode c, int x, int z, int ly) {
        return toBool(eval(c.condition(), x, z, ly)) ? eval(c.thenExpr(), x, z, ly) : eval(c.elseExpr(), x, z, ly);
    }

    private static Object evalFunc(ExprNode.FuncCallNode f, int x, int z, int ly) {
        List<Object> raw = new ArrayList<>();
        for (ExprNode arg : f.args()) raw.add(eval(arg, x, z, ly));
        return switch (f.name()) {
            case "floordiv" -> { int a = toInt(raw.get(0)); int b = toInt(raw.get(1)); yield (double)(b == 0 ? 0 : Math.floorDiv(a, b)); }
            case "floormod" -> { int a = toInt(raw.get(0)); int b = toInt(raw.get(1)); yield (double)(b == 0 ? 0 : Math.floorMod(a, b)); }
            case "abs"   -> Math.abs(toDouble(raw.get(0))); case "max" -> Math.max(toDouble(raw.get(0)), toDouble(raw.get(1))); case "min" -> Math.min(toDouble(raw.get(0)), toDouble(raw.get(1)));
            case "floor" -> Math.floor(toDouble(raw.get(0))); case "ceil" -> Math.ceil(toDouble(raw.get(0))); case "round" -> (double)Math.round(toDouble(raw.get(0)));
            case "sign"  -> (double)Math.signum(toDouble(raw.get(0))); case "sqrt" -> Math.sqrt(toDouble(raw.get(0)));
            case "pow"   -> Math.pow(toDouble(raw.get(0)), toDouble(raw.get(1))); case "exp" -> Math.exp(toDouble(raw.get(0)));
            case "log"   -> Math.log(toDouble(raw.get(0))); case "log10" -> Math.log10(toDouble(raw.get(0)));
            case "sin"   -> Math.sin(toDouble(raw.get(0))); case "cos" -> Math.cos(toDouble(raw.get(0))); case "tan" -> Math.tan(toDouble(raw.get(0)));
            case "asin"  -> Math.asin(toDouble(raw.get(0))); case "acos" -> Math.acos(toDouble(raw.get(0))); case "atan" -> Math.atan(toDouble(raw.get(0)));
            case "todeg" -> Math.toDegrees(toDouble(raw.get(0))); case "torad" -> Math.toRadians(toDouble(raw.get(0)));
            case "rand" -> evalRand(raw, x, z, ly); case "randexcept" -> evalRandExcept(raw, x, z, ly);
            case "rng" -> evalRng(raw, x, z); case "smooth" -> evalSmooth(raw, x, z);
            default -> toDouble(raw.get(0));
        };
    }

    private static List<BlockState> ALL_BLOCKS;
    private static List<BlockState> getAllBlocks() {
        if (ALL_BLOCKS == null) { List<BlockState> list = new ArrayList<>(); for (Block block : BuiltInRegistries.BLOCK) if (block != Blocks.AIR) list.add(block.defaultBlockState()); ALL_BLOCKS = list; }
        return ALL_BLOCKS;
    }

    private static int pickIndex(int x, int z, int y, int bound) {
        if (bound <= 0) return 0;
        long mix = ((long)x * 374761393L + (long)z * 668265263L + y) ^ 0x5DEECE66DL;
        mix = (mix ^ (mix >>> 33)) * 0xFF51AFD7ED558CCDL; mix = (mix ^ (mix >>> 33)) * 0xC4CEB9FE1A85EC53L; mix = mix ^ (mix >>> 33);
        return (int)Math.floorMod(mix, (long)bound);
    }

    private static Object evalRand(List<Object> raw, int x, int z, int ly) {
        if (raw.isEmpty()) return getAllBlocks().get(pickIndex(x, z, ly, getAllBlocks().size()));
        List<BlockState> states = new ArrayList<>(); for (Object arg : raw) if (arg instanceof BlockState bs) states.add(bs);
        if (states.isEmpty()) return BlockResolver.resolve("minecraft:air");
        return states.get(pickIndex(x, z, ly, states.size()));
    }

    private static Object evalRandExcept(List<Object> raw, int x, int z, int ly) {
        List<BlockState> all = new ArrayList<>(getAllBlocks()); for (Object arg : raw) if (arg instanceof BlockState bs) all.remove(bs);
        if (all.isEmpty()) return BlockResolver.resolve("minecraft:air");
        return all.get(pickIndex(x, z, ly, all.size()));
    }

    private static double hashDouble(int x, int y) {
        long mix = ((long)x * 374761393L + (long)y * 668265263L) ^ 0x5DEECE66DL;
        mix = (mix ^ (mix >>> 33)) * 0xFF51AFD7ED558CCDL; mix = (mix ^ (mix >>> 33)) * 0xC4CEB9FE1A85EC53L; mix = mix ^ (mix >>> 33);
        return (double)(mix & 0x7FFFFFFFFFFFFFFFL) / (double)0x7FFFFFFFFFFFFFFFL;
    }

    private static Object evalRng(List<Object> raw, int x, int z) {
        double val = hashDouble(x, z); if (raw.size() >= 2) { double a = toDouble(raw.get(0)); double b = toDouble(raw.get(1)); return a + val * (b - a); }
        return val;
    }

    private static Object evalSmooth(List<Object> raw, int x, int z) {
        if (raw.size() >= 2) return smoothNoise(x * toDouble(raw.get(0)), z * toDouble(raw.get(1)));
        return smoothNoise(x, z);
    }

    private static double smoothNoise(double x, double z) {
        int ix = (int)Math.floor(x); int iz = (int)Math.floor(z); double fx = x - ix; double fz = z - iz;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        double v00 = hashDouble(ix, iz); double v10 = hashDouble(ix + 1, iz); double v01 = hashDouble(ix, iz + 1); double v11 = hashDouble(ix + 1, iz + 1);
        return v00 + (v10 - v00) * fx + (v01 - v00) * fz + (v00 - v10 - v01 + v11) * fx * fz;
    }

    public static BlockState evalToBlock(ExprNode node, int x, int z, int ly) {
        Object result = eval(node, x, z, ly); if (result instanceof BlockState bs) return bs;
        return BlockResolver.resolve("minecraft:air");
    }

    private static double toDouble(Object o) { if (o instanceof Number n) return n.doubleValue(); if (o instanceof Boolean b) return b ? 1d : 0d; return 0d; }
    private static int toInt(Object o) { if (o instanceof Number n) return n.intValue(); if (o instanceof Boolean b) return b ? 1 : 0; return 0; }
    private static boolean toBool(Object o) { if (o instanceof Boolean b) return b; if (o instanceof Number n) return n.doubleValue() != 0; return false; }
}
