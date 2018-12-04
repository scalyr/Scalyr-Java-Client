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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.Test;

import com.scalyr.api.ScalyrServerException;
import com.scalyr.api.internal.ScalyrService;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.query.QueryService;
import com.scalyr.api.query.QueryService.CreateTimeseriesResult;
import com.scalyr.api.query.QueryService.FacetQueryResult;
import com.scalyr.api.query.QueryService.LogQueryResult;
import com.scalyr.api.query.QueryService.NumericQueryResult;
import com.scalyr.api.query.QueryService.PageMode;
import com.scalyr.api.query.QueryService.TimeseriesQueryResult;
import com.scalyr.api.query.QueryService.TimeseriesQuerySpec;
import com.scalyr.api.tests.MockServer.ExpectedRequest;

/**
 * Tests for the Logs query client library.
 */
public class QueryTest extends LogsTestBase {
  /**
   * A simple test of QueryService.logQuery(), issuing a query and verifying that the response is
   * correctly unpacked.
   */
  @Test public void testLogQuery() {
    QueryService queryService = new MockQueryService();

    expectRequest(
        "api/query",
        "{'token': 'dummyToken',"
      + "'queryType': 'log',"
      + "'filter': 'foo > 5',"
      + "'startTime': '4h',"
      + "'maxCount': 1000,"
      + "'pageMode': 'head',"
      + "'columns': 'foo,bar'"
      + "}",
        "{'status': 'success',"
      + "'executionTime': 15,"
      + "'continuationToken': 'abc',"
      + "'sessions': {'s1': {'host': 'host1', 'dc': 'east'}, 's2': {'host': 'host2'}},"
      + "'matches': ["
      + "  {'timestamp': '1393009097459537089', 'message': 'hello, world', 'severity': 2, 'session': 's1', 'thread': 't1', 'attributes': {'x': 'f1', 'y': 'f2'}},"
      + "  {'timestamp': '1393009097459537090', 'severity': 3, 'session': 's2', 'thread': 't2', 'attributes': {'x': 'f3', 'y': 'f4'}}"
      + "]"
      + "}"
        );

    LogQueryResult queryResult = queryService.logQuery("foo > 5", "4h", null, 1000, PageMode.head, "foo,bar", null);
    assertEquals("{LogQueryResult: 2 matches, execution time 15.0 ms, continuationToken [abc]\n"
        + "  {timestamp 1393009097459537089: fine hello, world, fields {x=f1, y=f2}, thread t1, session s1 / {dc=east, host=host1}}\n"
        + "  {timestamp 1393009097459537090: info null, fields {x=f3, y=f4}, thread t2, session s2 / {host=host2}}\n"
        + "}",
        queryResult.toString());


    // Issue a second query, populating different fields of the request and response than in the first query.
    expectRequest(
        "api/query",
        "{'token': 'dummyToken',"
      + "'queryType': 'log',"
      + "'endTime': '1393009097459537091',"
      + "'maxCount': 1,"
      + "'continuationToken': 'abc'"
      + "}",
        "{'status': 'success',"
      + "'executionTime': 15,"
      + "'sessions': {'s1': {'host': 'host1', 'dc': 'east'}, 's2': {'host': 'host2'}},"
      + "'matches': ["
      + "]"
      + "}"
        );

    queryResult = queryService.logQuery(null, null, "1393009097459537091", 1, null, null, "abc");
    assertEquals("{LogQueryResult: 0 matches, execution time 15.0 ms\n"
        + "}",
        queryResult.toString());
  }

  /**
   * Verify that error responses from the server are properly converted into exceptions for logQuery().
   */
  @Test public void testErrorHandling() {
    QueryService queryService = new MockQueryService();

    expectRequest(
        "api/query",
        "{'token': 'dummyToken',"
      + "'queryType': 'log',"
      + "'filter': 'foo > 5',"
      + "'startTime': '4h',"
      + "'maxCount': 1000,"
      + "'pageMode': 'head',"
      + "'columns': 'foo,bar'"
      + "}",
        "{'status': 'error/server/test',"
      + "'message': 'this is a test'"
      + "}"
        );

    try {
      queryService.logQuery("foo > 5", "4h", null, 1000, PageMode.head, "foo,bar", null);
      fail("should throw an exception");
    } catch (ScalyrServerException ex) {
      assertEquals("Error response from Scalyr server: status [error/server/test], message [this is a test]",
          ex.getMessage());
    }

    expectRequest(
        "api/query",
        "{'token': 'dummyToken',"
      + "'queryType': 'log',"
      + "'filter': 'foo > 5',"
      + "'startTime': '4h',"
      + "'maxCount': 1000,"
      + "'pageMode': 'head',"
      + "'columns': 'foo,bar'"
      + "}",
        "{'status': 'error/server/test',"
      + "'__status': 500,"
      + "'message': 'this is a test'"
      + "}"
        );

    try {
      queryService.logQuery("foo > 5", "4h", null, 1000, PageMode.head, "foo,bar", null);
      fail("should throw an exception");
    } catch (ScalyrServerException ex) {
      assertEquals("Error response from Scalyr server: status 500 (error/server/test), message [this is a test]",
          ex.getMessage());
    }
  }

  /**
   * A simple test of QueryService.numericQuery(), issuing a query and verifying that the response is
   * correctly unpacked.
   */
  @Test public void testNumericQuery() {
    QueryService queryService = new MockQueryService();

    expectRequest(
        "api/numericQuery",
        "{'token': 'dummyToken',"
      + "'queryType': 'numeric',"
      + "'filter': 'foo > 5',"
      + "'startTime': '4h',"
      + "'buckets': 3"
      + "}",
        "{'status': 'success',"
      + "'executionTime': 15,"
      + "'values': [1, 2.0, -3.5]"
      + "}"
        );

    NumericQueryResult queryResult = queryService.numericQuery("foo > 5", null, "4h", null, 3);
    assertEquals("{NumericQueryResult: 3 values, execution time 15.0 ms, values [1.0, 2.0, -3.5]}",
        queryResult.toString());


    // Issue a second query, populating different fields of the request and response than in the first query.
    expectRequest(
        "api/numericQuery",
        "{'token': 'dummyToken',"
      + "'queryType': 'numeric',"
      + "'function': 'rate',"
      + "'startTime': '4h',"
      + "'endTime': '1393009097459537091',"
      + "'buckets': 1"
      + "}",
        "{'status': 'success',"
      + "'executionTime': 15,"
      + "'values': [123]"
      + "}"
        );

    queryResult = queryService.numericQuery(null, "rate", "4h", "1393009097459537091", 1);
    assertEquals("{NumericQueryResult: 1 value, execution time 15.0 ms, values [123.0]"
        + "}",
        queryResult.toString());
  }

  /**
   * A sinple test of QueryService.facetQuery(), issuing a query and verifying that the response is
   * correctly unpacked.
   */
  @Test public void testFacetQuery() {
    QueryService queryService = new MockQueryService();

    expectRequest(
        "api/facetQuery",
        "{'token': 'dummyToken',"
      + "'queryType': 'facet',"
      + "'filter': 'foo > 5',"
      + "'field': 'field1',"
      + "'startTime': '4h',"
      + "'maxCount': 3"
      + "}",
        "{'status': 'success',"
      + "'matchCount': 123,"
      + "'executionTime': 15,"
      + "'values': [{'value': 'aaa', 'count': 100}, {'value': 'bbb', 'count': 50}, {'value': 'ccc', 'count': 1}]"
      + "}"
        );

    FacetQueryResult queryResult = queryService.facetQuery("foo > 5", "field1", 3, "4h", null);
    assertEquals("{FacetQueryResult: 3 values, 123 matching events, execution time 15.0 ms, values [{aaa: 100}, {bbb: 50}, {ccc: 1}]}",
        queryResult.toString());

    // Issue a second query, populating different fields of the request and response than in the first query.
    expectRequest(
        "api/facetQuery",
        "{'token': 'dummyToken',"
      + "'queryType': 'facet',"
      + "'field': 'field2',"
      + "'startTime': '4h',"
      + "'endTime': '1393009097459537091'"
      + "}",
        "{'status': 'success',"
      + "'matchCount': 1,"
      + "'executionTime': 15,"
      + "'values': [{'value': 'aaa', 'count': 100}]"
      + "}"
        );

    queryResult = queryService.facetQuery(null, "field2", null, "4h", "1393009097459537091");
    assertEquals("{FacetQueryResult: 1 value, 1 matching event, execution time 15.0 ms, values [{aaa: 100}]"
        + "}",
        queryResult.toString());
  }

  /**
   * A simple test of QueryService.timeseriesQuery(), issuing a query and verifying that the response is
   * correctly unpacked.
   */
  @Test public void testTimeseriesQuery() {
    QueryService queryService = new MockQueryService();

    expectRequest(
        "api/timeseriesQuery",
        "{'token': 'dummyToken',"
      + "'queries': ["
      + "  {"
      + "  'timeseriesId': 't1',"
      + "  'startTime': '4h',"
      + "  'endTime': '2h',"
      + "  'buckets': 3"
      + "  },"
      + "  {"
      + "  'timeseriesId': 't2',"
      + "  'startTime': '1d',"
      + "  'endTime': null,"
      + "  'buckets': 1"
      + "  }"
      + "]}",
        "{'status': 'success',"
      + "'executionTime': 15,"
      + "'results': ["
      + "  { 'executionTime': 9, 'values': [1, 2.0, -3.5] },"
      + "  { 'executionTime': 6, 'values': [1234567] }"
      + "]"
      + "}"
        );

    TimeseriesQuerySpec query1 = new TimeseriesQuerySpec();
    query1.timeseriesId = "t1";
    query1.startTime = "4h";
    query1.endTime = "2h";
    query1.buckets = 3;

    TimeseriesQuerySpec query2 = new TimeseriesQuerySpec();
    query2.timeseriesId = "t2";
    query2.startTime = "1d";

    TimeseriesQueryResult queryResult = queryService.timeseriesQuery(new TimeseriesQuerySpec[]{query1, query2});
    assertEquals(
        "{TimeseriesQueryResult: 2 values, execution time 15.0 ms, values ["
      + "{NumericQueryResult: 3 values, execution time 9.0 ms, values [1.0, 2.0, -3.5]}, "
      + "{NumericQueryResult: 1 value, execution time 6.0 ms, values [1234567.0]}]}",
        queryResult.toString());
  }

  /**
   * A simple test of QueryService.createTimeseries(), issuing a request and verifying that the response is
   * correctly unpacked.
   */
  @Test public void testCreateTimeseries() {
    QueryService queryService = new MockQueryService();

    expectRequest(
        "api/createTimeseries",
        "{'token': 'dummyToken',"
      + "'queryType': 'numeric',"
      + "'filter': 'foo > 5',"
      + "'function': 'max(x)'"
      + "}",
        "{'status': 'success',"
      + "'timeseriesId': 'timeseries1'"
      + "}"
        );

    CreateTimeseriesResult result = queryService.createTimeseries("foo > 5", "max(x)");
    assertEquals(
        "{CreateTimeseriesResult: timeseriesId=timeseries1}",
        result.toString());
  }

  // @Test public void liveTest() {
  //   QueryService queryService = new QueryService("---");
  //   LogQueryResult result = queryService.logQuery("Chrome", "2h", "1h", 7, null, null, null);
  //   System.out.println(result.toString());
  // }

  protected class MockQueryService extends QueryService {
    public final BlockingQueue<ExpectedRequest> expectedRequests = new LinkedBlockingQueue<ExpectedRequest>();

    public MockQueryService() {
      super("dummyToken");
      setServerAddress("dummyServerAddress");
    }

    @Override public JSONObject invokeApi(String methodName, JSONObject parameters) {
      JSONObject result = mockServer.invokeApi(methodName, parameters);
      ScalyrService.throwIfErrorStatus(result);
      return result;
    }
  }

}
