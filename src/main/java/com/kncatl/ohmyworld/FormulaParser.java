package com.kncatl.ohmyworld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.kncatl.ohmyworld.expr.ExprLexer;
import com.kncatl.ohmyworld.expr.ExprNode;
import com.kncatl.ohmyworld.expr.ExprParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class FormulaParser {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record ParseResult(List<Object> layers, List<String> errors) {}

    public static ParseResult parseWithErrors(String input) {
        List<Object> layers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (input == null || input.isBlank()) return new ParseResult(layers, errors);

        String cleaned = input.replace("\r", "").replace("\n", "");
        if (cleaned.isBlank()) return new ParseResult(layers, errors);

        String[] lines = smartSplit(cleaned);
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx].trim();
            if (line.isEmpty()) continue;

            try {
                int colonIdx = findColon(line);
                if (colonIdx < 0) {
                    errors.add("Layer " + (lineIdx + 1) + ": missing range separator ':' in \"" + truncate(line) + "\"");
                    continue;
                }

                String rangePart = line.substring(0, colonIdx).trim();
                String exprPart = line.substring(colonIdx + 1).trim();
                if (exprPart.isEmpty()) {
                    errors.add("Layer " + (lineIdx + 1) + ": empty expression after ':'");
                    continue;
                }

                int eqIdx = rangePart.indexOf('=');
                if (eqIdx < 0) {
                    errors.add("Layer " + (lineIdx + 1) + ": missing '=' in range \"" + rangePart + "\"");
                    continue;
                }
                String varName = rangePart.substring(0, eqIdx).trim();
                if (!varName.equals("y")) {
                    errors.add("Layer " + (lineIdx + 1) + ": only 'y' is supported as layer axis, got \"" + varName + "\"");
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

                if (exprPart.contains("*[")) {
                    List<CyclicLayerDef.Entry> entries = parseCyclic(exprPart);
                    if (entries.isEmpty()) {
                        errors.add("Layer " + (lineIdx + 1) + ": cyclic layer has no valid entries");
                        continue;
                    }
                    layers.add(new CyclicLayerDef(yStart, yEnd, entries));
                } else {
                    ExprNode expr = new ExprParser(ExprLexer.tokenize(exprPart)).parse();
                    layers.add(new FormulaLayerDef(yStart, yEnd, expr));
                }
            } catch (Exception e) {
                errors.add("Layer " + (lineIdx + 1) + ": " + e.getMessage() + " in \"" + truncate(line) + "\"");
            }
        }
        return new ParseResult(layers, errors);
    }

    public static List<Object> parse(String input) {
        return parseWithErrors(input).layers();
    }

    private static String truncate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
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
            entries.add(new CyclicLayerDef.Entry(t, expr));
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
