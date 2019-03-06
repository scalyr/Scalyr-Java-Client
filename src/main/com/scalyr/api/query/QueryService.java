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
import com.scalyr.api.internal.ScalyrService;
import com.scalyr.api.json.JSONArray;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.logs.EventAttributes;
import com.scalyr.api.logs.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates the raw HTTP-level API to the log query service.
 */
public class QueryService extends ScalyrService {
  /**
   * Construct a QueryService.
   *
   * @param apiToken The API authorization token to use when communicating with the server. Should
   *     be a "Read Logs" token.
   */
  public QueryService(String apiToken) {
    super(apiToken);
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
   * If you will be be invoking the same query repeatedly, you may want to create a timeseries for the query. This is
   * especially useful if you are using the Scalyr API to feed a home-built dashboard, alerting system, or other
   * automated tool. A timeseries precomputes a numeric query, allowing you to execute queries almost instantaneously,
   * and without exhausting your query execution limit. Use the createTimeseries method to create a timeseries, and
   * timeseriesQuery to execute queries using a timeseries.
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
   * Retrieve numeric data from a previously defined timeseries. This method is similar to numericQuery(), but relies on
   * parameters defined by a previous call to createTimeseries, and usually executes in a few milliseconds (plus network
   * latency of course). The timeseriesQuery method also allows you to execute multiple queries in a single request.
   *
   * @param queries The queries to execute.
   *
   * @throws ScalyrException if a low-level error occurs (e.g. network failure)
   * @throws ScalyrServerException if the Scalyr service returns an error
   */
  public TimeseriesQueryResult timeseriesQuery(TimeseriesQuerySpec[] queries)
      throws ScalyrException, ScalyrNetworkException {
    JSONObject parameters = new JSONObject();
    parameters.put("token", apiToken);

    JSONArray queriesJson = new JSONArray();
    parameters.put("queries", queriesJson);

    for (TimeseriesQuerySpec query : queries) {
      JSONObject queryJson = new JSONObject();
      if (query.timeseriesId != null) { // Existence of a timeseriesID takes precedence, although it will be deprecated eventually.
        queryJson.put("timeseriesId", query.timeseriesId);
      } else { // If using new API (no timeseriesID), use filter and function.
        if (query.filter   != null) { queryJson.put("filter", query.filter);     }
        if (query.function != null) { queryJson.put("function", query.function); }
      }
      queryJson.put("startTime", query.startTime);
      queryJson.put("endTime"  , query.endTime  );
      queryJson.put("buckets"  , query.buckets  );

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
    /** This field is deprecated. Use 'filter' and 'function' instead. */
    @Deprecated
    public String timeseriesId;

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
   * Deprecated. See git history for javadoc.
   */
  @Deprecated
  public CreateTimeseriesResult createTimeseries(String filter, String function)
      throws ScalyrException, ScalyrNetworkException {
    JSONObject parameters = new JSONObject();
    parameters.put("token", apiToken);
    parameters.put("queryType", "numeric");

    if (filter != null)
      parameters.put("filter", filter);

    if (function != null)
      parameters.put("function", function);

    JSONObject rawApiResponse = invokeApi("api/createTimeseries", parameters);
    checkResponseStatus(rawApiResponse);
    return unpackCreateTimeseriesResult(rawApiResponse);
  }

  /**
   * Deprecated helper method for createTimeSeries().
   */
  private CreateTimeseriesResult unpackCreateTimeseriesResult(JSONObject rawApiResponse) {
    String timeseriesId = rawApiResponse.get("timeseriesId").toString();

    return new CreateTimeseriesResult(timeseriesId);
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

    LogQueryResult(double executionTime, String continuationToken) {
      this.executionTime = executionTime;
      this.continuationToken = continuationToken;
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

    LogQueryMatch(long timestamp, String message, Severity severity, String sessionId, EventAttributes sessionFields,
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

    NumericQueryResult(double executionTime) {
      this.executionTime = executionTime;
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
   * Encapsulates the result of creating a timeseries.
   */
  public static class CreateTimeseriesResult {
    /**
     * The ID of the newly created timeseries. Use this value when calling timeseriesQuery.
     */
    public final String timeseriesId;

    public CreateTimeseriesResult(String timeseriesId) {
      this.timeseriesId = timeseriesId;
    }

    @Override public String toString() {
      return "{CreateTimeseriesResult: timeseriesId=" + timeseriesId + "}";
    }
  }
}
