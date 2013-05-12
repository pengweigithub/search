/**
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
package org.apache.solr.hadoop.morphline;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.hadoop.HeartBeater;
import org.apache.solr.hadoop.SolrInputDocumentWritable;
import org.apache.solr.hadoop.SolrMapper;
import org.apache.solr.morphline.DocumentLoader;
import org.apache.solr.schema.IndexSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

/**
 * This class takes the input files, extracts the relevant content, transforms
 * it and hands SolrInputDocuments to a set of reducers.
 * 
 * More specifically, it consumes a list of <offset, hdfsFilePath> input pairs.
 * For each such pair extracts a set of zero or more SolrInputDocuments and
 * sends them to a downstream Reducer. The key for the reducer is the unique id
 * of the SolrInputDocument specified in Solr schema.xml.
 */
public class MorphlineMapper extends SolrMapper<LongWritable, Text> {

  private Context context;
  private MorphlineMapRunner runner;
  private HeartBeater heartBeater;
  
  private static final Logger LOG = LoggerFactory.getLogger(MorphlineMapper.class);
  
  protected IndexSchema getSchema() {
    return runner.getSchema();
  }

  protected Context getContext() {
    return context;
  }

  @Override
  protected void setup(Context context) throws IOException, InterruptedException {
    super.setup(context);
    this.context = context;
    heartBeater = new HeartBeater(context);
    this.runner = new MorphlineMapRunner(
        context.getConfiguration(), new MyDocumentLoader(), getSolrHomeDir().toString());
  }

  /**
   * Extract content from the path specified in the value. Key is useless.
   */
  @Override
  public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
    heartBeater.needHeartBeat();
    try {
      runner.map(value.toString(), context.getConfiguration(), context);
    } finally {
      heartBeater.cancelHeartBeat();
    }
  }
  
  @Override
  protected void cleanup(Context context) throws IOException, InterruptedException {
    heartBeater.close();
    runner.cleanup();
    addMetricsToMRCounters(runner.getMorphlineContext().getMetricRegistry(), context);
    super.cleanup(context);
  }

  private void addMetricsToMRCounters(MetricRegistry metricRegistry, Context context) {
    for (Map.Entry<String, Counter> entry : metricRegistry.getCounters().entrySet()) {
      String metricName = entry.getKey();
      context.getCounter("morphline", metricName).increment(entry.getValue().getCount());
    }
    for (Map.Entry<String, Timer> entry : metricRegistry.getTimers().entrySet()) {
      String metricName = entry.getKey();
      long nanosPerSec = 1000 * 1000 * 1000L;
      context.getCounter("morphline", metricName).increment(entry.getValue().getCount() / nanosPerSec);
    }
  }
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  private final class MyDocumentLoader implements DocumentLoader {

    @Override
    public void beginTransaction() {
    }

    @Override
    public void load(SolrInputDocument doc) throws IOException, SolrServerException {
      String uniqueKeyFieldName = getSchema().getUniqueKeyField().getName();
      String id = doc.getFieldValue(uniqueKeyFieldName).toString();
      try {
        context.write(new Text(id), new SolrInputDocumentWritable(doc));
      } catch (InterruptedException e) {
        throw new IOException("Interrupted while writing " + doc, e);
      }

      if (LOG.isDebugEnabled()) {
        long numParserOutputBytes = 0;
        for (SolrInputField field : doc.values()) {
          numParserOutputBytes += sizeOf(field.getValue());
        }
        context.getCounter(MorphlineCounters.class.getName(), MorphlineCounters.PARSER_OUTPUT_BYTES.toString()).increment(numParserOutputBytes);
      }
      context.getCounter(MorphlineCounters.class.getName(), MorphlineCounters.DOCS_READ.toString()).increment(1);
    }

    // just an approximation
    private long sizeOf(Object value) {
      if (value instanceof CharSequence) {
        return ((CharSequence) value).length();
      } else if (value instanceof Integer) {
        return 4;
      } else if (value instanceof Long) {
        return 8;
      } else if (value instanceof Collection) {
        long size = 0;
        for (Object val : (Collection) value) {
          size += sizeOf(val);
        }
        return size;      
      } else {
        return String.valueOf(value).length();
      }
    }

    @Override
    public void commitTransaction() {
    }

    @Override
    public UpdateResponse rollbackTransaction() throws SolrServerException, IOException {
      return new UpdateResponse();
    }

    @Override
    public void shutdown() {
    }

    @Override
    public SolrPingResponse ping() throws SolrServerException, IOException {
      return new SolrPingResponse();
    }

  }

}
