/**
 * Copyright 2013 Cloudera Inc.
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
package com.cloudera.cdk.morphline.cmd;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.TimeZone;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.Configs;
import com.cloudera.cdk.morphline.api.Fields;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.MorphlineParsingException;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.base.AbstractCommand;
import com.typesafe.config.Config;

/**
 * Command that converts the timestamps in a given field from one of a set of input date formats (in
 * an input timezone) to an output date format (in an output timezone), while respecting daylight
 * savings time rules. Provides reasonable defaults for common use cases.
 */
public final class ConvertTimestampBuilder implements CommandBuilder {

  @Override
  public Collection<String> getNames() {
    return Collections.singletonList("convertTimestamp");
  }

  @Override
  public Command build(Config config, Command parent, Command child, MorphlineContext context) {
    return new ConvertTimestamp(config, parent, child, context);
  }
  
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  private static final class ConvertTimestamp extends AbstractCommand {

    private final String fieldName;
    private final List<SimpleDateFormat> inputFormats = new ArrayList();
    private final SimpleDateFormat outputFormat;
    
    static {
      DateUtil.DEFAULT_DATE_FORMATS.add(0, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"); 
    }    
    
    public ConvertTimestamp(Config config, Command parent, Command child, MorphlineContext context) {
      super(config, parent, child, context);
      
      this.fieldName = Configs.getString(config, "field", Fields.TIMESTAMP);
      TimeZone inputTimeZone = getTimeZone(Configs.getString(config, "inputTimezone", "UTC"));
      Locale inputLocale = getLocale(Configs.getString(config, "inputLocale", ""));
      for (String inputFormat : Configs.getStringList(config, "inputFormats", DateUtil.DEFAULT_DATE_FORMATS)) {
        SimpleDateFormat df = new SimpleDateFormat(inputFormat, inputLocale);
        df.setTimeZone(inputTimeZone);
        df.set2DigitYearStart(DateUtil.DEFAULT_TWO_DIGIT_YEAR_START);
        this.inputFormats.add(df);
      }
      TimeZone outputTimeZone = getTimeZone(Configs.getString(config, "outputTimezone", "UTC"));
      Locale outputLocale = getLocale(Configs.getString(config, "outputLocale", ""));
      String outputFormatStr = Configs.getString(config, "outputFormat");
      this.outputFormat = new SimpleDateFormat(outputFormatStr, outputLocale);
      this.outputFormat.setTimeZone(outputTimeZone);
    }
        
    @Override
    public boolean process(Record record) {
      ParsePosition pos = new ParsePosition(0);
      ListIterator iter = record.get(fieldName).listIterator();
      while (iter.hasNext()) {
        String timestamp = iter.next().toString();
        boolean foundMatchingFormat = false;
        for (SimpleDateFormat inputFormat : inputFormats) {
          pos.setIndex(0);
          Date date = inputFormat.parse(timestamp, pos);
          if (date != null && pos.getIndex() == timestamp.length()) {
            String result = outputFormat.format(date);
            iter.set(result);
            foundMatchingFormat = true;
            break;
          }
        }
        if (!foundMatchingFormat) {
          //LOG.debug("Cannot parse timestamp: " + timestamp + " with one of these inputFormats: " + inputFormats);
          return false;
        }
      }
      return super.process(record);
    }
    
    private TimeZone getTimeZone(String timeZoneID) {
      if (!Arrays.asList(TimeZone.getAvailableIDs()).contains(timeZoneID)) {
        throw new MorphlineParsingException("Unknown timezone: " + timeZoneID, getConfig());
      }
      return TimeZone.getTimeZone(timeZoneID);
    }
    
    private Locale getLocale(String name) {
      for (Locale locale : Locale.getAvailableLocales()) {
        if (locale.toString().equals(name)) {
          return locale;
        }
      }
      assert Locale.ROOT.toString().equals("");
      if (name.equals(Locale.ROOT.toString())) {
        return Locale.ROOT;
      }
      throw new MorphlineParsingException("Unknown locale: " + name, getConfig());
    }
    
    
    ///////////////////////////////////////////////////////////////////////////////
    // Nested classes:
    ///////////////////////////////////////////////////////////////////////////////
    /*
     * Licensed to the Apache Software Foundation (ASF) under one or more
     * contributor license agreements.  See the NOTICE file distributed with
     * this work for additional information regarding copyright ownership.
     * The ASF licenses this file to You under the Apache License, Version 2.0
     * (the "License"); you may not use this file except in compliance with
     * the License.  You may obtain a copy of the License at
     *
     *     http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */
    /**
     * This class has some code from HttpClient DateUtil and Solrj DateUtil.
     */
    private static final class DateUtil {
      //start HttpClient
      /**
       * Date format pattern used to parse HTTP date headers in RFC 1123 format.
       */
      public static final String PATTERN_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

      /**
       * Date format pattern used to parse HTTP date headers in RFC 1036 format.
       */
      public static final String PATTERN_RFC1036 = "EEEE, dd-MMM-yy HH:mm:ss zzz";

      /**
       * Date format pattern used to parse HTTP date headers in ANSI C
       * <code>asctime()</code> format.
       */
      public static final String PATTERN_ASCTIME = "EEE MMM d HH:mm:ss yyyy";
      //These are included for back compat
      private static final Collection<String> DEFAULT_HTTP_CLIENT_PATTERNS = Arrays.asList(
              PATTERN_ASCTIME, PATTERN_RFC1036, PATTERN_RFC1123);

      private static final Date DEFAULT_TWO_DIGIT_YEAR_START;

      static {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.ROOT);
        calendar.set(2000, Calendar.JANUARY, 1, 0, 0);
        DEFAULT_TWO_DIGIT_YEAR_START = calendar.getTime();
      }

//      private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

      //end HttpClient

      //---------------------------------------------------------------------------------------

      /**
       * A suite of default date formats that can be parsed, and thus transformed to the Solr specific format
       */
      public static final List<String> DEFAULT_DATE_FORMATS = new ArrayList<String>();

      static {
        DEFAULT_DATE_FORMATS.add("yyyy-MM-dd'T'HH:mm:ss'Z'");
        DEFAULT_DATE_FORMATS.add("yyyy-MM-dd'T'HH:mm:ss");
        DEFAULT_DATE_FORMATS.add("yyyy-MM-dd");
        DEFAULT_DATE_FORMATS.add("yyyy-MM-dd hh:mm:ss");
        DEFAULT_DATE_FORMATS.add("yyyy-MM-dd HH:mm:ss");
        DEFAULT_DATE_FORMATS.add("EEE MMM d hh:mm:ss z yyyy");
        DEFAULT_DATE_FORMATS.addAll(DateUtil.DEFAULT_HTTP_CLIENT_PATTERNS);
      }

    }
  }
  
}