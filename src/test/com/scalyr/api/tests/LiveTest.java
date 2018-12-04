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

import org.junit.Ignore;
import org.junit.Test;

import com.scalyr.api.query.QueryService;
import com.scalyr.api.query.QueryService.NumericQueryResult;
import com.scalyr.api.query.QueryService.TimeseriesQueryResult;
import com.scalyr.api.query.QueryService.TimeseriesQuerySpec;

/**
 * Tests against the live staging server. Not part of AllTests, as these are only run ocasionally ad-hoc, and
 * some have side effects (e.g. creating a new timeseries).
 */
@Ignore
public class LiveTest {
  private static String timeseriesId = "vN6OQJf4zG5LCTPC"; // rate of matches for 'red'

  private static String readLogsToken = "xxx";

  @Test public void testNumericQuery() {
    QueryService queryService = new QueryService(readLogsToken);
    queryService.setServerAddress("https://logstaging.scalyr.com");

    NumericQueryResult result = queryService.numericQuery(
        "$source='tsdb' $serverHost='prod-log-1z' metric='proc.stat.cpu_rate' type='user'",
        "value", "1h", null, 60);
    System.out.println(result.values);
  }

  @Test public void testTimeseriesQuery() {
    QueryService queryService = new QueryService(readLogsToken);
    queryService.setServerAddress("https://logstaging.scalyr.com");

    TimeseriesQuerySpec querySpec = new TimeseriesQuerySpec();
    querySpec.timeseriesId = timeseriesId;
    querySpec.startTime = "24h";
    querySpec.endTime = null;
    querySpec.buckets = 1;

    TimeseriesQueryResult result = queryService.timeseriesQuery(new TimeseriesQuerySpec[]{querySpec});
    System.out.println(result.values.get(0).values.get(0));
  }
}