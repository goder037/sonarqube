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
package org.sonar.server.platform.db;

import java.io.File;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.apache.commons.lang.StringUtils;
import org.h2.Driver;
import org.h2.tools.Server;
import org.picocontainer.Startable;
import org.sonar.api.config.Settings;
import org.sonar.api.utils.SonarException;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.sonar.api.database.DatabaseProperties.PROP_EMBEDDED_PORT;
import static org.sonar.api.database.DatabaseProperties.PROP_PASSWORD;
import static org.sonar.api.database.DatabaseProperties.PROP_PASSWORD_DEFAULT_VALUE;
import static org.sonar.api.database.DatabaseProperties.PROP_URL;
import static org.sonar.api.database.DatabaseProperties.PROP_USER;
import static org.sonar.api.database.DatabaseProperties.PROP_USER_DEFAULT_VALUE;
import static org.sonar.process.ProcessProperties.PATH_DATA;

public class EmbeddedDatabase implements Startable {
  private static final Logger LOG = Loggers.get(EmbeddedDatabase.class);
  private final Settings settings;
  private Server server;

  public EmbeddedDatabase(Settings settings) {
    this.settings = settings;
  }

  @Override
  public void start() {
    File dbHome = new File(getRequiredSetting(PATH_DATA));
    if (!dbHome.exists()) {
      dbHome.mkdirs();
    }

    startServer(dbHome);
  }

  private void startServer(File dbHome) {
    String url = getRequiredSetting(PROP_URL);
    String port = getRequiredSetting(PROP_EMBEDDED_PORT);
    String user = getSetting(PROP_USER, PROP_USER_DEFAULT_VALUE);
    String password = getSetting(PROP_PASSWORD, PROP_PASSWORD_DEFAULT_VALUE);
    try {
      if (url.contains("/mem:")) {
        server = Server.createTcpServer("-tcpPort", port, "-tcpAllowOthers", "-baseDir", dbHome.getAbsolutePath());
      } else {
        createDatabase(dbHome, user, password);
        server = Server.createTcpServer("-tcpPort", port, "-tcpAllowOthers", "-ifExists", "-baseDir", dbHome.getAbsolutePath());
      }

      LOG.info("Starting embedded database on port " + server.getPort() + " with url " + url);
      server.start();

      LOG.info("Embedded database started. Data stored in: " + dbHome.getAbsolutePath());
    } catch (Exception e) {
      throw new SonarException("Unable to start database", e);
    }
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop();
      server = null;
      LOG.info("Embedded database stopped");
    }
  }

  private String getRequiredSetting(String property) {
    String value = settings.getString(property);
    checkArgument(isNotEmpty(value), "Missing property %s", property);
    return value;
  }

  private String getSetting(String name, String defaultValue) {
    return StringUtils.defaultIfBlank(settings.getString(name), defaultValue);
  }

  private static void createDatabase(File dbHome, String user, String password) throws SQLException {
    String url = format("jdbc:h2:%s/sonar;USER=%s;PASSWORD=%s", dbHome.getAbsolutePath(), user, password);

    DriverManager.registerDriver(new Driver());
    DriverManager.getConnection(url).close();
  }
}
