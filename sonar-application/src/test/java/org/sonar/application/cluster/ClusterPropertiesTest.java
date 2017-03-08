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

package org.sonar.application.cluster;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.application.AppSettings;
import org.sonar.application.AppSettingsImplForTests;
import org.sonar.application.cluster.ClusterProperties;
import org.sonar.process.ProcessProperties;

import static org.assertj.core.api.Assertions.assertThat;

public class ClusterPropertiesTest {
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void test_default_values() throws Exception {
    AppSettings appSettings = AppSettingsImplForTests.forCliArguments(new Properties());
    ClusterProperties props = new ClusterProperties(appSettings);

    assertThat(props.getInterfaces())
      .isEqualTo(Collections.emptyList());
    assertThat(props.isPortAutoincrement())
      .isEqualTo(false);
    assertThat(props.getPort())
      .isEqualTo(9003);
    assertThat(props.isEnabled())
      .isEqualTo(false);
    assertThat(props.getMembers())
      .isEqualTo(Collections.emptyList());
    assertThat(props.getName())
      .isEqualTo("");
  }


  @Test
  public void test_port_parameter() {
    Properties properties = new Properties();
    properties.put(ProcessProperties.CLUSTER_ENABLED, "true");
    properties.put(ProcessProperties.CLUSTER_NAME, "sonarqube");

    Stream.of("-50", "0", "65536", "128563").forEach(
      port -> {
        properties.put(ProcessProperties.CLUSTER_PORT, port);

        AppSettings appSettings = AppSettingsImplForTests.forCliArguments(properties);
        ClusterProperties clusterProperties = new ClusterProperties(appSettings);
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage(
          String.format("Cluster port have been set to %s which is outside the range [1-65535].", port)
        );
        clusterProperties.validate();

      }
    );
  }

  @Test
  public void test_interfaces_parameter() {
    Properties properties = new Properties();
    properties.put(ProcessProperties.CLUSTER_ENABLED, "true");
    properties.put(ProcessProperties.CLUSTER_NAME, "sonarqube");
    properties.put(ProcessProperties.CLUSTER_INTERFACES, "8.8.8.8"); // This IP belongs to Google

    AppSettings appSettings = AppSettingsImplForTests.forCliArguments(properties);
    ClusterProperties clusterProperties = new ClusterProperties(appSettings);
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(
      String.format("Interface %s is not available on this machine.", "8.8.8.8")
    );
    clusterProperties.validate();
  }

  @Test
  public void test_missing_name() {
    Properties properties = new Properties();
    properties.put(ProcessProperties.CLUSTER_ENABLED, "true");
    properties.put(ProcessProperties.CLUSTER_NAME, "");

    AppSettings appSettings = AppSettingsImplForTests.forCliArguments(properties);
    ClusterProperties clusterProperties = new ClusterProperties(appSettings);
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(
      String.format("Cluster have been enabled but a %s has not been defined.",
        ProcessProperties.CLUSTER_NAME)
    );
    clusterProperties.validate();
  }

  @Test
  public void validate_does_not_fail_if_cluster_enabled_and_name_specified() {
    Properties properties = new Properties();
    properties.put(ProcessProperties.CLUSTER_ENABLED, "true");
    properties.put(ProcessProperties.CLUSTER_NAME, "sonarqube");

    AppSettings appSettings = AppSettingsImplForTests.forCliArguments(properties);
    ClusterProperties clusterProperties = new ClusterProperties(appSettings);
    clusterProperties.validate();
  }

  @Test
  public void test_members() {
    Properties properties = new Properties();
    properties.put(ProcessProperties.CLUSTER_ENABLED, "true");
    properties.put(ProcessProperties.CLUSTER_NAME, "sonarqube");

    assertThat(
      new ClusterProperties(AppSettingsImplForTests.forCliArguments(properties)).getMembers()
    ).isEqualTo(
      Collections.emptyList()
    );

    properties.put(ProcessProperties.CLUSTER_MEMBERS, "192.168.1.1");
    assertThat(
      new ClusterProperties(AppSettingsImplForTests.forCliArguments(properties)).getMembers()
    ).isEqualTo(
      Arrays.asList("192.168.1.1:9003")
    );

    properties.put(ProcessProperties.CLUSTER_MEMBERS, "192.168.1.2:5501");
    assertThat(
      new ClusterProperties(AppSettingsImplForTests.forCliArguments(properties)).getMembers()
    ).containsExactlyInAnyOrder(
      "192.168.1.2:5501"
    );

    properties.put(ProcessProperties.CLUSTER_MEMBERS, "192.168.1.2:5501,192.168.1.1");
    assertThat(
      new ClusterProperties(AppSettingsImplForTests.forCliArguments(properties)).getMembers()
    ).containsExactlyInAnyOrder(
      "192.168.1.2:5501", "192.168.1.1:9003"
    );
  }
}
