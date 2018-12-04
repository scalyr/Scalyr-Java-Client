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

import java.util.List;

import com.scalyr.api.json.JSONArray;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.tests.JsonTokenizerForTests.Token;
import com.scalyr.api.tests.JsonTokenizerForTests.TokenType;

/**
 * Quick-and-dirty JSON parser. Only intended for use in tests.
 */
public class JsonParserForTests {
  /**
   * The text to be parsed.
   */
  private final String input;

  /**
   * A tokenizer for the input string.
   */
  private final JsonTokenizerForTests tokenizer;

  /**
   * Tokenization of the input string.
   */
  protected List<Token> tokens;

  /**
   * Index, in the tokens list, of the next unconsumed token.
   */
  protected int pos;

  public JsonParserForTests(String input) {
    this.input = input;

    tokenizer = new JsonTokenizerForTests(input);
    tokenizer.tokenize();
    tokens = tokenizer.tokens;
  }

  /**
   * Parse a JSON value.
   */
  public Object parse() {
    Token token = match("value expected");
    if (token.isId("true"))
      return true;
    else if (token.isId("false"))
      return false;
    else if (token.isId("null"))
      return null;
    else if (token.isOp("{"))
      return parseObjectCompletion();
    else if (token.isOp("["))
      return parseArrayCompletion();
    else if (token.type == TokenType.stringLit)
      return token.value;
    else if (token.type == TokenType.numLit)
      return Double.parseDouble(token.value);
    else
      return error("unparseable JSON value", token);
  }

  /**
   * Parse a JSON object literal. We assume that the initial '{' has already been consumed.
   */
  private JSONObject parseObjectCompletion() {
    JSONObject object = new JSONObject();
    if (tryMatchOp("}"))
      return object;

    while (true) {
      Token token = match("field name expected");
      if (token.type != TokenType.stringLit)
        tokenizer.error("field name expected", prevToken());

      String fieldName = token.value;
      matchOp(":");
      Object fieldValue = parse();
      object.put(fieldName, fieldValue);

      if (tryMatchOp("}"))
        return object;

      matchOp(",");
    }
  }

  /**
   * Parse a JSON array literal. We assume that the initial '[' has already been consumed.
   */
  private JSONArray parseArrayCompletion() {
    JSONArray array = new JSONArray();
    if (tryMatchOp("]"))
      return array;

    while (true) {
      Object value = parse();
      array.add(value);
      if (tryMatchOp("]"))
        return array;

      matchOp(",");
    }
  }

  /**
   * Consume and return the next token.  If we are at the end of the input text, report an error.
   */
  private Token match(String errorMessage) {
    if (atEnd())
      if (pos == 0)
        tokenizer.error(errorMessage, 0, input.length());
      else
        tokenizer.error(errorMessage, prevToken());

    return tokens.get(pos++);
  }

  /**
   * Consume the next token.  If it is not the specified operator, report an error.
   */
  private Token matchOp(String string) {
    Token token = match("Expected \"" + string + "\"");
    if (!token.isOp(string))
      error("Expected \"" + string + "\"", token);

    return token;
  }

  /**
   * If the next token is the specified operator, consume it and return true.  Otherwise, or if we are at the end
   * of the input, do nothing and return false.
   */
  private boolean tryMatchOp(String string) {
    Token token = peek();
    if (token == null || !token.isOp(string))
      return false;

    match("Expected \"" + string + "\"");
    return true;
  }

  /**
   * Return the next token, without consuming it.  If we are at the end of the input text, return null.
   */
  private Token peek() {
    if (atEnd())
      return null;

    return tokens.get(pos);
  }

  private Void error(String message, Token atToken) {
    return error(message, atToken.pos, atToken.len);
  }

  private Void error(String message, int position, int len) {
    tokenizer.error(message, position, len);
    return null; // never reached
  }

  /**
   * Return the most recently consumed token.  If no tokens have yet been consumed, throw an excption.
   */
  private Token prevToken() {
    if (pos == 0)
      throw new RuntimeException("prevToken() called before any tokens have been consumed");

    return tokens.get(pos-1);
  }

  private boolean atEnd() {
    return pos >= tokens.size();
  }
}
