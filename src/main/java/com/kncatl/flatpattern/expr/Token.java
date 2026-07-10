package com.kncatl.flatpattern.expr;

public record Token(TokenType type, String text, int pos) {}
