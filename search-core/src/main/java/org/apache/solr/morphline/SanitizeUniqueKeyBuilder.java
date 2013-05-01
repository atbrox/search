/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.solr.morphline;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;

import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.Configs;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.base.AbstractCommand;
import com.cloudera.cdk.morphline.base.Fields;
import com.cloudera.cdk.morphline.base.Notifications;
import com.typesafe.config.Config;

/**
 * A command that assigns a record unique key that is the concatenation of the given
 * <code>baseIdField</code> record field, followed by a running count of the record number within
 * the current session. The count is reset to zero whenever a "startSession" notification is
 * received.
 * <p>
 * For example, assume a CSV file containing multiple records, and the <code>baseIdField</code>
 * field is the filesystem path of the file. Now this command can be used to assign the following
 * record values to Solr's unique key field: <code>$path#0, $path#1, ... $path#N</code>.
 * <p>
 * The name of the unique key field is fetched from Solr's schema.xml file, as directed by the
 * <code>solrLocator</code> configuration parameter.
 */
public final class SanitizeUniqueKeyBuilder implements CommandBuilder {

  @Override
  public Collection<String> getNames() {
    return Collections.singletonList("sanitizeUniqueKey");
  }

  @Override
  public Command build(Config config, Command parent, Command child, MorphlineContext context) {
    return new SanitizeUniqueKey(config, parent, child, context);
  }
  
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  private static final class SanitizeUniqueKey extends AbstractCommand {
    
    private final String baseIdFieldName;
    private final String uniqueKeyName;
    private long recordCounter = 0;
  
    private final String idPrefix; // for load testing only; enables adding same document many times with a different unique key
    private final Random randomIdPrefix; // for load testing only; enables adding same document many times with a different unique key

    public SanitizeUniqueKey(Config config, Command parent, Command child, MorphlineContext context) {
      super(config, parent, child, context);
      this.baseIdFieldName = Configs.getString(config, "baseIdField", Fields.ID);
      
      Config solrLocatorConfig = Configs.getConfig(config, "solrLocator");
      SolrLocator locator = new SolrLocator(solrLocatorConfig, context);
      LOG.debug("solrLocator: {}", locator);
      IndexSchema schema = locator.getIndexSchema();
      SchemaField uniqueKey = schema.getUniqueKeyField();
      uniqueKeyName = uniqueKey == null ? null : uniqueKey.getName();
      
      String tmpIdPrefix = null;
      Random tmpRandomIdPrefx = null;
      if (config.hasPath("idPrefix")) { // for load testing only
        tmpIdPrefix = config.getString("idPrefix");
      }
      if ("random".equals(tmpIdPrefix)) { // for load testing only
        tmpRandomIdPrefx = new Random(new SecureRandom().nextLong());    
        tmpIdPrefix = null;
      }
      idPrefix = tmpIdPrefix;
      randomIdPrefix = tmpRandomIdPrefx;
    }

    @Override
    public boolean process(Record doc) {      
      long num = recordCounter++;
      // LOG.debug("record #{} id before sanitizing doc: {}", num, doc);
      if (uniqueKeyName != null && !doc.getFields().containsKey(uniqueKeyName)) {
        Object baseId = doc.getFirstValue(baseIdFieldName);
        if (baseId == null) {
          throw new IllegalStateException("Record field " + baseIdFieldName
              + " must not be null as it is needed as a basis for a unique key for solr doc: " + doc);
        }
        doc.replaceValues(uniqueKeyName, baseId.toString() + "#" + num);          
      }
      
      // for load testing only; enables adding same document many times with a different unique key
      if (idPrefix != null) { 
        String id = doc.getFirstValue(uniqueKeyName).toString();
        id = idPrefix + id;
        doc.replaceValues(uniqueKeyName, id);
      } else if (randomIdPrefix != null) {
        String id = doc.getFirstValue(uniqueKeyName).toString();
        id = String.valueOf(Math.abs(randomIdPrefix.nextInt())) + "#" + id;
        doc.replaceValues(uniqueKeyName, id);
      }

      LOG.debug("record #{} id sanitized to this: {}", num, doc);
      
      return super.process(doc);
    }
    
    @Override
    public void notify(Record notification) {
      if (Notifications.contains(notification, Notifications.LifeCycleEvent.startSession)) {
        recordCounter = 0; // reset
      }
      super.notify(notification);
    }

  }
}
