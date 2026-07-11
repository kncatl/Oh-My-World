package com.kncatl.flatpattern.expr;

public enum TokenType {
    NUMBER, IDENTIFIER, BLOCK_ID,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NE, LT, GT, LE, GE,
    AND, OR, NOT,
    QUESTION, COLON, COMMA, LBRACE, RBRACE, SEMI, ASSIGN,
    LPAREN, RPAREN,
    EOF
}
