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

import org.junit.Test;
import org.sonar.application.AppStateImpl;
import org.sonar.application.AppStateListener;
import org.sonar.process.ProcessId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class AppStateImplTest {

  private AppStateListener listener = mock(AppStateListener.class);
  private AppStateImpl underTest = new AppStateImpl();

  @Test
  public void get_and_set_operational_flag() {
    assertThat(underTest.isOperational(ProcessId.COMPUTE_ENGINE)).isFalse();
    assertThat(underTest.isOperational(ProcessId.ELASTICSEARCH)).isFalse();
    assertThat(underTest.isOperational(ProcessId.WEB_SERVER)).isFalse();

    underTest.setOperational(ProcessId.ELASTICSEARCH);

    assertThat(underTest.isOperational(ProcessId.COMPUTE_ENGINE)).isFalse();
    assertThat(underTest.isOperational(ProcessId.ELASTICSEARCH)).isTrue();
    assertThat(underTest.isOperational(ProcessId.WEB_SERVER)).isFalse();
  }

  @Test
  public void notify_listeners_when_a_process_becomes_operational() {
    underTest.addListener(listener);

    underTest.setOperational(ProcessId.ELASTICSEARCH);

    verify(listener).onAppStateOperational(ProcessId.ELASTICSEARCH);
    verifyNoMoreInteractions(listener);
  }
}
