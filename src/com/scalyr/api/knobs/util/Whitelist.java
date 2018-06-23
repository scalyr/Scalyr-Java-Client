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

package com.scalyr.api.knobs.util;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.scalyr.api.Callback;
import com.scalyr.api.json.JSONArray;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.knobs.ConfigurationFile;
import com.scalyr.api.knobs.Knob;

/**

A Whitelist provides a quick and efficient way to test whether a given string appears
in an array of string values (or a comma-delimited list of strings) stored in a Knob.
The following syntax is supported:

```
["x", "y", "z"]  -- One or more options. Leading / trailing whitespace from each option is discarded.
["^", "x", "y"]  -- The initial caret indicates that this is a blacklist rather than a whitelist.
                    Must appear on its own as the first element in the array.
                    All values *not* appearing in the list are accepted.
["*"]            -- Wildcard: all values are accepted.
["^*"]           -- Negative wildcard: no values are accepted.
```

Legacy:

```
"x, y, z"  -- One or more options, separated by commas. Leading / trailing whitespace from
              each option is discarded.
"^x, y, z" -- The initial caret indicates that this is a blacklist rather than a whitelist.
              All values *not* appearing in the list are accepted.
"*"        -- Wildcard: all values are accepted.
"^*"       -- Negative wildcard: no values are accepted.
```

 */
public class Whitelist {
  private final Knob whitelistKnob;
  
  /**
   * Holds the result of parsing the most recent knob value. Null until first use.
   * Synchronize access on the Whitelist instance.
   */
  private Set<String> whitelistSet = null;
  
  /**
   * True if the most recent knob value was a wildcard ("*" or "^*"). Undefined if whitelistSet is
   * null. Synchronize access on the Whitelist instance.
   */
  private boolean wildcard;
  
  /**
   * True if the most recent knob value was a negated value (began with a caret). Undefined if whitelistSet is
   * null. Synchronize access on the Whitelist instance.
   */
  private boolean negated;


  /**
   * Used by other constructors.
   */
  private Whitelist(Knob whitelistKnob) {
    this.whitelistKnob = whitelistKnob;
  }

  /**
   * Construct a Whitelist based on a Knob. The Knob can contain either an array of string values or
   * a comma-delimited list of strings. An empty or missing Knob is treated as an empty list. Leading
   * or trailing whitespace, and whitespace adjacent to commas, is ignored. See our class comment for
   * a discussion of wildcards ('*') and negation ('^').
   *
   * @param key            The knob's key.
   * @param defaultValue   Default value to use if no value for the knob is specified.
   */
  public Whitelist(java.lang.String key, String defaultValue, ConfigurationFile ... files) {
    this(new Knob(key, defaultValue, files));
  }


  /**
   * No-op developer convenience/hygiene method: when defining a new Whitelist that
   * we ought to cleanup at some point, add this method to the Whitelist declaration:
   *
   * ```
   * final Whitelist accountsUsingOldImplementation = new Whitelist("accountsUsingOldImplementation", "^*").expireHint("12/15/2017");
   * ```
   *
   * @param dateStr after which we may want to pull this knob.  Not currently parsed.
   * @return self for chaining
   */
  public Whitelist expireHint(String dateStr) {
    return this;
  }

  /** Convenience wrapper for `isInWhitelist(enumVal.name())`. */
  public synchronized boolean isInWhitelist(Enum<?> enumVal) {
    return isInWhitelist(enumVal.name());
  }
  
  /**
   * Return true if the given string appears in the whitelist.
   */
  public synchronized boolean isInWhitelist(String candidate) {
    prepareValue();
    
    if (wildcard)
      return !negated;
    else
      return !negated == whitelistSet.contains(candidate);
  }
  
  /**
   * Return true if our current value is a non-negated wildcard.
   */
  public synchronized boolean acceptsAll() {
    prepareValue();
    
    return wildcard && !negated;
  }
  
  /**
   * Return true if our current value is a negated wildcard.
   */
  public synchronized boolean acceptsNone() {
    prepareValue();
    
    return wildcard && negated;
  }

  private void prepareValue() {
    if (whitelistSet == null) {
      whitelistSet = new HashSet<String>();
      whitelistKnob.addUpdateListener(new Callback<Knob>(){
        @Override public void run(Knob value) {
          rebuildStringSet();
        }});
      rebuildStringSet();
    }
  }
  
  /**
   * Overwrite whitelistSet, wildcard, and negated with the latest values from the Knob.
   */
  private synchronized void rebuildStringSet() {
    whitelistSet.clear();
    wildcard = false;
    negated = false;

    Object knobValueObj = whitelistKnob.get();

    if (knobValueObj instanceof JSONArray) {
      JSONArray array = (JSONArray) knobValueObj;
      if (!array.isEmpty()) {
        String first = null;
        Object firstObj = array.get(0);
        if (firstObj instanceof JSONObject) {
          JSONObject obj = (JSONObject) firstObj;
          Object objValue = obj.get("value");
          if (objValue != null) first = objValue.toString().trim();
        } else {
          first = array.get(0).toString().trim();
        }

        // if first is null (which is possible) then skipObject will end up being null and therefore == first

        if (first != null && first.matches("^\\^\\s*\\*$")) {  // (carat, any possible whitespace, asterisk) = blacklist everything
          negated = true;
          wildcard = true;
        } else if ("^".equals(first)) {  // blacklist
          negated = true;
        } else if ("*".equals(first)) {  // wildcard
          wildcard = true;
        }
        String skipObject = negated || wildcard ? first : null;

        for (Object item: array) {
          if (item != skipObject) {
            if (item instanceof JSONObject) {
              JSONObject obj = (JSONObject) item;
              Object value = obj.getOrDefault("value", null);
              if (value != null) {
                whitelistSet.add(value.toString().trim());
              }
            } else {
              whitelistSet.add(item.toString().trim());
            }
          }
        }
      }
    } else if (knobValueObj != null) {
      String knobValue = knobValueObj.toString().trim();
      // if ^ is the first real character of the string, it's a blacklist
      if (knobValue.startsWith("^")) {
        negated = true;
        knobValue = knobValue.substring(1).trim();
      }

      if (knobValue.equals("*")) {
        wildcard = true;
      } else {
        for (String value : knobValue.split(",")) {
          whitelistSet.add(value.trim());
        }
      }
    }
  }

}
