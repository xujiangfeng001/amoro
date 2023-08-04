/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package com.netease.arctic.optimizer;

import com.google.common.collect.Maps;
import com.netease.arctic.ams.api.resource.Resource;
import com.netease.arctic.optimizer.flink.FlinkOptimizer;
import com.netease.arctic.optimizer.util.PropertyUtil;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FlinkOptimizerContainer extends AbstractResourceContainer {
  private static final Logger LOG = LoggerFactory.getLogger(FlinkOptimizerContainer.class);

  public static final String FLINK_HOME_PROPERTY = "flink-home";
  public static final String FLINK_CONFIG_PATH = "/conf/flink-conf.yaml";
  public static final String TASK_MANAGER_MEMORY_PROPERTY = "taskmanager.memory";
  public static final String JOB_MANAGER_MEMORY_PROPERTY = "jobmanager.memory";
  public static final String JOB_MANAGER_TOTAL_PROCESS_MEMORY = "jobmanager.memory.process.size";
  public static final String TASK_MANAGER_TOTAL_PROCESS_MEMORY = "taskmanager.memory.process.size";
  public static final String YARN_APPLICATION_ID_PROPERTY = "yarn-application-id";

  private static final Pattern APPLICATION_ID_PATTERN = Pattern.compile("(.*)application_(\\d+)_(\\d+)");
  private static final int MAX_READ_APP_ID_TIME = 600000; //10 min


  public String getFlinkHome() {
    String flinkHome = PropertyUtil.checkAndGetProperty(getContainerProperties(), FLINK_HOME_PROPERTY);
    return  flinkHome != null ? flinkHome.replaceAll("/$", "") : null;
  }

  @Override
  protected Map<String, String> doScaleOut(String startUpArgs) {
    Runtime runtime = Runtime.getRuntime();
    try {
      String exportCmd = String.join(" && ", exportSystemProperties());
      String startUpCmd = String.format("%s && %s", exportCmd, startUpArgs);
      String[] cmd = {"/bin/sh", "-c", startUpCmd};
      LOG.info("Starting flink optimizer using command : {}", startUpCmd);
      Process exec = runtime.exec(cmd);
      Map<String, String> startUpStatesMap = Maps.newHashMap();
      String applicationId = readApplicationId(exec);
      if (applicationId != null) {
        startUpStatesMap.put(YARN_APPLICATION_ID_PROPERTY, applicationId);
      }
      return startUpStatesMap;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to scale out flink optimizer.", e);
    }
  }

  @Override
  protected String buildOptimizerStartupArgsString(Resource resource) {
    Map<String, String> properties = resource.getProperties();
    Map<String, String> flinkConfig = loadFlinkConfig();
    Preconditions.checkState(properties != null && !flinkConfig.isEmpty(),
        "resource properties is null or load flink-conf.yaml failed");
    Long jobManagerMemory = getMemorySizeValue(properties, flinkConfig, JOB_MANAGER_MEMORY_PROPERTY,
        JOB_MANAGER_TOTAL_PROCESS_MEMORY);
    Long taskManagerMemory = getMemorySizeValue(properties, flinkConfig, TASK_MANAGER_MEMORY_PROPERTY,
        TASK_MANAGER_TOTAL_PROCESS_MEMORY);
    String jobPath = getAMSHome() + "/plugin/optimize/OptimizeJob.jar";
    long memory = jobManagerMemory + taskManagerMemory * resource.getThreadCount();
    return String.format("%s/bin/flink run -m yarn-cluster -ytm %s -yjm %s %s -c %s %s -m %s %s",
          getFlinkHome(), taskManagerMemory, jobManagerMemory, buildFlinkArgs(properties),
          FlinkOptimizer.class.getName(), jobPath, memory,
          super.buildOptimizerStartupArgsString(resource));
  }

  private Map<String, String> loadFlinkConfig() {
    try {
      return new Yaml().load(Files.newInputStream(Paths.get(getFlinkHome() + FLINK_CONFIG_PATH)));
    } catch (IOException e) {
      LOG.error("load flink conf yaml failed: {}", e.getMessage());
      return Collections.emptyMap();
    }
  }

  /**
   * get jobManager and taskManager memory.
   * An example of using Jobmanager memory parameters is as follows:
   *    jobmanager.memory: 1024
   *    jobmanager.memory.process.size: 1024M
   *    flink-conf.yaml
   * Prioritize from high to low.
   *
   */
  protected Long getMemorySizeValue(Map<String, String> properties, Map<String, String> flinkConfig,
                                  String propertyKey, String finkConfigKey) {
    return Optional.ofNullable(properties.get(propertyKey))
        .map(Long::valueOf)
        .orElseGet(() -> Optional.ofNullable(properties.get(finkConfigKey))
            .map((v) -> parseMemorySize(v))
            .orElseGet(() -> Optional.ofNullable(flinkConfig.get(finkConfigKey))
                .map((v) -> parseMemorySize(v))
                .orElse(0L)));
  }

  /**
   * build user configured flink native parameters
   */
  protected String buildFlinkArgs(Map<String, String> properties) {
    final String[] keysToFilter = {
        JOB_MANAGER_MEMORY_PROPERTY,
        TASK_MANAGER_MEMORY_PROPERTY,
        JOB_MANAGER_TOTAL_PROCESS_MEMORY,
        TASK_MANAGER_TOTAL_PROCESS_MEMORY
    };
    return properties.entrySet()
        .stream().filter(entry -> !containsKey(keysToFilter, entry.getKey()))
        .map(entry -> "-yD " + entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(" "));
  }

  private boolean containsKey(String[] keys, String key) {
    for (String k : keys) {
      if (k.equals(key)) {
        return true;
      }
    }
    return false;
  }

  /**
   * memory conversion of units method, supporting m and g units
   */
  public long parseMemorySize(String memoryStr) {
    if (memoryStr == null || memoryStr.isEmpty()) {
      return 0;
    }
    memoryStr = memoryStr.toLowerCase().trim().replaceAll("\\s", "");
    Matcher matcher = Pattern.compile("(\\d+)([mg])").matcher(memoryStr);
    if (matcher.matches()) {
      long size = Long.parseLong(matcher.group(1));
      String unit = matcher.group(2);
      switch (unit) {
        case "m":
          return size;
        case "g":
          return size * 1024;
        default:
          LOG.error("Invalid memory size unit: {}, Please use m or g as the unit", unit);
          return 0;
      }
    } else {
      try {
        return Long.parseLong(memoryStr);
      } catch (NumberFormatException e) {
        LOG.error("Invalid memory size format: {}", memoryStr);
        return 0;
      }
    }
  }

  private String readApplicationId(Process exec) {
    StringBuilder outputBuilder = new StringBuilder();
    String applicationId = null;
    try (InputStreamReader inputStreamReader = new InputStreamReader(exec.getInputStream())) {
      try (BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < MAX_READ_APP_ID_TIME) {
          String readLine = bufferedReader.readLine();
          if (readLine == null) {
            break;
          }
          outputBuilder.append(readLine).append("\n");
          Matcher matcher = APPLICATION_ID_PATTERN.matcher(readLine);
          if (matcher.matches()) {
            applicationId = String.format("application_%s_%s", matcher.group(2), matcher.group(3));
            break;
          }
        }
        LOG.info("Started flink optimizer log:\n{}", outputBuilder);
        return applicationId;
      }
    } catch (IOException e) {
      LOG.error("Read application id from output failed", e);
      return null;
    }
  }

  @Override
  public void releaseOptimizer(Resource resource) {
    Preconditions.checkArgument(resource.getProperties().containsKey(YARN_APPLICATION_ID_PROPERTY),
        "Cannot find {} from optimizer start up stats", YARN_APPLICATION_ID_PROPERTY);
    Preconditions.checkArgument(resource.getProperties().containsKey(FlinkOptimizer.JOB_ID_PROPERTY),
        "Cannot find {} from optimizer properties", FlinkOptimizer.JOB_ID_PROPERTY);
    String applicationId = resource.getProperties().get(YARN_APPLICATION_ID_PROPERTY);
    String jobId = resource.getProperties().get(FlinkOptimizer.JOB_ID_PROPERTY);
    try {
      String exportCmd = String.join(" && ", exportSystemProperties());
      String releaseCmd = String.format("%s && %s/bin/flink cancel -t yarn-per-job -Dyarn.application.id=%s %s",
          exportCmd, getFlinkHome(), applicationId, jobId);
      String[] cmd = {"/bin/sh", "-c", releaseCmd};
      LOG.info("Releasing flink optimizer using command:" + releaseCmd);
      Runtime.getRuntime().exec(cmd);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to release flink optimizer.", e);
    }
  }
}
