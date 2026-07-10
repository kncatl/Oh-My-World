package com.kncatl.flatpattern;

import java.util.ArrayList;
import java.util.List;

import com.kncatl.flatpattern.expr.ExprLexer;
import com.kncatl.flatpattern.expr.ExprNode;
import com.kncatl.flatpattern.expr.ExprParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class FormulaParser {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static List<Object> parse(String input) {
        List<Object> layers = new ArrayList<>();
        if (input == null || input.isBlank()) return layers;

        String cleaned = input.replace("\r", "").replace("\n", "");
        if (cleaned.isBlank()) return layers;

        for (String line : cleaned.split(";")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            try {
                int colonIdx = findColon(line);
                if (colonIdx < 0) continue;

                String rangePart = line.substring(0, colonIdx).trim();
                String exprPart = line.substring(colonIdx + 1).trim();
                if (exprPart.isEmpty()) continue;

                int eqIdx = rangePart.indexOf('=');
                if (eqIdx < 0) continue;
                String varName = rangePart.substring(0, eqIdx).trim();
                if (!varName.equals("y")) continue;

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
                    if (!entries.isEmpty()) {
                        layers.add(new CyclicLayerDef(yStart, yEnd, entries));
                    }
                } else {
                    ExprNode expr = new ExprParser(ExprLexer.tokenize(exprPart)).parse();
                    layers.add(new FormulaLayerDef(yStart, yEnd, expr));
                }
            } catch (Exception e) {
                LOGGER.warn("FormulaParser: failed to parse line '{}': {}", line, e.getMessage());
            }
        }
        return layers;
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
            if (star < 0) break;
            int t = Integer.parseInt(exprPart.substring(pos, star).trim());
            pos = star + 1;

            if (pos >= exprPart.length() || exprPart.charAt(pos) != '[') break;
            pos++;
            int depth = 1, start = pos;
            while (pos < exprPart.length() && depth > 0) {
                char c = exprPart.charAt(pos);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                if (depth > 0) pos++;
            }
            if (depth != 0) break;
            String inner = exprPart.substring(start, pos).trim();
            pos++;

            ExprNode expr = new ExprParser(ExprLexer.tokenize(inner)).parse();
            entries.add(new CyclicLayerDef.Entry(t, expr));
        }
        return entries;
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
