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

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.sonar.application.*;
import org.sonar.application.PropsBuilder;
import org.sonar.process.ProcessId;
import org.sonar.process.ProcessProperties;
import org.sonar.process.Props;

import static org.mockito.Mockito.mock;
import static org.sonar.process.ProcessProperties.CLUSTER_ENABLED;

public class AppSettingsImplForTests implements AppSettings {
  private final Properties commandLineArguments;
  private Props allProps;
  private final static org.sonar.application.JdbcSettings jdbcSettings = mock(org.sonar.application.JdbcSettings.class);

  private AppSettingsImplForTests(Properties commandLineArguments, Props allProps) {
    this.commandLineArguments = commandLineArguments;
    this.allProps = allProps;
  }

  @VisibleForTesting
  public static AppSettingsImplForTests forCliArguments(Properties cliArguments) {
    org.sonar.application.PropsBuilder propsBuilder = new PropsBuilder(cliArguments, jdbcSettings);
    return new AppSettingsImplForTests(cliArguments, propsBuilder.build());
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

  @Override
  public void reload() {
    this.allProps = forCliArguments(commandLineArguments).allProps;
  }

  private boolean isProcessEnabled(String disabledPropertyKey) {
    return !allProps.valueAsBoolean(ProcessProperties.CLUSTER_ENABLED) || !allProps.valueAsBoolean(disabledPropertyKey);
  }
}
