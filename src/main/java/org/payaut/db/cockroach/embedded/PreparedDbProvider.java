/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.payaut.db.cockroach.embedded;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.apache.commons.lang3.RandomStringUtils;
import org.payaut.db.cockroach.embedded.EmbeddedCockroach.Builder;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PreparedDbProvider
{
   static final Logger LOG = LoggerFactory.getLogger(EmbeddedCockroach.class);
   //    private static final String JDBC_FORMAT = "jdbc:postgresql://localhost:%d/%s?user=%s";

    /**
     * Each database cluster's <code>defaultdb</code> database has a unique set of schema
     * loaded so that the databases may be cloned.
     */
    // @GuardedBy("PreparedDbProvider.class")
    private static final Map<ClusterKey, PrepPipeline> CLUSTERS = new HashMap<>();

    private final PrepPipeline dbPreparer;

    public static PreparedDbProvider forPreparer(DatabasePreparer preparer) {
        return forPreparer(preparer, Collections.emptyList());
    }

    public static PreparedDbProvider forPreparer(DatabasePreparer preparer, Iterable<Consumer<EmbeddedCockroach.Builder>> customizers) {
        return new PreparedDbProvider(preparer, customizers);
    }

    private PreparedDbProvider(DatabasePreparer preparer, Iterable<Consumer<Builder>> customizers) {
        try {
            dbPreparer = createOrFindPreparer(preparer, customizers);
        } catch (final IOException | SQLException e) {
        	LOG.error("Unable to prepare database", e);
            throw new RuntimeException(e);
        }
    }

    private static synchronized PrepPipeline createOrFindPreparer(DatabasePreparer preparer, Iterable<Consumer<Builder>> customizers) throws IOException, SQLException
    {
        final ClusterKey key = new ClusterKey(preparer, customizers);
        PrepPipeline result = CLUSTERS.get(key);
        if (result != null) {
            return result;
        }

        final Builder builder = EmbeddedCockroach.builder();
        customizers.forEach(c -> c.accept(builder));
        final EmbeddedCockroach crdb = builder.start(); //NOPMD

        result = new PrepPipeline(crdb, preparer).start();
        CLUSTERS.put(key, result);
        return result;
    }

    /**
     * Create a new database, and return it as a JDBC connection string.
     * NB: No two invocations will return the same database.
     */
    public String createDatabase() throws SQLException
    {
        return getJdbcUri(createNewDB());
    }

    /**
     * Create a new database, and return the backing info.
     * This allows you to access the host and port.
     * More common usage is to call createDatabase() and
     * get the JDBC connection string.
     * NB: No two invocations will return the same database.
     */
    private DbInfo createNewDB() throws SQLException
    {
       return dbPreparer.getNextDb();
    }

    public ConnectionInfo createNewDatabase() throws SQLException
    {
        final DbInfo dbInfo = createNewDB();
        return dbInfo == null || !dbInfo.isSuccess() ? null : new ConnectionInfo(dbInfo.getDbName(), dbInfo.getPort(), dbInfo.getUser());
    }

    /**
     * Create a new Datasource given DBInfo.
     * More common usage is to call createDatasource().
     */
    public DataSource createDataSourceFromConnectionInfo(final ConnectionInfo connectionInfo) throws SQLException
    {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setPortNumbers(new int[] { connectionInfo.getPort() });
        ds.setDatabaseName(connectionInfo.getDbName());
        ds.setUser(connectionInfo.getUser());
        return ds;
    }

    /**
     * Create a new database, and return it as a DataSource.
     * No two invocations will return the same database.
     */
    public DataSource createDataSource() throws SQLException
    {
        return createDataSourceFromConnectionInfo(createNewDatabase());
    }

    String getJdbcUri(DbInfo db)
    {
        return String.format(EmbeddedCockroach.JDBC_FORMAT, db.port, db.dbName, db.user);
    }

    /**
     * Return configuration tweaks in a format appropriate for otj-jdbc DatabaseModule.
     */
    public Map<String, String> getConfigurationTweak(String dbModuleName) throws SQLException
    {
        final DbInfo db = dbPreparer.getNextDb();
        final Map<String, String> result = new HashMap<>();
        result.put("ot.db." + dbModuleName + ".uri", getJdbcUri(db));
        result.put("ot.db." + dbModuleName + ".ds.user", db.user);
        return result;
    }

    /**
     * Spawns a background thread that prepares databases ahead of time for speed, and then uses a
     * synchronous queue to hand the prepared databases off to test cases.
     */
    private static class PrepPipeline implements Runnable
    {
        private final EmbeddedCockroach crdb;
        private final DatabasePreparer preparer;
        private final SynchronousQueue<DbInfo> nextDatabase = new SynchronousQueue<>();

        PrepPipeline(EmbeddedCockroach crdb, DatabasePreparer preparer)
        {
            this.crdb = crdb;
            this.preparer = preparer;
        }

        PrepPipeline start()
        {
            final ExecutorService service = Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("cluster-" + crdb + "-preparer");
                return t;
            });
            service.submit(this);
            service.shutdown();
            return this;
        }

        DbInfo getNextDb() throws SQLException
        {
            try {
                final DbInfo next = nextDatabase.take();
                if (next.ex != null) {
                    throw next.ex;
                }
                return next;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void run()
        {
            while (true) {
                final String newDbName = RandomStringUtils.randomAlphabetic(12).toLowerCase(Locale.ENGLISH);
                SQLException failure = null;
                try {
                    create(crdb.getPostgresDatabase(), newDbName, "root");
                } catch (SQLException e) {
                    failure = e;
                }
                try {
                    if (failure == null) {
                        final PGSimpleDataSource ds = new PGSimpleDataSource();
                        ds.setPortNumbers(new int[] { crdb.getPort() });
                        ds.setDatabaseName(newDbName);
                        ds.setUser("root");
                        try {
                        	this.preparer.prepare(ds);
                        	nextDatabase.put(DbInfo.ok(newDbName, crdb.getPort(), "root"));
                        } catch (SQLException e) {
                            failure = e;
						}
                    }
                    if (failure != null) {
                        nextDatabase.put(DbInfo.error(failure));
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void create(final DataSource connectDb, final String dbName, final String userName) throws SQLException
    {
        if (dbName == null) {
            throw new IllegalStateException("the database name must not be null!");
        }
        if (userName == null) {
            throw new IllegalStateException("the user name must not be null!");
        }
        try (Connection c = connectDb.getConnection()) {
        	try (PreparedStatement stmtCreate = c.prepareStatement(String.format("CREATE DATABASE %s ENCODING = 'UTF-8'; ", dbName))) {
            	stmtCreate.execute();
        	}
        	try (PreparedStatement stmtGrant = c.prepareStatement(String.format("GRANT ALL ON DATABASE %s TO %s", dbName, userName))) {
        		stmtGrant.execute();
        	}
        }
    }

    private static class ClusterKey {

        private final DatabasePreparer preparer;
        private final Builder builder;

        ClusterKey(DatabasePreparer preparer, Iterable<Consumer<Builder>> customizers) {
            this.preparer = preparer;
            this.builder = EmbeddedCockroach.builder();
            customizers.forEach(c -> c.accept(this.builder));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ClusterKey that = (ClusterKey) o;
            return Objects.equals(preparer, that.preparer) &&
                    Objects.equals(builder, that.builder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(preparer, builder);
        }
    }

    public static class DbInfo
    {
        private static final Logger LOG = LoggerFactory.getLogger(EmbeddedCockroach.class);
        
        public static DbInfo ok(final String dbName, final int port, final String user) {
            return new DbInfo(dbName, port, user, null);
        }

        public static DbInfo error(SQLException e) {
        	LOG.error("Database initialize error", e);
            return new DbInfo(null, -1, null, e);
        }

        private final String dbName;
        private final int port;
        private final String user;
        private final SQLException ex;

        private DbInfo(final String dbName, final int port, final String user, final SQLException e) {
            this.dbName = dbName;
            this.port = port;
            this.user = user;
            this.ex = null;
        }

        public int getPort() {
            return port;
        }

        public String getDbName() {
            return dbName;
        }

        public String getUser() {
            return user;
        }

        public SQLException getException() {
            return ex;
        }

        public boolean isSuccess() {
            return ex == null;
        }
    }
}
