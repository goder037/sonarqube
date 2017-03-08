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

import java.io.IOException;
import java.util.Properties;
import org.sonar.application.cluster.AppStateClusterImpl;
import org.sonar.process.SystemExit;
import org.sonar.process.monitor.JavaProcessLauncher;
import org.sonar.process.monitor.JavaProcessLauncherImpl;

public class App {

  private final SystemExit systemExit = new SystemExit();
  private StopRequestWatcher stopRequestWatcher;

  public void start(Properties cliArguments) throws IOException {
    AppSettings settings = AppSettingsImpl.forCliArguments(cliArguments);

    AppFileSystem fileSystem = new AppFileSystem(settings.getProps());
    fileSystem.verifyProps();
    fileSystem.reset();

    AppLogging logging = new AppLogging();
    logging.configure(settings.getProps());

    JavaCommandFactory javaCommandFactory = new JavaCommandFactoryImpl(settings);
    AppState state = settings.isClusterEnabled() ?
      new AppStateClusterImpl(settings) :
      new AppStateImpl();

    try (JavaProcessLauncher javaProcessLauncher = new JavaProcessLauncherImpl(fileSystem.getTempDir())) {
      Scheduler scheduler = new SchedulerImpl(settings, javaCommandFactory, javaProcessLauncher, state);

      // intercepts CTRL-C
      Runtime.getRuntime().addShutdownHook(new ShutdownHook(scheduler));

      scheduler.schedule();

      stopRequestWatcher = StopRequestWatcherImpl.create(settings, scheduler, fileSystem);
      stopRequestWatcher.startWatching();

      scheduler.awaitTermination();
      stopRequestWatcher.stopWatching();
    }
    systemExit.exit(0);
  }

  public static void main(String... args) throws IOException {
    CommandLineParser cli = new CommandLineParser();
    Properties commandLineArguments = cli.parseArguments(args);
    new App().start(commandLineArguments);
  }

  private class ShutdownHook extends Thread {
    private final Scheduler scheduler;

    public ShutdownHook(Scheduler scheduler) {
      super("SonarQube Shutdown Hook");
      this.scheduler = scheduler;
    }

    @Override
    public void run() {
      systemExit.setInShutdownHook();

      if (stopRequestWatcher != null) {
        stopRequestWatcher.stopWatching();
      }

      // blocks until everything is corrected terminated
      scheduler.terminate();
    }
  }
}
