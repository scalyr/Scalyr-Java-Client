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

package com.scalyr.api.internal;

/**
 * Implementation of rate limiting.  See http://amistrongeryet.blogspot.com/2011/01/rate-limiting-in-150-lines-or.html
 * for background on the design.
 */
public class SimpleRateLimiter {
  /**
   * Resources currently available.
   */
  double currentBudget;

  /**
   * Maximum value to which currentBudget can grow -- i.e. the point at which
   * the bucket is "full".
   */
  public final double maxBudget;

  /**
   * Amount added to currentBudget each millisecond.
   */
  public final double fillRatePerMs;

  /**
   * Time when currentBudget was last updated.
   */
  long lastUpdateMs;

  /**
   * Construct a SimpleRateLimiter.
   *
   * @param fillRatePerSecond Amount of resource that can be consumed per second.
   * @param maxBudget Maximum "burst" size supported (the size of the "leaky bucket").
   */
  public SimpleRateLimiter(double fillRatePerSecond, double maxBudget) {
    this.currentBudget = maxBudget;
    this.maxBudget = maxBudget;
    this.fillRatePerMs = fillRatePerSecond / 1000.0;
    this.lastUpdateMs = ScalyrUtil.currentTimeMillis();
  }

  /**
   * Attempt to consume the specified amount of resources. If the resources are
   * available, consume them and return true; otherwise, consume nothing and
   * return false.
   */
  public boolean consume(double amount) {
    bringUpToDate(ScalyrUtil.currentTimeMillis());

    if (currentBudget >= amount) {
      currentBudget -= amount;
      return true;
    } else {
      return false;
    }
  }

  private void bringUpToDate(long currentTime) {
    long msSinceLastUpdate = currentTime - lastUpdateMs;
    if (msSinceLastUpdate > 0) {
      currentBudget = Math.min(currentBudget + msSinceLastUpdate * fillRatePerMs, maxBudget);
      lastUpdateMs += msSinceLastUpdate;
    }
  }
}
