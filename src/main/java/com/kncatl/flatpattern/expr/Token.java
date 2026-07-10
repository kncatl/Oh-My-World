package com.kncatl.flatpattern.expr;

public record Token(TokenType type, String text, int pos) {
    @Override
    public String toString() {
        return type + (text.isEmpty() ? "" : "('" + text + "')");
    }
}
