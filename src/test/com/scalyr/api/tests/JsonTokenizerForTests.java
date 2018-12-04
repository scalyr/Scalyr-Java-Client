/*
 * Scalyr client library
 * Copyright 2012 Scalyr, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scalyr.api.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.scalyr.api.internal.ScalyrUtil;

/**
 * Simple tokenizer (lexer) for tokenizing JSON source. Placed in the test package
 * because we only need it in unit tests.
 *
 * This code is cribbed from another project and may not be especially robust or
 * spec-compliant; it is only intended for use in tests.
 */
public class JsonTokenizerForTests {
  private static String standardMantissaPattern = "[0-9]+(\\.[0-9]+)?";
  private static String altMantissaPattern = "\\.[0-9]+";
  private static String mantissaPattern = "((" + standardMantissaPattern + ")|(" + altMantissaPattern + "))";

  private static Pattern numberPattern = Pattern.compile("^[+\\-]?" + mantissaPattern + "([eE][+\\-][0-9]+)?");
  private static Pattern identifierPattern = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*");

  public static enum TokenType {
    operator,
    stringLit,
    numLit,
    identifier
  }

  public static class Token {
    /**
     * Character position in the input text where this token begins.
     */
    public final int pos;

    /**
     * Number of input text characters corresponding to this token.
     */
    public final int len;

    public final TokenType type;

    /**
     * Logical "value" of this token.  Interpretation is type-specific.
     */
    public final String value;

    public Token(String value, int pos, int len, TokenType type) {
      this.value = value;
      this.pos = pos;
      this.len = len;
      this.type = type;
    }

    /**
     * Return true if this is an operator token with the given value.
     */
    public boolean isOp(String s) {
      return (type == TokenType.operator && value.equals(s));
    }

    /**
     * Return true if this is an identifier token with the given value.
     */
    public boolean isId(String s) {
      return (type == TokenType.identifier && value.equals(s));
    }

    @Override public boolean equals(Object obj) {
      if (!(obj instanceof Token))
        return false;

      Token t = (Token)obj;
      return t.pos == pos && t.len == len && t.type == type && t.value.equals(value);
    }

    @Override public String toString() {
      return "Token <" + type + ", " + pos + ":" + len + ", [" + value + "]>";
    }
  }

  /**
   * The source code to tokenize.
   */
  private final String input;

  /**
   * Index, in input, of the next unconsumed character.
   */
  private int pos;

  public final List<Token> tokens = new ArrayList<Token>();

  /**
   * Contains, for each line in the input text, the character position where that line begins.
   */
  public final List<Integer> lineStarts = new ArrayList<Integer>();

  public JsonTokenizerForTests(String input) {
    this.input = input;
    lineStarts.add(0);
  }

  public void tokenize() {
    pos = 0;
    while (pos < input.length()) {
      char c = input.charAt(pos);
      if (Character.isDigit(c)) {
        tokens.add(tokenizeNumber());
      } else if (c == '.' && tokenIsNumber()) {
        tokens.add(tokenizeNumber());
      } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$') {
        tokens.add(tokenizeIdentifier());
      } else if (Character.isWhitespace(c)) {
        pos++;
        if (c == '\n')
          lineStarts.add(pos);
        else if (c == '\r' && !nextCharIs('\n'))
          lineStarts.add(pos);
      } else if (c == '\'' || c == '"') {
        tokens.add(tokenizeStringLiteral());
      } else {
        switch (c) {
        case '!':
        case '@':
        case '#':
        case '$':
        case '%':
        case '^':
        case '&':
        case '*':
        case '(':
        case ')':
        case '=':
        case '+':
        case '-':
        case '`':
        case '~':
        case '[':
        case ']':
        case '{':
        case '}':
        case '|':
        case ';':
        case ':':
        case '<':
        case '>':
        case ',':
        case '.':
        case '/':
        case '?':
          tokens.add(tokenizeOperator());
          break;

        default:
          error("\"" + c + "\" is not used in programs");
          break;
        }
      }
    }
  }

  /**
   * Return true if we are not at the end of the input, and the next character is the specified
   * character.
   */
  private boolean nextCharIs(char c) {
    return pos < input.length() && input.charAt(pos) == c;
  }

  private boolean tokenIsNumber() {
    String upcoming = input.substring(pos, Math.min(pos+100, input.length()));
    return numberPattern.matcher(upcoming).find();
  }

  private Token tokenizeNumber() {
    String upcoming = input.substring(pos, Math.min(pos+100, input.length()));
    Matcher matcher = numberPattern.matcher(upcoming);
    if (matcher.find()) {
      String value = matcher.group(0);
      Token token = new Token(matcher.group(0), pos, value.length(), TokenType.numLit);
      pos += value.length();
      return token;
    } else {
      error("Unparsable number"); // this should never happen -- the regexp should match in any situation where we are called
      return null;
    }
  }

  private Token tokenizeIdentifier() {
    String upcoming = input.substring(pos, Math.min(pos+100, input.length()));
    Matcher matcher = identifierPattern.matcher(upcoming);
    if (matcher.find()) {
      String value = matcher.group(0);
      Token token = new Token(matcher.group(0), pos, value.length(), TokenType.identifier);
      pos += value.length();
      return token;
    } else {
      error("Unparsable identifier"); // this should never happen -- the regexp should match in any situation where we are called
      return null;
    }
  }

  private Token tokenizeStringLiteral() {
    int startPos = pos;
    char delim = input.charAt(pos++);
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (pos >= input.length())
        error("Start quote with no matching end quote", startPos, 1);

      char c = input.charAt(pos++);
      if (c == delim)
        break;

      if (c == '\\') {
        if (pos >= input.length())
          error("Start quote with no matching end quote", startPos, 1);
        c = input.charAt(pos++);
        switch (c) {
          case '\\': sb.append('\\'); break;
          case '\'': sb.append('\''); break;
          case '\"': sb.append('\"'); break;
          case 'r': sb.append('\r'); break;
          case 'n': sb.append('\n'); break;
          case 't': sb.append('\t'); break;
          case 'b': sb.append('\b'); break;
        }
      } else
        sb.append(c);
    }

    return new Token(sb.toString(), startPos, pos - startPos, TokenType.stringLit);
  }

  private static String[] multicharOperators = new String[]{
    "<=", ">=", "!=", "==", "~=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "++", "--"
  };

  private Token tokenizeOperator() {
    for (String opString : multicharOperators)
      if (peekFor(opString)) {
        Token token = new Token(opString, pos, opString.length(), TokenType.operator);
        pos += token.len;
        return token;
      }

    pos++;
    return new Token(input.substring(pos-1, pos), pos-1, 1, TokenType.operator);
  }

  private boolean peekFor(String s) {
    if (pos + s.length() > input.length())
      return false;

    for (int i=0; i<s.length(); i++)
      if (s.charAt(i) != input.charAt(pos + i))
        return false;

    return true;
  }

  private void error(String message) {
    error(message, pos, 1);
  }

  public void error(String message, Token token) {
    error(message, token.pos, token.len);
  }

  /**
   * Report an error in the input text.
   *
   * @param message  A message describing the error.
   * @param errorPos Character position where the input substring associated with this error begins.
   * @param errorLen Length of the input substring associated with the error.
   */
  public void error(String message, int errorPos, int errorLen) {
    int lineNum   = getLineNum(errorPos);
    int lineStart = getLineStart(lineNum);
    int lineEnd   = getLineEnd  (lineNum);
    String lineText = input.substring(lineStart, lineEnd);
    String errorInput = input.substring(errorPos, errorPos+errorLen);
    throw new ParseException(message, errorPos, errorLen, lineNum, errorPos - lineStart, lineText);
  }

  /**
   * Return the (1-based) index of the line containing the given character position in the input.
   */
  public int getLineNum(int charPos) {
    // Binary search.  Invariant: the desired line number is in the range [min, max).  We
    // work in 0-based indexes, and then add 1 when returning.
    int min = 0, max = lineStarts.size();
    while (max > min+1) {
      int mid = (min + max) / 2;
      if (lineStarts.get(mid) <= charPos)
        min = mid;
      else
        max = mid;
    }

    ScalyrUtil.Assert(max == min+1, "assertion failed");
    return min + 1;
  }

  /**
   * Return the character position where the given (1-based) line begins.
   */
  public int getLineStart(int lineNum) {
    return lineStarts.get(lineNum - 1);
  }

  /**
   * Return the character position where the given (1-based) line ends.  The
   * position returned will be prior to any linebreak character(s) at the end of
   * the line.
   */
  public int getLineEnd(int lineNum) {
    if (lineNum >= lineStarts.size())
      return input.length();

    int lineEndPos = lineStarts.get(lineNum);
    while (lineEndPos > 0 && isCrOrLf(input.charAt(lineEndPos-1)))
      lineEndPos--;
    return lineEndPos;
  }

  private boolean isCrOrLf(char c) {
    return c == '\n' || c == '\r';
  }


  public static class ParseException extends RuntimeException {
    public final int errorPos;
    public final int errorLen;
    public final int lineNum;
    public final int posInLine;
    public final String offendingLine;

    public ParseException(String message, int errorPos, int errorLen, int lineNum, int posInLine, String offendingLine) {
      super(message);

      this.errorPos = errorPos;
      this.errorLen = errorLen;
      this.lineNum = lineNum;
      this.posInLine = posInLine;
      this.offendingLine = offendingLine;
    }

    @Override public String toString() {
      return "Error at line " + lineNum + ", character " + posInLine + ": " + getMessage()
          + "\n" + offendingLine.substring(0, posInLine) + "[[["
          + offendingLine.substring(posInLine, posInLine + errorLen) + "]]]" + offendingLine.substring(posInLine + errorLen);
    }
  }
}
