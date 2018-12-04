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

package com.scalyr.api;

/**
 * Utilities for converting data from one type to another.
 *
 * Used to avoid type confusion when reading fields from a JSON object.
 */
public class Converter {
  /**
   * Convert any numeric type to Integer.
   * <p>
   * A null input is returned as-is. Non-numeric inputs trigger an exception. Out-of-range
   * values trigger undefined behavior.
   */
  public static Integer toInteger(Object value) {
    if (value instanceof Integer)
      return (Integer)value;
    else if (value instanceof Long)
      return (int)(long)(Long)value;
    else if (value instanceof Double)
      return (int)(double)(Double)value;
    else if (value == null)
      return null;
    else
      throw new RuntimeException("Can't convert [" + value + "] to Integer");
  }

  /**
   * Convert any numeric type to Long.
   * <p>
   * A null input is returned as-is. Non-numeric inputs trigger an exception. Out-of-range
   * values trigger undefined behavior.
   */
  public static Long toLong(Object value) {
    if (value instanceof Integer)
      return (long)(int)(Integer)value;
    else if (value instanceof Long)
      return (Long)value;
    else if (value instanceof Double)
      return (long)(double)(Double)value;
    else if (value == null)
      return null;
    else
      throw new RuntimeException("Can't convert [" + value + "] to Long");
  }

  /**
   * Convert any numeric type to Double.
   * <p>
   * A null input is returned as-is. Non-numeric inputs trigger an exception.
   */
  public static Double toDouble(Object value) {
    if (value instanceof Integer)
      return (double)(int)(Integer)value;
    else if (value instanceof Long)
      return (double)(long)(Long)value;
    else if (value instanceof Double)
      return (Double)value;
    else if (value == null)
      return null;
    else
      throw new RuntimeException("Can't convert [" + value + "] to Double");
  }

  /**
   * Return the given value coerced to Boolean.
   * <p>
   * A null input is returned as-is. Non-Boolean inputs trigger an exception.
   */
  public static Boolean toBoolean(Object value) {
    if (value instanceof Boolean)
      return (Boolean) value;
    else if (value == null)
      return null;
    else
      throw new RuntimeException("Can't convert [" + value + "] to Boolean");
  }

  /**
   * Return the given value converted to a String.
   * <p>
   * A null input is returned as-is.
   */
  public static String toString(Object value) {
    if (value != null)
      return value.toString();
    else
      return null;
  }

  /**
   * Converts certain pattern of String to disk/memory/network sizes.
   *
   * It takes a variety of values:
   *
   * 1000 // just number, default SI is bytes
   * 1KB  // 1000 bytes
   * 1KiB // 1024 bytes
   *
   * the accepted pattern is: [0-9]+[\s]*[[[KMGTP]i?]?B]?
   *
   *    [\s]*[0-9]+[\s]*[[[KMGT]i?]?B]?[\s]*
   *  0   1    2     3       4   5   6   7     0
   */
  public static Long parseNumberWithSI(Object valueWithSIObj) {
    String valueWithSI = toString(valueWithSIObj);
    long numberPart = 0;
    char multiplier = '\0';
    boolean withI = false;
    short state = 0;
    java.lang.String exceptionMessage = "Can't convert [" + valueWithSI + "]";
    for (int i = 0; i < valueWithSI.length(); i++) {
      char c = valueWithSI.charAt(i);
      if (c >= '0' && c <= '9') {
        switch (state) {
          case 0:
          case 1:
          case 2: state = 2; numberPart *= 10; numberPart += c - '0'; break;
          default: throw new RuntimeException(exceptionMessage);
        }
      } else if (c == ' ') {
        switch (state) {
          case 0:
          case 1: state = 1; break;
          case 2:
          case 3: state = 3; break;
          case 6:
          case 7: state = 7; break;
          default: throw new RuntimeException(exceptionMessage);
        }
      } else if (c == 'K' || c == 'M' || c == 'G' || c == 'T') {
        switch (state) {
          case 2:
          case 3: state = 4; multiplier = c; break;
          default: throw new RuntimeException(exceptionMessage);
        }
      } else if (c == 'i') {
        switch (state) {
          case 4: state = 5; withI = true; break;
          default: throw new RuntimeException(exceptionMessage);
        }
      } else if (c == 'B') {
        switch (state) {
          case 2:
          case 3:
          case 4:
          case 5: state = 6; break;
          default: throw new RuntimeException(exceptionMessage);
        }
      } else {
        throw new RuntimeException(exceptionMessage);
      }
    }
    if (state == 2 || state == 3) {
      return numberPart;
    } else if (state == 6 || state == 7) {
      long base = withI ? 1024 : 1000;
      switch (multiplier) {
        case '\0': return numberPart;
        case 'K': return numberPart * base;
        case 'M': return numberPart * base * base;
        case 'G': return numberPart * base * base * base;
        case 'T': return numberPart * base * base * base * base;
      }
    }
    throw new RuntimeException(exceptionMessage);
  }
}
