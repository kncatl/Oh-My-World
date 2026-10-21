package com.kncatl.ohmyworld.expr;

/** 词法单元：{@code pos} 是原文下标；{@code line}/{@code column} 从 1 开始，用于报错定位。 */
public record Token(TokenType type, String text, int pos, int line, int column) {}
