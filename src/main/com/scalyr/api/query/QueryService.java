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

package com.scalyr.api.query;

import com.scalyr.api.ScalyrException;
import com.scalyr.api.ScalyrNetworkException;
import com.scalyr.api.ScalyrServerException;
import com.scalyr.api.internal.Logging;
import com.scalyr.api.internal.ScalyrService;
import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.json.JSONArray;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.logs.EventAttributes;
import com.scalyr.api.logs.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * Encapsulates the raw HTTP-level API to the log query service.
 */
public class QueryService extends ScalyrService {

  /** If nonzero, divide queries into chunks of at most this size for serial execution. */
  private final int chunkSizeHours;


  /**
   * Construct a QueryService for non-chunked execution.
   *
   * @param apiToken The API authorization token to use when communicating with the server. Should
   *     be a "Read Logs" token.
   */
  public QueryService(String apiToken) {
    this(apiToken, 0);
  }

  /**
   * Construct a QueryService.
   *
   * @param apiToken The API authorization token to use when communicating with the server. Should
   *     be a "Read Logs" token.
   * @param chunkSizeHours If nonzero, then for queries which cover a long time period, we split the query
   *     into chunks of this duration and issue each chunk as a separate query. For instance, if `chunkSizeHours`
   *     is 24 and you query a 7-day period, we issue seven separate one-day queries. These queries are issued
   *     one at a time. Chunking should be used if you experience timeouts when querying long time periods, which
   *     can happen if you have a large amount of log data, especially when using full-text search rather than
   *     querying specific fields. (For instance, `userName == 'foo'` will execute more efficiently than `message contains 'foo'`.)
   *
   *     A reasonable chunk size is often 12 to 24 hours. Smaller values reduce the possibility of timeout, but add
   *     overhead for extra round-trips to the Scalyr server.
   *
   *    chunkSizeHours is only supported for a limit set of query types; others will thrown a RuntimeException.
   */
  public QueryService(String apiToken, int chunkSizeHours) {
    super(apiToken);
    this.chunkSizeHours = chunkSizeHours;
  }


  /**
   * Specifies which direction to page through the matching log events when more events match the
   * query than can be returned in a single response.
   */
  public static enum PageMode {
    head,
    tail
  }

  /**
   * Retrieve selected log messages.
   *
   * @param filter Specifies which log records to match, using the same syntax as the Expression field in the
   *     query UI. To match all log records, pass null or an empty string.
   * @param startTime The beginning of the time range to query, using the same syntax as the query UI. You can
   *     also supply a simple timestamp, measured in seconds, milliseconds, or nanoseconds since 1/1/1970.
   * @param endTime The end of the time range to query, using the same syntax as the query UI. You can also
   *     supply a simple timestamp, measured in seconds, milliseconds, or nanoseconds since 1/1/1970.
   * @param maxCount The maximum number of records to return. You may specify a value from 1 to 5000.
   * @param pageMode applies when the number of log records matching the query is more than maxCount. Pass
   *     head to get the oldest matches in the specified time range, or tail to get the newest.
   * @param columns A comma-delimited list of fields to return for each log message. To retrieve all fields,
   *     pass null (or the empty string).
   * @param continuationToken Used to page through result sets larger than maxCount. Pass null for your
   *     first query. You may then repeat the query with the same filter, startTime, endTime, and pageMode to
   *     retrieve further matches. Each time, set continuationToken to the value returned by the previous query.
   *     When using continuationToken, you should set startTime and endTime to absolute values, not relative values
   *     such as ``4h``. If you use relative time values, and the time range drifts so that the continuation token
   *     refers to an event that falls outside the new time range, the query will fail.
   *
   * @throws ScalyrException if a low-level error occurs (e.g. network failure)
   * @throws ScalyrServerException if the Scalyr service returns an error
   */
  public LogQueryResult logQuery(String filter, String startTime, String endTime, Integer maxCount,
                                 PageMode pageMode, String columns, String continuationToken)
      throws ScalyrException, ScalyrNetworkException {

    if (chunkSizeHours <= 0)
      return logQuery_(filter, startTime, endTime, maxCount, pageMode, columns, continuationToken);

    Stream<Pair<String>> chunked = splitIntoChunks(startTime, endTime, chunkSizeHours);

    if (pageMode == PageMode.tail) chunked = reversed(chunked); // splitIntoChunks returns oldest -> newest, must flip for tail

    AtomicBoolean stop = new AtomicBoolean(false);
    return chunked
      .filter(pair -> !stop.get())
      .map(pair -> logQuery_(filter, pair.a, pair.b, maxCount, pageMode, columns, continuationToken))
      .peek(result -> stop.getAndSet(result.continuationToken != null)) // stream abuse!
      .collect(Collectors.reducing(null, LogQueryResult::merge));
    }

  // Actual workhorse method for a single blocking query call
  private LogQueryResult logQuery_(String filter, String startTime, String endTime, Integer maxCount,
                                 PageMode pageMode, String columns, String continuationToken)
      throws ScalyrException, ScalyrNetworkException {
    JSONObject parameters = new JSONObject();
    parameters.put("token", apiToken);
    parameters.put("queryType", "log");

    if (filter != null)
      parameters.put("filter", filter);

    if (startTime != null)
      parameters.put("startTime", startTime);

    if (endTime != null)
      parameters.put("endTime", endTime);

    if (maxCount != null)
      parameters.put("maxCount", maxCount);

    if (pageMode != null)
      parameters.put("pageMode", pageMode.toString());

    if (columns != null)
      parameters.put("columns", columns);

    if (continuationToken != null)
      parameters.put("continuationToken", continuationToken);

    JSONObject rawApiResponse = invokeApi("api/query", parameters);
    checkResponseStatus(rawApiResponse);
    return unpackLogQueryResult(rawApiResponse);
  }


  //--------------------------------------------------------------------------------
  // query chunking, public for testing
  //--------------------------------------------------------------------------------

  public static <T> Stream<T> reversed(Stream<T> s) {
    List<T> asList = s.collect(Collectors.toList());
    Collections.reverse(asList);
    return asList.stream();
  }


  public static class Pair<T> {
    public final T a, b;
    public Pair(T a, T b) {
      this.a = a;
      this.b = b;
    }
    @Override public boolean equals(Object o) {
      if (!(o instanceof Pair))
        return false;

      Pair p = (Pair) o;
      return ScalyrUtil.equals(a, p.a) && ScalyrUtil.equals(b, p.b);
    }
  }

  /**
   * Split `[startTime, endTime)` into chunks of at most `chunkSizeHours`.
   *
   * @throws RuntimeException unless both startTime and endTime can be parsed as seconds-, millis-, or nanos-since-1970.
   */
  public static Stream<Pair<String>> splitIntoChunks(String startTime, String endTime, int chunkSizeHours) {
    try {
      return splitIntoChunks(Long.parseLong(startTime), Long.parseLong(endTime), chunkSizeHours)
        .map(longPair -> new Pair<>(Long.toString(longPair.a), Long.toString(longPair.b)));
    } catch (Exception ignored) {
    }

    throw new RuntimeException("Cannot parse [" + startTime + ", " + endTime + "); both values must be epoch seconds");
  }

  /** Split `[start, end)` into `[start, start + chunk), [start + chunk, start + chunk * 2), ... [start + chunk * N, end)`. */
  public static Stream<Pair<Long>> splitIntoChunks(final long start, final long end, final int chunkSizeHours) {
    // figure out the right chunkSize to use per `chunkSizeHours` vs our supported start/end precisions
    // using 1/1/2470 as the cutoff for detecting the precision used by "end"
    final long chunkSize =
      end < 500L*365*24*60*60       ? chunkSizeHours*3600L :       // seconds
      end < 500L*365*24*60*60*1_000 ? chunkSizeHours*3600L*1_000 : // millis
      chunkSizeHours*3600L*ScalyrUtil.NANOS_PER_SECOND;               // everything else nanos

    ArrayList<Pair<Long>> ret = new ArrayList<>();

    for (int n = 0; n < 1000; n++) { // 1000 is just a short-circuit in case of weird input
      long chunkStart = start + n*chunkSize;
      long chunkEnd   = chunkStart + chunkSize;
      ret.add(new Pair(chunkStart, Math.min(end, chunkEnd)));
      if (chunkEnd >= end)
        return ret.stream();
    }

    throw new RuntimeException("Too many chunks for [" + start + ", " + end + ")");
  }





  /**
   * Given the raw server response to a log query, encapsulate the query result in a LogQueryResult.
   * The caller should verify that the query was successful (returned status "success").
   */
  private LogQueryResult unpackLogQueryResult(JSONObject rawApiResponse) {
    // Unpack singleton fields of the response.
    Double executionTime = convertToDouble(rawApiResponse.get("executionTime"));
    if (executionTime == null)
      executionTime = 0.0;

    String continuationToken = (String) rawApiResponse.get("continuationToken");

    // Create a LogQueryResult object.
    LogQueryResult result = new LogQueryResult(executionTime, continuationToken);

    // Create a table of session records.
    Map<String, EventAttributes> sessionInfos = new HashMap<String, EventAttributes>();
    for (Map.Entry<String, Object> entry : ((JSONObject)rawApiResponse.get("sessions")).entrySet()) {
      sessionInfos.put(entry.getKey(), unpackEventAttributes((JSONObject) entry.getValue()));
    }

    // Populate the matches list.
    for (Object matchObject : (JSONArray)rawApiResponse.get("matches")) {
      JSONObject matchJson = (JSONObject) matchObject;
      long timestamp = convertToLong(matchJson.get("timestamp"));
      String message = (String) matchJson.get("message");
      Severity severity = Severity.values()[(int)(long) convertToLong(matchJson.get("severity"))];
      String sessionId = (String) matchJson.get("session");
      String threadId = (String) matchJson.get("thread");
      EventAttributes eventFields = unpackEventAttributes((JSONObject) matchJson.get("attributes"));

      EventAttributes sessionFields = sessionInfos.get(sessionId);

      result.matches.add(new LogQueryMatch(timestamp, message, severity, sessionId, sessionFields, threadId, eventFields));
    }

    return result;
  }

  /**
   * Convert a JSONObject to an EventAttributes.
   */
  private static EventAttributes unpackEventAttributes(JSONObject json) {
    EventAttributes attributes = new EventAttributes();

    for (Map.Entry<String, Object> entry : json.entrySet())
      attributes.put(entry.getKey(), entry.getValue());

    return attributes;
  }

  /**
   * Retrieve numeric data, e.g. for graphing. You can count the rate of events matching some criterion (e.g. error
   * rate), or retrieve a numeric field (e.g. response size).
   *
   * If you will be be invoking the same query repeatedly, you may want to use the {@link #timeseriesQuery} method
   * instead.  This is especially useful if you are using the Scalyr API to feed a home-built dashboard, alerting
   * system, or other automated tool. A timeseries precomputes a numeric query, allowing you to execute queries almost
   * instantaneously, and without exhausting your query execution limit.
   *
   * @param filter Specifies which log records to match, using the same syntax as the Expression field in the
   *     query UI. To match all log records, pass null or an empty string.
   * @param function Specifies the value to compute from the matching events. You can use any function listed
   *     in https://www.scalyr.com/help/query-language#graphFunctions, except for fraction(expr). For
   *     example: mean(x) or median(responseTime), if x and responseTime are fields of your log. You can
   *     also specify a simple field name, such as responseTime, to return the mean value of that field. If
   *     you omit the function argument, the rate of matching events per second will be returned. Specifying rate
   *     yields the same result.
   * @param startTime The beginning of the time range to query, using the same syntax as the query UI. You can
   *     also supply a simple timestamp, measured in seconds, milliseconds, or nanoseconds since 1/1/1970.
   * @param endTime The end of the time range to query, using the same syntax as the query UI. You can also
   *     supply a simple timestamp, measured in seconds, milliseconds, or nanoseconds since 1/1/1970.
   * @param buckets The number of numeric values to return. The time range is divided into this many equal slices.
   *   You may specify a value from 1 to 5000.
   *
   * @throws ScalyrException if a low-level error occurs (e.g. network failure)
   * @throws ScalyrServerException if the Scalyr service returns an error
   */
  public NumericQueryResult numericQuery(String filter, String function, String startTime,
                                         String endTime, Integer buckets)
      throws ScalyrException, ScalyrNetworkException {

    if (chunkSizeHours <= 0)
      return numericQuery_(filter, function, startTime, endTime, buckets);

    Stream<Pair<String>> chunked = splitIntoChunks(startTime, endTime, chunkSizeHours);

    return chunked
      .map(pair -> numericQuery_(filter, function, pair.a, pair.b, buckets))
      .collect(Collectors.reducing(null, NumericQueryResult::merge));
    }



  // actual workhorse of for one blocking query call
  private NumericQueryResult numericQuery_(String filter, String function, String startTime,
                                         String endTime, Integer buckets)
      throws ScalyrException, ScalyrNetworkException {
    JSONObject parameters = new JSONObject();
    parameters.put("token", apiToken);
    parameters.put("queryType", "numeric");

    if (filter != null)
      parameters.put("filter", filter);

    if (function != null)
      parameters.put("function", function);

    if (startTime != null)
      parameters.put("startTime", startTime);

    if (endTime != null)
      parameters.put("endTime", endTime);

    if (buckets != null)
      parameters.put("buckets", buckets);

    JSONObject rawApiResponse = invokeApi("api/numericQuery", parameters);
    checkResponseStatus(rawApiResponse);
    return unpackNumericQueryResult(rawApiResponse);
  }

  /**
   * Given the raw server response to a numeric query, encapsulate the query result in a NumericQueryResult.
   * The caller should verify that the query was successful (returned status "success").
   */
  private NumericQueryResult unpackNumericQueryResult(JSONObject rawApiResponse) {
    // Unpack singleton fields of the response.
    Double executionTime = convertToDouble(rawApiResponse.get("executionTime"));
    if (executionTime == null)
      executionTime = 0.0;

    // Create a NumericQueryResult object.
    NumericQueryResult result = new NumericQueryResult(executionTime);

    // Populate the values array.
    for (Object value : (JSONArray) rawApiResponse.get("values")) {
      result.values.add(convertToDouble(value));
    }

    return result;
  }

  /**
   * Retrieve the most common values of a field. For instance, you can find the most common URLs accessed on your
   * site, the most common user-agent strings, or the most common response codes returned. (If a very large number
   * of events match your search criteria, the results will be based on a random subsample of at least 500,000
   * matching events.)
   *
   * @param filter Specifies which log records to match, using the same syntax as the Expression field in the
   *     query UI. To match all log records, pass null or an empty string.
   * @param field Specifies which event field to retrieve.
   * @param maxCount The number of unique values to return. The most common values are returned, up to this limit.
   *   You may specify a value from 1 to 1000. If you pass null, the default (currently 100) is used.
   * @param startTime The beginning of the time range to query, using the same syntax as the query UI. You can
   *     also supply a simple timestamp, measured in seconds, milliseconds, or nanoseconds since 1/1/1970.
   * @param endTime The end of the time range to query, using the same syntax as the query UI. You can also
   *     supply a simple timestamp, measured in seconds, milliseconds, or nanoseconds since 1/1/1970.
   *
   * @throws ScalyrException if a low-level error occurs (e.g. network failure)
   * @throws ScalyrServerException if the Scalyr service returns an error
   */
  public FacetQueryResult facetQuery(String filter, String field, Integer maxCount, String startTime, String endTime)
      throws ScalyrException, ScalyrNetworkException {

    if (chunkSizeHours > 0)
      throw new RuntimeException("chunked facet queries not yet supported; use a non-chunking QueryService for facet queries");

    JSONObject parameters = new JSONObject();
    parameters.put("token", apiToken);
    parameters.put("queryType", "facet");

    if (filter != null)
      parameters.put("filter", filter);

    parameters.put("field", field);

    if (maxCount != null)
      parameters.put("maxCount", maxCount);

    if (startTime != null)
      parameters.put("startTime", startTime);

    if (endTime != null)
      parameters.put("endTime", endTime);

    JSONObject rawApiResponse = invokeApi("api/facetQuery", parameters);
    checkResponseStatus(rawApiResponse);
    return unpackFacetQueryResult(rawApiResponse);
  }

  /**
   * Given the raw server response to a facet query, encapsulate the query result in a FacetQueryResult.
   * The caller should verify that the query was successful (returned status "success").
   */
  private FacetQueryResult unpackFacetQueryResult(JSONObject rawApiResponse) {
    // Unpack singleton fields of the response.
    Double executionTime = convertToDouble(rawApiResponse.get("executionTime"));
    if (executionTime == null)
      executionTime = 0.0;

    Long matchCount = convertToLong(rawApiResponse.get("matchCount"));
    if (matchCount == null)
      matchCount = 0L;

    // Create a FacetQueryResult object.
    FacetQueryResult result = new FacetQueryResult(matchCount, executionTime);

    // Populate the values array.
    for (Object value : (JSONArray) rawApiResponse.get("values")) {
      result.values.add(new ValueAndCount((JSONObject) value));
    }

    return result;
  }

  /**
   * Retrieve numeric data from one or more timeseries, automatically creating them as a side effect if necessary.  A
   * timeseries precomputes a numeric query, allowing you to execute queries almost instantaneously, and without
   * exhausting your query execution limit.  This is especially useful if you are using the Scalyr API to feed a
   * home-built dashboard, alerting system, or other automated tool.
   *
   * When new timeseries are defined, they immediately capture data from this moment onward and our servers begin
   * a background process to backpropagate the timeseries over data we have already received.  The result is that,
   * within an hour of defining a new timeseries, you should be able to rapidly query for historical data as well
   * as new data.
   *
   * This method's parameters are similar to {@link #numericQuery}; with the one difference being that you may specify
   * multiple queries in a single request.
   *
   * @param queries The queries to execute.
   *
   * @throws ScalyrException if a low-level error occurs (e.g. network failure)
   * @throws ScalyrServerException if the Scalyr service returns an error
   */
  public TimeseriesQueryResult timeseriesQuery(TimeseriesQuerySpec[] queries)
      throws ScalyrException, ScalyrNetworkException {

    if (chunkSizeHours > 0)
      throw new RuntimeException("chunked timeseries queries not yet supported; use a non-chunking QueryService for timeseries queries");

    JSONObject parameters = new JSONObject();
    parameters.put("token", apiToken);

    JSONArray queriesJson = new JSONArray();
    parameters.put("queries", queriesJson);

    for (TimeseriesQuerySpec query : queries) {
      JSONObject queryJson = new JSONObject();
      if (query.filter   != null) { queryJson.put("filter", query.filter);     }
      if (query.function != null) { queryJson.put("function", query.function); }
      queryJson.put("startTime", query.startTime);
      queryJson.put("endTime", query.endTime);
      queryJson.put("buckets", query.buckets);

      queriesJson.add(queryJson);
    }

    JSONObject rawApiResponse = invokeApi("api/timeseriesQuery", parameters);
    checkResponseStatus(rawApiResponse);

    return unpackTimeseriesQueryResult(rawApiResponse);
  }

  /**
   * Parameters for a single timeseries query.
   */
  public static class TimeseriesQuerySpec {
    /**
     * The time range to query, using the same syntax as the query UI. You can also supply a simple timestamp,
     * measured in seconds, milliseconds, or nanoseconds since 1/1/1970. endTime is optional, defaulting to
     * the current time.
     */
    public String startTime, endTime;

    /**
     * Specifies which log records to match, using the same syntax as the Expression field in the
     * query UI. To match all log records, pass null or an empty string.
     */
    public String filter;

    /**
     * Specifies the value to compute from the matching events. Has the same meaning as for the numericQuery method.
     */
    public String function;

    /**
     * The number of numeric values to return. The time range is divided into this many equal slices.
     * You may specify a value from 1 to 5000.
     */
    public int buckets = 1;
  }

  /**
   * Given the raw server response to a timeseries query, encapsulate the query result in a TimeseriesQueryResult.
   * The caller should verify that the query was successful (returned status "success").
   */
  private TimeseriesQueryResult unpackTimeseriesQueryResult(JSONObject rawApiResponse) {
    // Unpack singleton fields of the response.
    Double executionTime = convertToDouble(rawApiResponse.get("executionTime"));
    if (executionTime == null)
      executionTime = 0.0;

    // Create a TimeseriesQueryResult object.
    TimeseriesQueryResult result = new TimeseriesQueryResult(executionTime);

    // Populate the values array.
    for (Object value : (JSONArray) rawApiResponse.get("results")) {
      result.values.add(unpackNumericQueryResult((JSONObject) value));
    }

    return result;
  }


  /**
   * Convert the given value to a Long. If the value is null, return null. If the value cannot be
   * converted to a Long (e.g. it is a non-numeric string), throw an exception.
   */
  private static Long convertToLong(Object value) {
    if (value instanceof Integer)
      return (long)(int)(Integer)value;
    else if (value instanceof Long)
      return (Long)value;
    else if (value instanceof Float)
      return (long)(float)(Float)value;
    else if (value instanceof Double)
      return (long)(double)(Double)value;
    else if (value == null)
      return null;
    else
      return Long.parseLong(value.toString());
  }

  /**
   * Convert the given value to a Double. If the value is null, return null. If the value cannot be
   * converted to a Double (e.g. it is a non-numeric string), throw an exception.
   */
  private static Double convertToDouble(Object value) {
    if (value instanceof Integer)
      return (double)(int)(Integer)value;
    else if (value instanceof Long)
      return (double)(long)(Long)value;
    else if (value instanceof Float)
      return (double)(float)(Float)value;
    else if (value instanceof Double)
      return (Double)value;
    else if (value == null)
      return null;
    else
      return Double.parseDouble(value.toString());
  }

  /**
   * If rawApiResponse does not have a "status" field with value "success", throw a
   * ScalyrServerException.
   */
  private void checkResponseStatus(JSONObject rawApiResponse) throws ScalyrServerException {
    Object responseStatus = rawApiResponse.get("status");
    if (!"success".equals(responseStatus)) {
      Object responseMessage = rawApiResponse.get("message");

      String exceptionMessage = responseStatus.toString();
      if (responseMessage != null)
        exceptionMessage += " (" + responseMessage + ")";

      throw new ScalyrServerException(exceptionMessage);
    }
  }

  /**
   * Encapsulates the result of executing a log query.
   */
  public static class LogQueryResult {
    /**
     * All log events which matched the query (up to the specified maxCount).
     */
    public final List<LogQueryMatch> matches = new ArrayList<LogQueryMatch>();

    /**
     * How much time the server spent processing this query, in milliseconds.
     */
    public final double executionTime;

    /**
     * This string may be passed to a subsequent query call, to retrieve additional matches. If there are
     * no additional matches, this may be null. If it is not null, there might or might not be additional
     * matches.
     */
    public final String continuationToken;

    public LogQueryResult(double executionTime, String continuationToken) {
      this.executionTime = executionTime;
      this.continuationToken = continuationToken;
    }


    /**
     * Merge `a` and `b` into a new result (whose `b` matches follow those of `a`, and using b's continuationToken).
     * Make sure to provide inputs in your desired order. May return one of its inputs if the other is null.
     */
    public static LogQueryResult merge(LogQueryResult a, LogQueryResult b) {
      if (a == null) return b;
      if (b == null) return a;
      LogQueryResult ret = new LogQueryResult(a.executionTime + b.executionTime, b.continuationToken);
      ret.matches.addAll(a.matches);
      ret.matches.addAll(b.matches);
      return ret;
    }




    @Override public String toString() {
      int matchCount = matches.size();

      StringBuilder sb = new StringBuilder();
      sb.append("{LogQueryResult: ");
      sb.append(matchCount + " match" + (matchCount != 1 ? "es" : "")
          + ", execution time " + executionTime + " ms");

      if (continuationToken != null)
        sb.append(", continuationToken [" + continuationToken + "]");

      for (LogQueryMatch match : matches) {
        sb.append("\n  ");
        sb.append(match.toString());
      }

      sb.append("\n}");
      return sb.toString();
    }
  }

  /**
   * Encapsulates an individual event in the result set of a log query.
   */
  public static class LogQueryMatch {
    /**
     * Timestamp when this event occurreed, in nanoseconds from 1/1/1970.
     */
    public final long timestamp;

    /**
     * The raw log message for this event. May be null for events which did not originate in a log file.
     */
    public final String message;

    public final Severity severity;

    /**
     * ID of the session within which this event was uploaded to the Scalyr server.
     */
    public final String sessionId;

    /**
     * Fields associated with the session. For logs uploaded by the Scalyr Agent, this will contain the
     * server attributes specified in the agent configuration file.
     */
    public final EventAttributes sessionFields;

    /**
     * ID of the thread which generated this event. Only meaningful for clients which associate a thread ID
     * with their events.
     */
    public final String threadId;

    /**
     * The fields of this event, including fields extracted by a log parser.
     */
    public final EventAttributes fields;

    public LogQueryMatch(long timestamp, String message, Severity severity, String sessionId, EventAttributes sessionFields,
                  String threadId, EventAttributes fields) {
      this.timestamp = timestamp;
      this.message = message;
      this.severity = severity;
      this.sessionId = sessionId;
      this.sessionFields = sessionFields;
      this.threadId = threadId;
      this.fields = fields;
    }

    @Override public String toString() {
      return "{timestamp " + timestamp + ": " + severity + " " + message + ", fields " + fields +
          ", thread " + threadId + ", session " + sessionId + " / " + sessionFields + "}";
    }
  }

  /**
   * Encapsulates the result of executing a numeric query.
   */
  public static class NumericQueryResult {
    /**
     * The requested numeric values, one for each bucket specified in the query. If a time bucket has
     * no events matching the query, the corresponding value will be null. (Some query functions, such as
     * sumPerSecond, return 0 instead of null when there are no events matching per query. Null is used
     * when the logical value of the query function is undefined.)
     */
    public final List<Double> values = new ArrayList<Double>();

    /**
     * How much time the server spent processing this query, in milliseconds.
     */
    public final double executionTime;

    public NumericQueryResult(double executionTime) {
      this.executionTime = executionTime;
    }

    /**
     * Merge `a` and `b` into a new result (whose `b` values follow those of `a`)
     * Make sure to provide inputs in your desired order. May return one of its inputs if the other is null.
     */
    public static NumericQueryResult merge(NumericQueryResult a, NumericQueryResult b) {
      if (a == null) return b;
      if (b == null) return a;
      NumericQueryResult ret = new NumericQueryResult(a.executionTime + b.executionTime);
      ret.values.addAll(a.values);
      ret.values.addAll(b.values);
      return ret;
    }


    @Override public String toString() {
      int valueCount = values.size();

      StringBuilder sb = new StringBuilder();
      sb.append("{NumericQueryResult: ");
      sb.append(valueCount + " value" + (valueCount != 1 ? "s" : "")
          + ", execution time " + executionTime + " ms, values [");

      for (int i = 0; i < values.size(); i++) {
        if (i > 0)
          sb.append(", ");

        sb.append(values.get(i));
      }

      sb.append("]}");
      return sb.toString();
    }
  }

  /**
   * Encapsulates the result of executing a facet query.
   */
  public static class FacetQueryResult {
    /**
     * Contains an entry for each unique value in the requested field, up to a maximum determined by
     * the maxCount parameter of the query. Values are sorted in order of decreasing count.
     */
    public final List<ValueAndCount> values = new ArrayList<ValueAndCount>();

    /**
     * The total number of events matching the query.
     */
    public final long matchCount;

    /**
     * How much time the server spent processing this query, in milliseconds.
     */
    public final double executionTime;

    FacetQueryResult(long matchCount, double executionTime) {
      this.matchCount = matchCount;
      this.executionTime = executionTime;
    }

    @Override public String toString() {
      int valueCount = values.size();

      StringBuilder sb = new StringBuilder();
      sb.append("{FacetQueryResult: ");
      sb.append(valueCount + " value" + (valueCount != 1 ? "s" : "")
          + ", " + matchCount + " matching event" + (matchCount != 1 ? "s" : "")
          + ", execution time " + executionTime + " ms, values [");

      for (int i = 0; i < values.size(); i++) {
        if (i > 0)
          sb.append(", ");

        sb.append(values.get(i));
      }

      sb.append("]}");
      return sb.toString();
    }
  }

  /**
   * Bundles a single value in a facet query result, with the number of matching events which had that
   * value.
   */
  public static class ValueAndCount {
    public final Object value;

    public final long count;

    public ValueAndCount(Object value, long count) {
      this.value = value;
      this.count = count;
    }

    public ValueAndCount(JSONObject json) {
      this(json.get("value"), convertToLong(json.get("count")));
    }

    @Override public String toString() {
      return "{" + value + ": " + count + "}";
    }
  }

  /**
   * Encapsulates the result of executing a timeseries query.
   */
  public static class TimeseriesQueryResult {
    /**
     * The requested numeric values, one for each query in the batch.
     */
    public final List<NumericQueryResult> values = new ArrayList<NumericQueryResult>();

    /**
     * How much time the server spent processing all queries in the batch, in milliseconds.
     */
    public final double executionTime;

    TimeseriesQueryResult(double executionTime) {
      this.executionTime = executionTime;
    }

    @Override public String toString() {
      int valueCount = values.size();

      StringBuilder sb = new StringBuilder();
      sb.append("{TimeseriesQueryResult: ");
      sb.append(valueCount + " value" + (valueCount != 1 ? "s" : "")
          + ", execution time " + executionTime + " ms, values [");

      for (int i = 0; i < values.size(); i++) {
        if (i > 0)
          sb.append(", ");

        sb.append(values.get(i));
      }

      sb.append("]}");
      return sb.toString();
    }
  }


  /**
   * Quick, hacky cmd line interface; first arg is the name of the query method and
   * subsequent args are 1-for-1 positional parameters for that method.  Provide '-' for
   * optional parameters you wish to omit.
   */
  public static void main(String[] args) {
    final String apiToken = System.getenv("scalyr_readlog_token");
    final String chunkSizeHours = System.getenv("scalyr_chunksize_hours");

    if (apiToken == null)
      printUsageAndExit("ERROR: must specify 'scalyr_readlog_token'");

    if (args.length < 1 || args[0] == null || args[0].matches("--?(\\?|help)"))
      printUsageAndExit();

    String method = args[0];
    if (method == null || !method.matches("^(facet|numeric|log)Query$"))
      printUsageAndExit("ERROR: unrecognized method '" + method + "'");

    try {
      QueryService svc = new QueryService(apiToken, chunkSizeHours == null ? 0 : Integer.parseInt(chunkSizeHours));

      // 'parse' args, so hacky
      String filter = null, function = null, field = null, startTime = null, endTime = null, columns = null, continuationToken = null;
      PageMode pageMode = PageMode.head;
      Integer maxCount = null, buckets = null;

      int i    = 1;
      filter   = args[i++];
      if (method.equals("numericQuery")) function = args[i++];
      if (method.equals("facetQuery"))   field    = args[i++];
      if (method.equals("facetQuery"))   maxCount = Integer.parseInt(args[i++]); // awesome that this comes before start/end time for facetQuery
      startTime = args[i++];
      endTime   = args[i++];
      if (method.equals("numericQuery")) buckets  = Integer.parseInt(args[i++]);
      if (method.equals("logQuery"))     maxCount = Integer.parseInt(args[i++]);
      if (method.equals("logQuery"))     pageMode = Enum.valueOf(PageMode.class, args[i++]);
      if (method.equals("logQuery") && args.length > i) columns = args[i++];
      if (method.equals("logQuery") && args.length > i) continuationToken = args[i++];

      if ("-".equals(filter))            filter              = null;
      if ("-".equals(function))          function            = null;
      if ("-".equals(field))             field               = null;
      if ("-".equals(startTime))         startTime           = null;
      if ("-".equals(endTime))           endTime             = null;
      if ("-".equals(columns))           columns             = null;
      if ("-".equals(continuationToken)) continuationToken   = null;

      if (method.equals("logQuery"))     dump(    svc.logQuery(filter, startTime, endTime, maxCount, pageMode, columns, continuationToken));
      if (method.equals("facetQuery"))   dump(  svc.facetQuery(filter, field, maxCount, startTime, endTime));
      if (method.equals("numericQuery")) dump(svc.numericQuery(filter, function, startTime, endTime, buckets));
    } catch (Exception e) {
      printUsageAndExit(e.getMessage());
    }
  }

  // TODO: make these prettier
  static void dump(LogQueryResult r)     { System.out.println(r.toString()); }
  static void dump(FacetQueryResult r)   { System.out.println(r.toString()); }
  static void dump(NumericQueryResult r) { System.out.println(r.toString()); }

  static void printUsageAndExit(String err) {
    System.err.println(err);
    System.err.println("");
    printUsageAndExit();
  }

  static void printUsageAndExit() {
    System.err.println("Usage: java com.scalyr.api.query.QueryService <methodName> <methodArg1> <methodArg2>...");
    System.err.println("       First arg is the name of the query method and subsequent args are 1-for-1 positional");
    System.err.println("       parameters for that method.  Provide '-' for optional parameters you wish to omit");
    System.err.println("");
    System.err.println("   eg: ..QueryService logQuery someText 4h 0m 100 tail - -");
    System.err.println("");
    System.err.println("Quitting");
    System.exit(1);
  }

}
