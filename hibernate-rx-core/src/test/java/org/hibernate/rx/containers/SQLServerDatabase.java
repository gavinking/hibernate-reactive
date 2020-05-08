package org.hibernate.rx.containers;

import org.testcontainers.containers.MSSQLServerContainer;

public class SQLServerDatabase {

	private static final boolean USE_DOCKER = Boolean.getBoolean( "docker" );

	/**
	 * Holds configuration for the SQL Server database container. If the build is run with <code>-Pdocker</code> then
	 * Testcontianers+Docker will be used.
	 * <p>
	 * TIP: To reuse the same containers across multiple runs, set `testcontainers.reuse.enable=true` in a file located
	 * at `$HOME/.testcontainers.properties` (create the file if it does not exist).
	 */
	public static final MSSQLServerContainer<?> sqlserver = new MSSQLServerContainer<>()
			.withReuse( true );

	public static String getJdbcUrl() {
		if ( USE_DOCKER ) {
			// Calling start() will start the container (if not already started)
			// It is required to call start() before obtaining the JDBC URL because it will contain a randomized port
			sqlserver.start();
			return buildJdbcUrlWithCredentials( sqlserver.getJdbcUrl() );
		}
		else {
			return "jdbc:sqlserver://localhost;user=sa;password=reallyStrongPwd123";
		}
	}

	private static String buildJdbcUrlWithCredentials(String jdbcUrl) {
		return jdbcUrl + ";user=" + sqlserver.getUsername() + ";password=" + sqlserver.getPassword();
	}

	private SQLServerDatabase() {
	}

}
