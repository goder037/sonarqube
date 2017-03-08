/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.sonar.process.ProcessId;
import org.sonar.process.ProcessProperties;
import org.sonar.process.Props;

import static java.lang.String.format;
import static org.sonar.process.ProcessProperties.CLUSTER_ENABLED;
import static org.sonar.process.ProcessProperties.PATH_DATA;
import static org.sonar.process.ProcessProperties.PATH_HOME;
import static org.sonar.process.ProcessProperties.PATH_LOGS;
import static org.sonar.process.ProcessProperties.PATH_TEMP;
import static org.sonar.process.ProcessProperties.PATH_WEB;

public class AppSettingsImpl implements AppSettings {
  
  private final Properties commandLineArguments;
  private Props allProps;

  private AppSettingsImpl(Properties commandLineArguments, Props allProps) {
    this.commandLineArguments = commandLineArguments;
    this.allProps = allProps;
  }

  public static AppSettingsImpl forCliArguments(Properties cliArguments) {
    PropsBuilder propsBuilder = new PropsBuilder(cliArguments, new JdbcSettings());
    return new AppSettingsImpl(cliArguments, propsBuilder.build());
  }

  @Override
  public Props getProps() {
    return allProps;
  }

  @Override
  public Optional<String> getValue(String key) {
    return Optional.ofNullable(allProps.value(key));
  }

  @Override
  public boolean isClusterEnabled() {
    return allProps.valueAsBoolean(CLUSTER_ENABLED, false);
  }

  @Override
  public List<ProcessId> getEnabledProcesses() {
    List<ProcessId> enabled = new ArrayList<>();
    for (ProcessId processId : ProcessId.values()) {
      switch (processId) {
        case APP:
          // this is the current process, ignore
          break;
        case ELASTICSEARCH:
          if (isProcessEnabled(ProcessProperties.CLUSTER_SEARCH_DISABLED)) {
            enabled.add(processId);
          }
          break;
        case WEB_SERVER:
          if (isProcessEnabled(ProcessProperties.CLUSTER_WEB_DISABLED)) {
            enabled.add(processId);
          }
          break;
        case COMPUTE_ENGINE:
          if (isProcessEnabled(ProcessProperties.CLUSTER_CE_DISABLED)) {
            enabled.add(processId);
          }
          break;
        default:
          // defensive safeguard
          throw new IllegalArgumentException("Unsupported process: " + processId);
      }
    }
    if (enabled.isEmpty()) {
      throw new IllegalArgumentException("At least one process is required. All Elasticsearch, Compute Engine and Web Server have been disabled.");
    }
    return enabled;
  }

  private boolean isProcessEnabled(String disabledPropertyKey) {
    return !allProps.valueAsBoolean(ProcessProperties.CLUSTER_ENABLED) || !allProps.valueAsBoolean(disabledPropertyKey);
  }

  @Override
  public void reload() {
    AppSettingsImpl reloaded = forCliArguments(commandLineArguments);
    ensureUnchangedProperties(reloaded.allProps);
    this.allProps = reloaded.allProps;
  }

  private void ensureUnchangedProperties(Props newProps) {
    verifyUnchanged(newProps,
      PATH_HOME,
      PATH_DATA,
      PATH_WEB,
      PATH_LOGS,
      PATH_TEMP,
      CLUSTER_ENABLED);
  }

  private void verifyUnchanged(Props newProps, String... keys) {
    for (String key : keys) {
      String initialValue = allProps.value(key);
      String newValue = newProps.value(key);
      if (!Objects.equals(newValue, initialValue)) {
        throw new IllegalStateException(format("Change of property '%s' is not supported on restart ('%s'=> '%s')", key, initialValue, newValue));
      }
    }
  }
}
