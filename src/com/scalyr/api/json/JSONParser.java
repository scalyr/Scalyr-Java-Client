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

package com.scalyr.api.json;

import com.scalyr.api.internal.ScalyrUtil;

import java.nio.charset.Charset;
import java.util.Arrays;

public class JSONParser {
  private static final Charset utf8 = Charset.forName("UTF-8");
  
  private final ByteScanner scanner;
  
  /**
   * Buffer for accumulating numbers to be parsed.
   */
  private final byte[] numberBuf = new byte[100];
  
  /**
   * If true, then we allow commas to be ommitted in array and object declarations, so long as there
   * is a line break between subsequent values.
   */
  public boolean allowMissingCommas = true;
  
  public JSONParser(ByteScanner scanner) {
    this.scanner = scanner;
  }
  
  public static Object parse(String input) {
    return new JSONParser(new ByteScanner(input.getBytes(utf8))).parseValue();
  }
  
  public Object parseValue() {
    int startPos = scanner.getPos();
    int c = readNextNonWhitespace();
    if (c == '{') {
      return parseObject();
    } else if (c == '[') {
      return parseArray();
    } else if (c == '"') {
      if (consumeRepeatedChars('"', 2)) {
        return parseTripleQuotedString();
      } else {
        return parseStringWithConcatenation();
      }
    } else if (c == 't') {
      match("true", "unknown identifier");
      return true;
    } else if (c == 'f') {
      match("false", "unknown identifier");
      return false;
    } else if (c == 'n') {
      match("null", "unknown identifier");
      return null;
    } else if (c == '-' || (c >= '0' && c <= '9')) {
      return parseNumber(c);
    } else if (c == '`') {
      return parseLengthPrefixedValue();
    } else if (c == '}') {
      error("'}' can only be used to end an object");
      return null; // never reached
    } else {
      if (c == -1)
        error(startPos == 0 ? "Empty input" : "Unexpected end-of-text");
      else
        error("Unexpected character '" + (char) c + "'");
      return null; // never reached
    }
  }
  
  /**
   * Parse a JSON object. The '{' has already been scanned.
   */
  private JSONObject parseObject() {
    int objectStart = scanner.getPos() - 1;
    
    JSONObject object = new JSONObject();
    
    while (true) {
      String key = null;
      int c = readNextNonWhitespace();
      
      int nameStart = scanner.getPos() - 1;
      if (c == '"') {
        key = parseString();
      } else if (c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
        key = parseIdentifier(c);
        
        int nextChar = scanner.peekUByteOrFlag();
        if (nextChar > 32 && nextChar != ':')
          error("to use character '" + (char)nextChar
              + "' in an attribute name, you must place the attribute name in double-quotes", scanner.getPos());
      } else if (c == '}') { // end-of-object
        return object;
      } else if (c < 0) {
        error("Need '}' for end of object", objectStart);
      } else {
        error("Expected string literal for object attribute name");
      }
      
      int nameEnd = scanner.getPos();
      c = readNextNonWhitespace();
      if (c != ':')
        error("Expected ':' delimiting object attribute value");
      
      peekNextNonWhitespace(); // skip any whitespace after the colon
      int valueStart = scanner.getPos();
      object.put(key, parseValue());
      
      c = peekNextNonWhitespace();
      if (c == -1) {
        error("Need '}' for end of object", objectStart);
      } else if (c == '}') {
        // do nothing; we'll process the '}' back around at the top of the loop.
      } else if (c == ',') {
        readNextNonWhitespace();
      } else {
        if (scanner.preceedingLineBreak() && allowMissingCommas) {
          // proceed, inferring a comma
        } else {
          error("After object field, expected ',' or '}' but found '" + (char)c + "'... are you missing a comma?");
        }
      }
    }
  }
  
  /**
   * Parse a JSON object. The '[' has already been scanned.
   */
  private JSONArray parseArray() {
    int arrayStart = scanner.getPos() - 1;
    JSONArray array;
    array = new JSONArray();
    
    while (true) {
      // Check for end-of-array.
      if (peekNextNonWhitespace() == ']') {
        scanner.readUByte();
        return array;
      }
      
      peekNextNonWhitespace(); // skip any whitespace
      int valueStartPos = scanner.getPos();
      array.add(parseValue());
      int c = peekNextNonWhitespace();
      if (c == -1) {
        error("Array has no terminating '['", arrayStart);
      } else if (c == ']') {
        // do nothing; we'll process the ']' back around at the top of the loop.
      } else if (c == ',') {
        readNextNonWhitespace();
      } else {
        if (scanner.preceedingLineBreak() && allowMissingCommas) {
          // proceed, inferring a comma
        } else {
          error("Unexpected character [" + (char)c + "] in array... are you missing a comma?");
        }
      }
    }
  }
  
  /**
   * Parse a string literal. The '"' has already been scanned.
   * 
   * If the string is followed by one or more "+", string literal sequences, consume those as well, and
   * return the concatenation. E.g. for input:
   * 
   *   "abc" + "def" + "ghi"
   *   
   * we return abcdefghi.
   */
  private String parseStringWithConcatenation() {
    String value = parseString();
    
    int c = peekNextNonWhitespace();
    if (c != '+')
      return value;
    
    StringBuilder sb = new StringBuilder();
    sb.append(value);
    
    while (true) {
      ScalyrUtil.Assert(scanner.readUByte() == '+', "expected '+'");
      
      c = peekNextNonWhitespace();
      if (c != '"')
        error("Expected string literal after + operator");
      
      ScalyrUtil.Assert(scanner.readUByte() == '"', "expected '\"'");
      sb.append(parseString());
      if (peekNextNonWhitespace() != '+')
        break;
    }
    
    return sb.toString();
  }
  
  /**
   * Parse an identifier. The initial character has already been scanned.
   */
  private String parseIdentifier(int initialChar) {
    int startPos = scanner.getPos() - 1;
    
    while (true) {
      int c = scanner.peekUByteOrFlag();
      if (c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))
        scanner.readUByte();
      else
        break;
    }
    
    int len = scanner.getPos() - startPos;
    byte[] stringBytes = new byte[len];
    scanner.readBytesFromBuffer(startPos, stringBytes, 0, len);
    return new String(stringBytes, utf8);
  }
  
  /**
   * Parse a string literal. The '"' has already been scanned.
   */
  private String parseString() {
    int startPos = scanner.getPos();
    int len = 0;
    while (true) {
      if (scanner.atEnd())
        throw new JsonParseException("string literal not terminated", startPos-1, lineNumberForBytePos(scanner.buffer, startPos-1));
      
      int c = scanner.readUByte();
      if (c == '"') {
        break;
      } else if (c == '\\') {
        if (scanner.atEnd())
          error("incomplete backslash sequence");
        scanner.readUByte();
        len++;
      } else if (c == '\r' || c == '\n') {
        throw new JsonParseException("string literal not terminated before end of line", startPos-1,
            lineNumberForBytePos(scanner.buffer, startPos-1));
      }
      
      len++;
    }
    
    byte[] stringBytes = new byte[len];
    scanner.readBytesFromBuffer(startPos, stringBytes, 0, len);
    String raw = new String(stringBytes, utf8);
    return processEscapes(raw);
  }

  /**
   * Parse a string literal within triple quotes. The '"""' has already been scanned.
   */
  private String parseTripleQuotedString() {
    int startPos = scanner.getPos();
    int len = 0;
    while (true) {
      if (scanner.atEnd())
        throw new JsonParseException("triple quoted string literal not terminated", startPos-1, lineNumberForBytePos(scanner.buffer, startPos-1));

      int c = scanner.readUByte();
      if (c == '"' && consumeRepeatedChars('"', 2)) {
        // handle any extra quotes at the end of the triple-quoted string in accordance with the HOCON spec.
        // example: """foo"""" should result in the four-character string foo"
        while (!scanner.atEnd() && scanner.peekUByte() == '"') {
          scanner.readUByte();
          len++;
        }
        break;
      }

      len++;
    }

    byte[] stringBytes = new byte[len];
    scanner.readBytesFromBuffer(startPos, stringBytes, 0, len);

    return new String(stringBytes, utf8);
  }

  /**
   * Given the raw contents of a raw string literal, process any backslash sequences.
   */
  private String processEscapes(String s) {
    if (s.indexOf('\\') < 0)
      return s;
    
    int len = s.length();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) {
      char c = s.charAt(i);
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      
      c = s.charAt(++i);
      if (c == 't') {
        sb.append('\t');
      } else if (c == 'n') {
        sb.append('\n');
      } else if (c == 'r') {
        sb.append('\r');
      } else if (c == 'b') {
        sb.append('\b');
      } else if (c == 'f') {
        sb.append('\f');
      } else if (c == '"') {
        sb.append('"');
      } else if (c == '\\') {
        sb.append('\\');
      } else if (c == '/') {
        sb.append('/');
      } else if (c == 'u' && i+5 <= s.length()) {
        String hexString = s.substring(i + 1, i + 5);
        i += 4;
        int hexValue = Integer.parseInt(hexString, 16);
        sb.append((char)hexValue);
      } else {
        error("Unexpected backslash escape [" + c + "]");
      }
    }
    
    return sb.toString();
  }
  
  /**
   * Parse a numeric literal. The first character has already been scanned.
   */
  private Object parseNumber(int firstChar) {
    numberBuf[0] = (byte)firstChar;
    int len = 1;
    
    boolean allDigits = firstChar >= '0' && firstChar <= '9';
    
    while (!scanner.atEnd()) {
      int peek = scanner.peekUByte();
      if (peek != '+' && peek != '-' && peek != 'e' && peek != 'E' && peek != '.' && !(peek >= '0' && peek <= '9'))
        break;
      
      if (len >= numberBuf.length)
        error("numeric literal too long (limit " + numberBuf.length + " characters)");
      
      int nextChar = scanner.readUByte();
      allDigits = allDigits && (nextChar >= '0' && nextChar <= '9');
      numberBuf[len++] = (byte) nextChar;
    }
    
    if (allDigits && len <= 18) {
      long value = 0;
      for (int i = 0; i < len; i++)
        value = (value * 10) + numberBuf[i] - '0';
      return value;
    }
    
    String numberString = new String(numberBuf, 0, len, utf8);
    if (numberString.indexOf('.') < 0 && numberString.indexOf('e') < 0 && numberString.indexOf('E') < 0)
      return Long.parseLong(numberString);
    else
      return Double.parseDouble(numberString);
  }
  
  /**
   * Scan through a // or /* comment. The initial '/' has already been scanned.
   */
  private void parseComment() {
    int commentStartPos = scanner.getPos() - 1;
    
    if (scanner.atEnd())
      error("Unexpected character '/'");
    
    int c = scanner.readUByte();
    if (c == '/') {
      // This is a "//" comment. Scan through EOF.
      while (!scanner.atEnd()) {
        c = scanner.readUByte();
        if (c == '\n' || c == '\r')
          break;
      }
      
      // If this is a CRLF, scan through the LF.
      if (c == '\r' && scanner.peekUByteOrFlag() == '\n')
        scanner.readUByte();
      
    } else if (c == '*') {
      // This is a "/*" comment. Scan through "*/".
      while (!scanner.atEnd()) {
        c = scanner.readUByte();
        if (c == '*' && scanner.peekUByteOrFlag() == '/') {
          scanner.readUByte();
          return;
        }
      }
      
      error("Unterminated comment", commentStartPos);
    } else {
      error("Unexpected character '/'");
    }
  }
  
  /**
   * Parse a byte array (Scalyr extension to the JSON format). The '`' has already been scanned.
   */
  private Object parseLengthPrefixedValue() {
    int c = scanner.peekUByteOrFlag();
    if (c == 's') {
      match("`s", null);

      int length = scanner.readInt();
      return new String(scanner.readBytes(length), utf8);
    } else {
      match("`b", null);

      int length = scanner.readInt();
      return scanner.readBytes(length);
    }
  }
  
  /**
   * Verify that the next N-1 characters match chars.substring(1), and consume them.
   * In case of a mismatch, throw an exception. Only supports low-ASCII characters.
   * 
   * If the errorMessage parameter is null, we generate a default message.
   */
  private void match(String chars, String errorMessage) {
    int startPos = scanner.getPos() - 1;
    for (int i = 1; i < chars.length(); i++) {
      int expected = chars.charAt(i);
      int actual = scanner.atEnd() ? -1 : scanner.readUByte();
      if (expected != actual) {
        if (errorMessage != null)
          error(errorMessage, startPos);
        else
          error("Expected \"" + chars + "\"", startPos);
      }
    }
  }
  
  /**
   * Report an error at the character just consumed.
   */
  private void error(String message) {
    error(message, Math.max(0, scanner.getPos() - 1));
  }
  
  /**
   * Report an error at the specified byte position.
   */
  private void error(String message, int pos) {
    throw new JsonParseException(message, pos, lineNumberForBytePos(scanner.buffer, pos));
  }
  
  /**
   * Scan up to the next non-whitespace, non-comment byte, and return it without consuming it.
   * (We do consume the intervening whitespace.) If there are no further non-whitespace
   * bytes in the input stream, return -1.
   */
  private int peekNextNonWhitespace() {
    while (true) {
      // TODO: support any Unicode / UTF-8 whitespace sequence.
      
      int c = scanner.peekUByteOrFlag();
      if (c == 32 || c == 9 || c == 13 || c == 10) {
        scanner.readUByte();
        continue;
      } else if (c == '/') {
        scanner.readUByte();
        parseComment();
        continue;
      }
      
      return c;
    }
  }

  /**
   * Attempts to consume a repetition of the specified ubyte.  The exact next `count` characters in the stream
   * must equal the expected ubyte value.  No whitespace is allowed in the repetition.
   *
   * @param expectedUByte  The expected ubyte value.
   * @param count  The number of characters there should be.
   * @return  True if there are exactly `count` characters equal to `expectedUByte` next in the stream (and those
   *     characters are consumed).  Otherwise, false is returned and the position is unchanged.
   */
  private boolean consumeRepeatedChars(int expectedUByte, int count) {
    for (int i = 0; i < count; ++i) {
      if (expectedUByte != scanner.peekUByteOrFlag(i)) {
        return false;
      }
    }

    for (int i = 0; i < count; ++i) {
      scanner.readUByte();
    }
    return true;
  }
  
  /**
   * Scan through the next non-whitespace, non-comment byte, and return it. If there are
   * no further such bytes, return -1.
   */
  private int readNextNonWhitespace() {
    int c = peekNextNonWhitespace();
    return (c == -1) ? -1 : scanner.readUByte();
  }
  
  public static class JsonParseException extends RuntimeException {
    /**
     * Byte position (counting from 0) in the UTF-8 input, where the exception occurred.
     */
    public final int bytePos;
    
    /**
     * Line number (counting from 1) where the exception occurred.
     */
    public final int lineNumber;
    
    public JsonParseException(String message, int bytePos, int lineNumber) {
      super(message + " (line " + lineNumber + ", byte position " + bytePos + ")");
      this.bytePos = bytePos;
      this.lineNumber = lineNumber;
    }
    
    public JsonParseException(Exception cause, int bytePos, int lineNumber) {
      super("Parse error at line " + lineNumber + ", byte position " + bytePos, cause);
      this.bytePos = bytePos;
      this.lineNumber = lineNumber;
    }
  }
  
  /**
   * Represents a pair of positions in the input stream.
   * @author steve
   *
   */
  public static class ByteRange {
    public final int start, end;
    
    public ByteRange(int start, int end) {
      this.start = start;
      this.end   = end;
    }
  }  
  public static class ByteScanner {
    /**
     * The buffer we scan over.
     */
    private final byte[] buffer;
    
    /**
     * Our current position in the buffer.
     */
    private int pos;
    
    /**
     * The end of the buffer range which we scan.
     */
    public final int maxPos;
    
    public ByteScanner(byte[] buffer) {
      this(buffer, 0, buffer.length);
    }
    
    public ByteScanner(byte[] buffer, int startPos, int maxPos) {
      this.buffer = buffer;
      this.pos    = startPos;
      this.maxPos = maxPos;
    }
    
    public boolean atEnd() {
      return pos >= maxPos;
    }
    
    public int getPos() {
      return pos;
    }
    
    /**
     * Return the next byte, unsigned. If there are no more bytes to be read, throw an exception.
     */
    public int readUByte() {
      checkReadSize(1);
      
      return buffer[pos++] & 255;
    }
    
    /**
     * Return the next byte, unsigned, without consuming it. If there are no more bytes to be read, throw an exception.
     */
    public int peekUByte() {
      checkReadSize(1);
      
      return buffer[pos] & 255;
    }
   
    /**
     * Return the next byte, unsigned, without consuming it. If there are no more bytes to be read, return -1.
     */
    public int peekUByteOrFlag() {
      if (pos >= maxPos)
        return -1;

      return buffer[pos] & 255;
    }

    /**
     * Return the a byte, unsigned, without consuming it. If the byte is past the end of the stream, return -1.
     *
     * @param offset  The offset to read the byte from, relative to the current location.
     */
    public int peekUByteOrFlag(int offset) {
      if (pos + offset < 0)
        return -1;

      if (pos + offset >= maxPos)
        return -1;

      return buffer[pos + offset] & 255;
    }

    public int readInt() {
      checkReadSize(4);
      
      int result =
             ((buffer[pos  ]      ) << 24)
           + ((buffer[pos+1] & 255) << 16)
           + ((buffer[pos+2] & 255) << 8)
           + ((buffer[pos+3] & 255) << 0);
      pos += 4;
      return result;
    }
    
    public byte[] readBytes(int len) {
      byte[] result = new byte[len];
      checkReadSize(len);
      System.arraycopy(buffer, pos, result, 0, len);
      pos += len;
      return result;
    }
    
    private void checkReadSize(int readLen) {
      if (pos + readLen > maxPos)
        throw new JsonParseException("Ran off end of buffer (position " + pos + ", limit " + maxPos + ", reading " + readLen + " bytes",
            pos, lineNumberForBytePos(buffer, pos));
    }
    
    public void readBytesFromBuffer(int startPos, byte[] destination, int destPos, int length) {
      System.arraycopy(buffer, startPos, destination, destPos, length);
    }
    
    /**
     * Return true if the current input position is preceeded by a sequence of
     * whitespace characters that includes at least one line break (CR and/or LF).
     */
    public boolean preceedingLineBreak() {
      for (int i = pos - 1; i >= 0; i--) {
        int b = buffer[i] & 255;
        if (b == '\r' || b == '\n')
          return true;
        else if (b == ' ' || b == '\t')
          continue;
        
        break;
      }
      return false;
    }
  }
  
  /**
   * Given a 0-based position in the given UTF-8 byte array, return a 1-based line number.
   */
  public static int lineNumberForBytePos(byte[] buffer, int pos) {
    // Scan buffer[0...pos-1], counting CR and LF bytes along the way.
    int lineNum = 1;
    int x = 0;
    while (x < pos) {
      int b = buffer[x] & 255;
      x++;
      
      if (b == '\n') {
        lineNum++;
      } else if (b == '\r') {
        lineNum++;
        
        // If this CR is the first half of a CRLF sequence, skip the LF; otherwise
        // we'd double-count CRLF line breaks.
        if (x < pos && (buffer[x] & 255) == '\n')
          x++;
      }
    }
    
    return lineNum;
  }
}