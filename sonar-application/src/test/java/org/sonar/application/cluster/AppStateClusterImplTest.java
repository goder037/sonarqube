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

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ReplicatedMap;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.application.AppSettings;
import org.sonar.application.AppSettingsImplForTests;
import org.sonar.process.ProcessId;
import org.sonar.process.ProcessProperties;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.sonar.application.cluster.AppStateClusterImpl.OPERATIONAL_PROCESSES;

public class AppStateClusterImplTest {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @BeforeClass
  public static void setup() {
    System.setProperty("hazelcast.local.localAddress", "127.0.0.1");
    System.setProperty("java.net.preferIPv4Stack", "true");
  }

  @Test
  public void test_cluster_properties() throws Exception {
    Properties properties = new Properties();
    properties.put(ProcessProperties.CLUSTER_ENABLED, "true");
    properties.put(ProcessProperties.CLUSTER_NAME, "sonarqube");
    properties.put(ProcessProperties.CLUSTER_PORT_AUTOINCREMENT, "true");

    AppSettings appSettings = AppSettingsImplForTests.forCliArguments(properties);

    try (AppStateClusterImpl appStateCluster = new AppStateClusterImpl(appSettings)) {
      assertThat(
        appStateCluster.tryToLockWebLeader()
      ).isEqualTo(true);
      assertThat(
        appStateCluster.tryToLockWebLeader()
      ).isEqualTo(false);
    }

    properties.put(ProcessProperties.CLUSTER_ENABLED, "false");
    appSettings = AppSettingsImplForTests.forCliArguments(properties);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Cluster is not enabled on this instance");
    try (AppStateClusterImpl appStateCluster = new AppStateClusterImpl(appSettings)) {
    }
  }

  @Test
  public void test_listeners() throws InterruptedException {
    Properties properties = new Properties();
    properties.put(ProcessProperties.CLUSTER_ENABLED, "true");
    properties.put(ProcessProperties.CLUSTER_NAME, "sonarqube");
    properties.put(ProcessProperties.CLUSTER_PORT_AUTOINCREMENT, "true");
    AppSettings appSettings = AppSettingsImplForTests.forCliArguments(properties);
    Object lock = new Object();
    List<ProcessId> operationalProcesses = new ArrayList<>();

    try (AppStateClusterImpl appStateCluster = new AppStateClusterImpl(appSettings)) {
      appStateCluster.addListener(processId -> {
        synchronized (lock) {
          operationalProcesses.add(processId);
          lock.notify();
        }
      });

      synchronized (lock) {
        appStateCluster.setOperational(ProcessId.ELASTICSEARCH);
        lock.wait(1_000);
        assertThat(operationalProcesses).containsExactly(ProcessId.ELASTICSEARCH);
      }

      assertThat(appStateCluster.isOperational(ProcessId.ELASTICSEARCH)).isEqualTo(true);
      assertThat(appStateCluster.isOperational(ProcessId.APP)).isEqualTo(false);
      assertThat(appStateCluster.isOperational(ProcessId.WEB_SERVER)).isEqualTo(false);
      assertThat(appStateCluster.isOperational(ProcessId.COMPUTE_ENGINE)).isEqualTo(false);
    }
  }

  @Test
  public void simulate_network_cluster() throws InterruptedException {
    Properties properties = new Properties();
    properties.put(ProcessProperties.CLUSTER_ENABLED, "true");
    properties.put(ProcessProperties.CLUSTER_NAME, "sonarqube");
    properties.put(ProcessProperties.CLUSTER_PORT_AUTOINCREMENT, "true");
    properties.put(ProcessProperties.CLUSTER_INTERFACES, InetAddress.getLoopbackAddress().getHostAddress());

    AppSettings appSettings = AppSettingsImplForTests.forCliArguments(properties);
    Object lock = new Object();
    List<ProcessId> operationalProcesses = new ArrayList<>();

    try (AppStateClusterImpl appStateCluster = new AppStateClusterImpl(appSettings)) {
      appStateCluster.addListener(processId -> {
        synchronized (lock) {
          operationalProcesses.add(processId);
          lock.notify();
        }
      });

      //HazelcastInstance hzInstance = HazelcastHelper.createHazelcastNode(appStateCluster);
      HazelcastInstance hzInstance = HazelcastHelper.createHazelcastClient(appStateCluster);
      String uuid = UUID.randomUUID().toString();
      synchronized (lock) {
        ReplicatedMap<ClusterProcess, Boolean> replicatedMap = hzInstance.getReplicatedMap(OPERATIONAL_PROCESSES);
        replicatedMap.put(
          new ClusterProcess(uuid, ProcessId.ELASTICSEARCH),
          Boolean.FALSE
        );
        lock.wait(1_000);
        assertThat(operationalProcesses).isEmpty();

        replicatedMap.replace(
          new ClusterProcess(uuid, ProcessId.ELASTICSEARCH),
          Boolean.TRUE
        );
        lock.wait(1_000);
        assertThat(operationalProcesses).containsExactly(ProcessId.ELASTICSEARCH);
      }
      hzInstance.shutdown();
    }
  }
}
