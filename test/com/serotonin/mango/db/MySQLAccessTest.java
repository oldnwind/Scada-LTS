package com.serotonin.mango.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import br.org.scadabr.db.AbstractMySQLDependentTest;
import br.org.scadabr.db.scenarios.ScenarioWithAdministrador;
import br.org.scadabr.db.scenarios.TablelessDatabaseScenario;

import com.serotonin.mango.Common;
import com.serotonin.mango.db.DatabaseAccess.DatabaseType;
import org.scada_lts.dao.SystemSettingsDAO;
import com.serotonin.mango.vo.User;
import org.scada_lts.mango.service.UserService;
import org.springframework.jdbc.core.ConnectionCallback;

public class MySQLAccessTest extends AbstractMySQLDependentTest {

	private static final double A_VALUE = 12.2;
	private int NUMBER_OF_TABLES = 44;

	@Test
	public void databaseTypeShouldBeORACLE11G() {
		assertEquals(DatabaseType.MYSQL, mysqlAccess.getType());
	}

	@Test
	public void afterInitializeEmptyDatabaseShouldHaveAllTables()
			throws SQLException {
		useScenario(new TablelessDatabaseScenario()); // automatically
														// initializes
														// DatabaseAccess

		String showTables = "SHOW TABLES";
		String count = "SELECT FOUND_ROWS()";

		Connection conn = mysqlAccess.getDataSource().getConnection();
		Statement stmt = conn.createStatement();
		stmt.executeQuery(showTables);
		ResultSet rs = stmt.executeQuery(count);
		assertTrue("Query was successful", rs.next());
		assertEquals("All " + NUMBER_OF_TABLES + " tables exists",
				NUMBER_OF_TABLES, rs.getLong(1));
	}

	@Test
	public void afterInitializeEmptyDatabaseShouldHaveAnAdminUserRegistered() {
		useScenario(new TablelessDatabaseScenario()); // automatically
														// initializes
														// DatabaseAccess

		User user = new UserService().getUser("admin");

		assertEquals(Common.encrypt("admin"), user.getPassword());
		assertEquals("admin@yourMangoDomain.com", user.getEmail());
		// assertEquals("", user.getHomeUrl());
		assertFalse(user.isDisabled());
		assertTrue(user.isAdmin());
		assertTrue(user.getDataSourcePermissions().isEmpty());
		assertTrue(user.getDataPointPermissions().isEmpty());
	}

	@Test
	public void afterInitializeEmptyDatabaseShouldSaveDatabaseSchemaVersion() {
		useScenario(new TablelessDatabaseScenario()); // automatically
														// initializes
														// DatabaseAccess
		final String savedValue = new SystemSettingsDAO()
				.getValue(SystemSettingsDAO.DATABASE_SCHEMA_VERSION);
		assertEquals(Common.getVersion(), savedValue);
	}

	@Test
	public void afterInitializeWithPopulatedDatabaseShouldNotOverrideData() {
		useScenario(new TablelessDatabaseScenario()); // automatically
														// initializes
														// DatabaseAccess
		final UserService userService = new UserService();
		User user = userService.getUser("admin");
		user.setHomeUrl("My home URL");
		userService.saveUser(user);

		mysqlAccess.initialize();

		User userAfterSecondInitialization = userService.getUser("admin");
		assertEquals("My home URL", userAfterSecondInitialization.getHomeUrl());
	}

	@Test
	public void applyBoundsShouldReplaceSpecialDoubleRepresentations() {
		assertEquals(0.0, mysqlAccess.applyBounds(Double.NaN), 0);
		assertEquals(Double.MAX_VALUE,
				mysqlAccess.applyBounds(Double.POSITIVE_INFINITY), 0);
		assertEquals(-Double.MAX_VALUE,
				mysqlAccess.applyBounds(Double.NEGATIVE_INFINITY), 0);
		assertEquals(A_VALUE, mysqlAccess.applyBounds(A_VALUE), 0);
	}

	@Test
	public void doInConnectionShouldPassAConnectionToTheDatabase() {
		useScenario(new ScenarioWithAdministrador());

		mysqlAccess.doInConnection(new ConnectionCallback() {
			@Override
			public Object doInConnection(Connection conn) throws SQLException {
				Statement stmt = conn.createStatement();

				ResultSet rs = stmt.executeQuery("SELECT username FROM users");
				String dbName = "";
				while (rs.next()) {
					dbName = new String(rs.getString("username"));
				}
				rs.close();
				assertEquals(dbName, "admin");
				return null;
			}
		});
	}

	@Test
	public void doInConnectionShouldRollbackWhenExceptionIsThrown() {
		useScenario(new ScenarioWithAdministrador());
		try {
			mysqlAccess.doInConnection(new ConnectionCallback() {
				@Override
				public Object doInConnection(Connection conn) throws SQLException {
					Statement stmt = conn.createStatement();

					stmt.execute("UPDATE USERS SET username='admin2' WHERE username='admin'");
					throw new RuntimeException("Oops. An error.");
				}
			});
		} catch (Exception e) {
		}
		UserService userService = new UserService();
		assertNotNull("admin was not changed", userService.getUser("admin"));
	}

	@Test
	public void prepareStatementShouldBeAbleToGetAutoGeneratedIDsAfterInsert()
			throws SQLException {
		useScenario(new ScenarioWithAdministrador());
		UserService userService = new UserService();
		int adminId = userService.getUser("admin").getId();

		Connection connection = mysqlAccess.getDataSource().getConnection();
		String testSql = "insert into users (USERNAME, PASSWORD, EMAIL, ADMIN, DISABLED, RECEIVEALARMEMAILS, RECEIVEOWNAUDITEVENTS) values ('xxx', 'xxxx', 'xxx', 'Y', 'N', 0, 'N')";
		PreparedStatement preparedStatement = mysqlAccess.prepareStatement(
				connection, testSql, "id");
		int affectedRows = preparedStatement.executeUpdate();
		assertEquals(1, affectedRows);

		ResultSet rs = preparedStatement.getGeneratedKeys();
		assertTrue(rs.next());
		long id = rs.getLong(1);

		assertEquals(adminId + 1, id);
	}
}
