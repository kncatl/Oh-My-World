package com.kncatl.ohmyworld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.kncatl.ohmyworld.expr.BlockResolver;
import com.kncatl.ohmyworld.expr.ExprCompiler;
import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprLexer;
import com.kncatl.ohmyworld.expr.ExprNode;
import com.kncatl.ohmyworld.expr.ExprParser;

public class FormulaParser {

    private static final List<String> KNOWN_VARS = List.of("x", "z", "ly");
    // These are deliberately high safety ceilings, not a formula complexity budget.
    public static final int MAX_INPUT_LENGTH = 1_048_576;
    private static final int MAX_LAYERS = 65_536;

    public record ParseResult(List<Object> layers, List<String> errors) {}

    public static ParseResult parseWithErrors(String input) {
        List<Object> layers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (input == null || input.isBlank()) return invalid("Formula is empty");
        if (input.length() > MAX_INPUT_LENGTH) {
            return invalid("Formula exceeds the maximum input size of " + MAX_INPUT_LENGTH + " characters");
        }

        String cleaned = input.replace("\r", "").replace("\n", "");
        if (cleaned.isBlank()) return invalid("Formula is empty");

        String[] lines = smartSplit(cleaned);
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            if (layers.size() + errors.size() >= MAX_LAYERS) {
                errors.add("Formula contains too many layers; maximum is " + MAX_LAYERS);
                break;
            }
            String line = lines[lineIdx].trim();
            if (line.isEmpty()) continue;

            try {
                int colonIdx = findColon(line);
                if (colonIdx < 0) {
                    errors.add(layerError(lineIdx, "missing range separator ':'", line));
                    continue;
                }

                String rangePart = line.substring(0, colonIdx).trim();
                String exprPart = line.substring(colonIdx + 1).trim();
                if (exprPart.isEmpty()) {
                    errors.add(layerError(lineIdx, "empty expression after ':'", line));
                    continue;
                }

                int eqIdx = rangePart.indexOf('=');
                if (eqIdx < 0) {
                    errors.add(layerError(lineIdx, "missing '=' in range \"" + rangePart + "\"", line));
                    continue;
                }
                String varName = rangePart.substring(0, eqIdx).trim();
                if (!varName.equals("y")) {
                    errors.add(layerError(lineIdx, "only 'y' is supported as layer axis, got \"" + varName + "\"", line));
                    continue;
                }

                String valuePart = rangePart.substring(eqIdx + 1).trim();
                int yStart, yEnd;
                int dotsIdx = valuePart.indexOf("..");
                if (dotsIdx >= 0) {
                    yStart = Integer.parseInt(valuePart.substring(0, dotsIdx).trim());
                    yEnd = Integer.parseInt(valuePart.substring(dotsIdx + 2).trim());
                } else {
                    yStart = yEnd = Integer.parseInt(valuePart);
                }
                if (yStart > yEnd) {
                    errors.add(layerError(lineIdx, "range start " + yStart + " is greater than end " + yEnd, line));
                    continue;
                }

                if (exprPart.contains("*[")) {
                    List<CyclicLayerDef.Entry> entries = parseCyclic(exprPart);
                    if (entries.isEmpty()) {
                        errors.add(layerError(lineIdx, "cyclic layer has no valid entries", line));
                        continue;
                    }
                    List<String> valErrors = new ArrayList<>();
                    for (CyclicLayerDef.Entry e : entries) {
                        ExprEvaluator.ValueType type = validateNode(e.expression(), valErrors, new HashMap<>());
                        if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                            valErrors.add("Cyclic layer expression must return a block, got " + type);
                        }
                    }
                    if (!valErrors.isEmpty()) {
                        for (String ve : valErrors) errors.add(layerError(lineIdx, ve, line));
                        continue;
                    }
                    layers.add(new CyclicLayerDef(yStart, yEnd, entries));
                } else {
                    ExprNode expr = new ExprParser(ExprLexer.tokenize(exprPart)).parse();
                    List<String> valErrors = new ArrayList<>();
                    ExprEvaluator.ValueType type = validateNode(expr, valErrors, new HashMap<>());
                    if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                        valErrors.add("Layer expression must return a block, got " + type);
                    }
                    if (!valErrors.isEmpty()) {
                        for (String ve : valErrors) errors.add(layerError(lineIdx, ve, line));
                        continue;
                    }
                    // 「是否与 y 相关」必须在编译前判定：编译后变量变成槽位下标，
                    // 无法再从名字反推来源。
                    boolean lyDependent = ExprEvaluator.dependsOnLy(expr);
                    layers.add(new FormulaLayerDef(yStart, yEnd, ExprCompiler.compile(expr), !lyDependent));
                }
            } catch (StackOverflowError e) {
                errors.add(layerError(lineIdx, "expression nesting is too deep", line));
            } catch (Exception e) {
                errors.add(layerError(lineIdx, e.getMessage(), line));
            }
        }
        if (layers.isEmpty() && errors.isEmpty()) errors.add("Formula contains no layers");
        return new ParseResult(List.copyOf(layers), List.copyOf(errors));
    }

    public static List<Object> parse(String input) {
        ParseResult result = parseWithErrors(input);
        if (!result.errors().isEmpty()) throw new IllegalArgumentException(String.join("; ", result.errors()));
        return result.layers();
    }

    private static ParseResult invalid(String error) {
        return new ParseResult(List.of(), List.of(error));
    }

    private static String layerError(int lineIdx, String msg, String line) {
        return "Layer " + (lineIdx + 1) + ": " + msg + " in \"" + truncate(line) + "\"";
    }

    private static String truncate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    /** 语义校验：同时推导表达式类型，避免非法结果在运行时静默变成空气。 */
    private static ExprEvaluator.ValueType validateNode(ExprNode node, List<String> errors,
                                                        Map<String, ExprEvaluator.ValueType> variables) {
        switch (node) {
            case ExprNode.NumberNode n -> { return ExprEvaluator.ValueType.NUMBER; }
            case ExprNode.VariableNode v -> {
                if (variables.containsKey(v.name())) return variables.get(v.name());
                if (KNOWN_VARS.contains(v.name())) return ExprEvaluator.ValueType.NUMBER;
                errors.add("Unknown variable: " + v.name() + " (available: x, z, ly)");
                return ExprEvaluator.ValueType.UNKNOWN;
            }
            case ExprNode.BlockNode b -> {
                if (!BlockResolver.exists(b.blockId())) {
                    errors.add("Unknown block: " + b.blockId());
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                return ExprEvaluator.ValueType.BLOCK;
            }
            case ExprNode.BinaryNode bn -> {
                ExprEvaluator.ValueType left = validateNode(bn.left(), errors, variables);
                ExprEvaluator.ValueType right = validateNode(bn.right(), errors, variables);
                return switch (bn.op()) {
                    case ADD, SUB, MUL, DIV, MOD -> {
                        requireNumber(left, "left operand of " + bn.op(), errors);
                        requireNumber(right, "right operand of " + bn.op(), errors);
                        yield ExprEvaluator.ValueType.NUMBER;
                    }
                    case EQ, NE -> {
                        if (left != ExprEvaluator.ValueType.UNKNOWN && right != ExprEvaluator.ValueType.UNKNOWN && left != right) {
                            errors.add("Cannot compare " + left + " with " + right);
                        }
                        yield ExprEvaluator.ValueType.BOOLEAN;
                    }
                    case LT, GT, LE, GE -> {
                        requireNumber(left, "left operand of " + bn.op(), errors);
                        requireNumber(right, "right operand of " + bn.op(), errors);
                        yield ExprEvaluator.ValueType.BOOLEAN;
                    }
                    case AND, OR -> {
                        requireCondition(left, "left operand of " + bn.op(), errors);
                        requireCondition(right, "right operand of " + bn.op(), errors);
                        yield ExprEvaluator.ValueType.BOOLEAN;
                    }
                };
            }
            case ExprNode.UnaryNode u -> {
                ExprEvaluator.ValueType operand = validateNode(u.operand(), errors, variables);
                if (u.op() == ExprNode.UnaryOp.NOT) {
                    requireCondition(operand, "operand of !", errors);
                    return ExprEvaluator.ValueType.BOOLEAN;
                }
                requireNumber(operand, "operand of unary -", errors);
                return ExprEvaluator.ValueType.NUMBER;
            }
            case ExprNode.ConditionalNode c -> {
                ExprEvaluator.ValueType condition = validateNode(c.condition(), errors, variables);
                requireCondition(condition, "ternary condition", errors);
                ExprEvaluator.ValueType thenType = validateNode(c.thenExpr(), errors, variables);
                ExprEvaluator.ValueType elseType = validateNode(c.elseExpr(), errors, variables);
                if (thenType != ExprEvaluator.ValueType.UNKNOWN && elseType != ExprEvaluator.ValueType.UNKNOWN && thenType != elseType) {
                    errors.add("Ternary branches must return the same type, got " + thenType + " and " + elseType);
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                return thenType == ExprEvaluator.ValueType.UNKNOWN ? elseType : thenType;
            }
            case ExprNode.FuncCallNode f -> {
                String msg = ExprEvaluator.validateFunction(f.name(), f.args().size());
                if (msg != null) {
                    errors.add(msg);
                    for (ExprNode a : f.args()) validateNode(a, errors, variables);
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                if (f.name().equals("rand") || f.name().equals("randexcept")) {
                    for (ExprNode a : f.args()) {
                        ExprEvaluator.ValueType type = validateNode(a, errors, variables);
                        if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                            errors.add("Function '" + f.name() + "' expects block arguments, got " + type);
                        }
                    }
                    return ExprEvaluator.ValueType.BLOCK;
                }
                for (ExprNode a : f.args()) {
                    ExprEvaluator.ValueType type = validateNode(a, errors, variables);
                    requireNumber(type, "argument of " + f.name(), errors);
                }
                return ExprEvaluator.ValueType.NUMBER;
            }
            case ExprNode.BlockExprNode be -> {
                Map<String, ExprEvaluator.ValueType> local = new HashMap<>(variables);
                for (ExprNode.LetBinding lb : be.bindings()) {
                    local.put(lb.name(), validateNode(lb.value(), errors, local));
                }
                return validateNode(be.body(), errors, local);
            }
            // 以下是编译后的形态。语义校验发生在编译之前（见本文件的处理顺序），
            // 因此这几个分支实际不会走到；这里只是为了让 switch 穷尽。
            case ExprNode.BuiltinNode b -> { return ExprEvaluator.ValueType.NUMBER; }
            case ExprNode.SlotNode s -> { return ExprEvaluator.ValueType.UNKNOWN; }
            case ExprNode.CompiledFuncCallNode cf -> { return ExprEvaluator.ValueType.UNKNOWN; }
            case ExprNode.CompiledBlockNode cb -> { return ExprEvaluator.ValueType.UNKNOWN; }
        }
    }

    private static void requireNumber(ExprEvaluator.ValueType type, String location, List<String> errors) {
        if (type != ExprEvaluator.ValueType.NUMBER && type != ExprEvaluator.ValueType.UNKNOWN) {
            errors.add("Expected a number for " + location + ", got " + type);
        }
    }

    private static void requireCondition(ExprEvaluator.ValueType type, String location, List<String> errors) {
        if (type != ExprEvaluator.ValueType.NUMBER && type != ExprEvaluator.ValueType.BOOLEAN
                && type != ExprEvaluator.ValueType.UNKNOWN) {
            errors.add("Expected a number or boolean for " + location + ", got " + type);
        }
    }

    private static List<CyclicLayerDef.Entry> parseCyclic(String exprPart) {
        List<CyclicLayerDef.Entry> entries = new ArrayList<>();
        int pos = 0;
        while (pos < exprPart.length()) {
            while (pos < exprPart.length() && (exprPart.charAt(pos) == ' ' || exprPart.charAt(pos) == ',')) {
                pos++;
            }
            if (pos >= exprPart.length()) break;

            int star = exprPart.indexOf('*', pos);
            if (star < 0) throw new IllegalArgumentException("Cyclic layer: expected '*' for thickness at position " + pos);
            int t;
            try {
                t = Integer.parseInt(exprPart.substring(pos, star).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Cyclic layer: invalid thickness \"" + exprPart.substring(pos, star).trim() + "\"");
            }
            if (t <= 0) throw new IllegalArgumentException("Cyclic layer: thickness must be positive, got " + t);
            pos = star + 1;

            if (pos >= exprPart.length() || exprPart.charAt(pos) != '[') {
                throw new IllegalArgumentException("Cyclic layer: expected '[' after '*'");
            }
            pos++;
            int depth = 1, start = pos;
            while (pos < exprPart.length() && depth > 0) {
                char c = exprPart.charAt(pos);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                if (depth > 0) pos++;
            }
            if (depth != 0) throw new IllegalArgumentException("Cyclic layer: unbalanced '[' brackets");
            String inner = exprPart.substring(start, pos).trim();
            pos++;

            ExprNode expr = new ExprParser(ExprLexer.tokenize(inner)).parse();
            // 与整层同理：依赖判定必须在编译前、且在未编译的 AST 上完成
            boolean lyDependent = ExprEvaluator.dependsOnLy(expr);
            entries.add(new CyclicLayerDef.Entry(t, ExprCompiler.compile(expr), lyDependent));
        }
        return entries;
    }

    private static String[] smartSplit(String input) {
        List<String> parts = new ArrayList<>();
        int start = 0, depth = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ';' && depth == 0) {
                parts.add(input.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(input.substring(start));
        return parts.toArray(new String[0]);
    }

    private static int findColon(String line) {
        int balance = 0;
        boolean inTernary = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') balance++;
            else if (c == ')') balance--;
            else if (c == '?' && balance == 0) inTernary = true;
            else if (c == ':' && balance == 0 && !inTernary) return i;
        }
        return -1;
    }
}
