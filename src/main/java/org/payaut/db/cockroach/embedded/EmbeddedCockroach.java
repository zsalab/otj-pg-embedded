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


import static org.payaut.db.cockroach.embedded.EmbeddedUtil.getWorkingDirectory;
import static org.payaut.db.cockroach.embedded.EmbeddedUtil.mkdirs;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddedCockroach implements Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedCockroach.class);
    private static final String LOG_PREFIX = EmbeddedCockroach.class.getName() + ".";
    private static final String LOOPBACK_ADDR = InetAddress.getLoopbackAddress().getHostAddress();
    static final String JDBC_FORMAT = "jdbc:postgresql://"+LOOPBACK_ADDR+":%d/%s?user=%s";

//    private static final String CRDB_STOP_MODE = "fast";
    private static final String CRDB_STOP_WAIT_S = "5s";
//    private static final String CRDB_SUPERUSER = "root";
    private static final Duration DEFAULT_CRDB_STARTUP_WAIT = Duration.ofSeconds(10);
    static final String LOCK_FILE_NAME = "ecrdb-lock";

    private final File crdbDir;

    private final Duration crdbStartupWait;
    private final File dataDirectory;
    private final File lockFile;
    private final UUID instanceId = UUID.randomUUID();
    private final int port;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

//    private final Map<String, String> cockroachConfig;
//    private final Map<String, String> localeConfig;

    private volatile FileOutputStream lockStream;
    private volatile FileLock lock;
    private final boolean cleanDataDirectory;

    private final ProcessBuilder.Redirect errorRedirector;
    private final ProcessBuilder.Redirect outputRedirector;

    EmbeddedCockroach(File parentDirectory, File dataDirectory, boolean cleanDataDirectory,
        /*Map<String, String> cockroachConfig, Map<String, String> localeConfig, */int port, Map<String, String> connectConfig,
        CockroachDirectoryResolver pgDirectoryResolver, ProcessBuilder.Redirect errorRedirector, ProcessBuilder.Redirect outputRedirector) throws IOException
    {
        this(parentDirectory, dataDirectory, cleanDataDirectory/*, cockroachConfig, localeConfig*/, port, connectConfig,
                pgDirectoryResolver, errorRedirector, outputRedirector, DEFAULT_CRDB_STARTUP_WAIT, Optional.empty());
    }

    EmbeddedCockroach(File parentDirectory, File dataDirectory, boolean cleanDataDirectory,
                     /*Map<String, String> cockroachConfig, Map<String, String> localeConfig, */int port, Map<String, String> connectConfig,
                     CockroachDirectoryResolver cockroachDirectoryResolver, ProcessBuilder.Redirect errorRedirector,
                     ProcessBuilder.Redirect outputRedirector, Duration pgStartupWait,
                     Optional<File> overrideWorkingDirectory) throws IOException
    {

        this.cleanDataDirectory = cleanDataDirectory;
//        this.cockroachConfig = new HashMap<>(cockroachConfig);
//        this.localeConfig = new HashMap<>(localeConfig);
        this.port = port;
        this.crdbDir = cockroachDirectoryResolver.getDirectory(overrideWorkingDirectory);
        this.errorRedirector = errorRedirector;
        this.outputRedirector = outputRedirector;
        this.crdbStartupWait = Objects.requireNonNull(pgStartupWait, "Wait time cannot be null");
        if (parentDirectory != null) {
            mkdirs(parentDirectory);
            cleanOldDataDirectories(parentDirectory);
            if (dataDirectory != null) {
                this.dataDirectory = dataDirectory;
            } else {
                this.dataDirectory = new File(parentDirectory, instanceId.toString());
            }
        } else {
            this.dataDirectory = dataDirectory;
        }
        if (this.dataDirectory == null) {
            throw new IllegalArgumentException("no data directory");
        }
        LOG.debug("{} cockroach: data directory is {}, cockroach directory is {}", instanceId, this.dataDirectory, this.crdbDir);
        mkdirs(this.dataDirectory);

        lockFile = new File(this.dataDirectory, LOCK_FILE_NAME);

        lock();
        startCockroach(connectConfig);
//        if (cleanDataDirectory) {
//            initdb();
//        }
    }

//    public DataSource getDefaultDatabase() {
//        return getDatabase("root", "defaultdb");
//    }
//
//    public DataSource getDefaultDatabase(Map<String, String> properties) {
//        return getDatabase("root", "defaultdb", properties);
//    }
//
    public DataSource getPostgresDatabase() {
        return getDatabase("root", "postgres");
    }

    public DataSource getPostgresDatabase(Map<String, String> properties) {
        return getDatabase("root", "postgres", properties);
    }

    public DataSource getDatabase(String userName, String dbName) {
        return getDatabase(userName, dbName, Collections.emptyMap());
    }

    public DataSource getDatabase(String userName, String dbName, Map<String, String> properties) {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] { LOOPBACK_ADDR });
        ds.setPortNumbers(new int[] { port });
        ds.setDatabaseName(dbName);
        ds.setUser(userName);

        properties.forEach((propertyKey, propertyValue) -> {
            try {
                ds.setProperty(propertyKey, propertyValue);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        return ds;
    }

    public String getJdbcUrl(String userName, String dbName) {
        return String.format(JDBC_FORMAT, port, dbName, userName);
    }

    public int getPort() {
        return port;
    }

    private static int detectPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            while(!socket.isBound()) {
                Thread.sleep(50);
            }
            return socket.getLocalPort();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Thread interrupted", e);
        }
    }

    private void lock() throws IOException {
        lockStream = new FileOutputStream(lockFile);
        if ((lock = lockStream.getChannel().tryLock()) == null) { //NOPMD
            throw new IllegalStateException("could not lock " + lockFile);
        }
    }

//    private void initdb() {
//        final StopWatch watch = new StopWatch();
//        watch.start();
//        List<String> command = new ArrayList<>();
//        command.addAll(Arrays.asList(
//                cockroachBin(), "sql", "--url", "postgresql://"+CRDB_SUPERUSER+"@localhost:2625", CRDB_SUPERUSER,
//                "-D", dataDirectory.getPath(), "-E", "UTF-8"));
//        command.addAll(createLocaleOptions());
//        system(command.toArray(new String[command.size()]));
//        LOG.info("{} initdb completed in {}", instanceId, watch);
//    }

    private void startCockroach(Map<String, String> connectConfig) throws IOException {
        final StopWatch watch = new StopWatch();
        watch.start();
        if (started.getAndSet(true)) {
            throw new IllegalStateException("Cockroach already started");
        }

        final List<String> args = new ArrayList<>();
        args.addAll(Arrays.asList(
                cockroachBin(),
                "start-single-node",
                "--insecure",
                "--background",
                "--store", dataDirectory.getPath(),
                "--pid-file", new File(dataDirectory.getPath(), "cockroach.pid").getPath(),
                "--listen-addr", LOOPBACK_ADDR+":"+port,
                "--http-addr", LOOPBACK_ADDR+":"+detectPort()
        ));

        final ProcessBuilder builder = new ProcessBuilder(args);
        if (SystemUtils.IS_OS_WINDOWS) {
        	builder.environment().put("ZONEINFO", new File(crdbDir, "zoneinfo.zip").getPath());
        }

        builder.redirectErrorStream(true);
        builder.redirectError(errorRedirector);
        builder.redirectOutput(outputRedirector);
        final Process cockroach = builder.start();

        if (outputRedirector.type() == ProcessBuilder.Redirect.Type.PIPE) {
            ProcessOutputLogger.logOutput(LoggerFactory.getLogger("crdb-" + instanceId), cockroach);
        } else if(outputRedirector.type() == ProcessBuilder.Redirect.Type.APPEND) {
            ProcessOutputLogger.logOutput(LoggerFactory.getLogger(LOG_PREFIX + "crdb-" + instanceId), cockroach);
        }

        LOG.info("{} cockroach started as {} on port {}.  Waiting up to {} for server startup to finish.", instanceId, cockroach.toString(), port, crdbStartupWait);

        Runtime.getRuntime().addShutdownHook(newCloserThread());

        waitForServerStartup(watch, connectConfig);
    }

//    private List<String> createInitOptions() {
//        final List<String> initOptions = new ArrayList<>();
//        initOptions.addAll(Arrays.asList(
//                "-p", Integer.toString(port),
//                "-F"));
//
//        for (final Entry<String, String> config : cockroachConfig.entrySet()) {
//            initOptions.add("-c");
//            initOptions.add(config.getKey() + "=" + config.getValue());
//        }
//
//        return initOptions;
//    }
//
//    private List<String> createLocaleOptions() {
//        final List<String> localeOptions = new ArrayList<>();
//        for (final Entry<String, String> config : localeConfig.entrySet()) {
//            if (SystemUtils.IS_OS_WINDOWS) {
//                localeOptions.add(String.format("--%s=%s", config.getKey(), config.getValue()));
//            } else {
//                localeOptions.add("--" + config.getKey());
//                localeOptions.add(config.getValue());
//            }
//        }
//        return localeOptions;
//    }

    private void waitForServerStartup(StopWatch watch, Map<String, String> connectConfig) throws IOException {
        Throwable lastCause = null;
        final long start = System.nanoTime();
        final long maxWaitNs = TimeUnit.NANOSECONDS.convert(crdbStartupWait.toMillis(), TimeUnit.MILLISECONDS);
        while (System.nanoTime() - start < maxWaitNs) {
            try {
                verifyReady(connectConfig);
                LOG.info("{} cockroach startup finished in {}", instanceId, watch);
                return;
            } catch (final SQLException e) {
                lastCause = e;
                LOG.trace("While waiting for server startup", e);
            }

            try {
                Thread.sleep(100);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IOException("Gave up waiting for server to start after " + crdbStartupWait.toMillis() + "ms", lastCause);
    }

    private void verifyReady(Map<String, String> connectConfig) throws SQLException
    {
        final InetAddress localhost = InetAddress.getLoopbackAddress();
        try (Socket sock = new Socket()) {

            sock.setSoTimeout((int) Duration.ofMillis(500).toMillis());
            sock.connect(new InetSocketAddress(localhost, port), (int) Duration.ofMillis(500).toMillis());
        } catch (final IOException e) {
            throw new SQLException("connect failed", e);
        }
        try (Connection c = getPostgresDatabase(connectConfig).getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("expecting single row");
            }
            if (1 != rs.getInt(1)) {
                throw new IllegalStateException("expecting 1");
            }
            if (rs.next()) {
                throw new IllegalStateException("expecting single row");
            }
        }
    }

    private Thread newCloserThread() {
        final Thread closeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    EmbeddedCockroach.this.close();
                } catch (IOException ex) {
                    LOG.error("Unexpected IOException from Closeables.close", ex);
                }
            }
        });
        closeThread.setName("cockroach-" + instanceId + "-closer");
        return closeThread;
    }

    @Override
    public void close() throws IOException {
        if (closed.getAndSet(true)) {
            return;
        }
        final StopWatch watch = new StopWatch();
        watch.start();
        try {
            cockroachStop();
            LOG.info("{} shut down cockroach in {}", instanceId, watch);
        } catch (final Exception e) {
            LOG.error("Could not stop cockroach " + instanceId, e);
        }
        if (lock != null) {
            lock.release();
        }
        try {
            lockStream.close();
        } catch (IOException e) {
            LOG.error("while closing lockStream", e);
        }

        if (cleanDataDirectory && System.getProperty("ecrdb.no-cleanup") == null) {
            try {
                FileUtils.deleteDirectory(dataDirectory);
            } catch (IOException e) {
                LOG.error("Could not clean up directory {}", dataDirectory.getAbsolutePath());
            }
        } else {
            LOG.info("Did not clean up directory {}", dataDirectory.getAbsolutePath());
        }
    }

    private void cockroachStop() {
        system(cockroachBin(), 
        		"quit",
        		"--insecure",
        		"--host", LOOPBACK_ADDR+":"+port,
        		"--drain-wait", CRDB_STOP_WAIT_S);
    }

    private void cleanOldDataDirectories(File parentDirectory) {
        final File[] children = parentDirectory.listFiles();
        if (children == null) {
            return;
        }
        for (final File dir : children) {
            if (!dir.isDirectory()) {
                continue;
            }

            final File lockFile = new File(dir, LOCK_FILE_NAME);
            final boolean isTooNew = System.currentTimeMillis() - lockFile.lastModified() < 10 * 60 * 1000;
            if (!lockFile.exists() || isTooNew) {
                continue;
            }
            try (FileOutputStream fos = new FileOutputStream(lockFile);
                 FileLock lock = fos.getChannel().tryLock()) {
                if (lock != null) {
                    LOG.info("Found stale data directory {}", dir);
                    if (new File(dir, "cockroach.pid").exists()) {
                        try {
                            cockroachStop();
                            LOG.info("Shut down orphaned cockroach!");
                        } catch (Exception e) {
                            if (LOG.isDebugEnabled()) {
                                LOG.warn("Failed to stop cockroach " + dir, e);
                            } else {
                                LOG.warn("Failed to stop cockroach " + dir + ": " + e.getMessage());
                            }
                        }
                    }
                    FileUtils.deleteDirectory(dir);
                }
            } catch (final OverlappingFileLockException e) {
                // The directory belongs to another instance in this VM.
                LOG.trace("While cleaning old data directories", e);
            } catch (final Exception e) {
                LOG.warn("While cleaning old data directories", e);
            }
        }
    }

    private String cockroachBin() {
        final String extension = SystemUtils.IS_OS_WINDOWS ? ".exe" : "";
        return new File(crdbDir, "cockroach" + extension).getPath();
    }


    public static EmbeddedCockroach start() throws IOException {
        return builder().start();
    }

    public static EmbeddedCockroach.Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final File parentDirectory = getWorkingDirectory();
        private Optional<File> overrideWorkingDirectory = Optional.empty(); // use tmpdir
        private File builderDataDirectory;
//        private final Map<String, String> config = new HashMap<>();
//        private final Map<String, String> localeConfig = new HashMap<>();
        private boolean builderCleanDataDirectory = true;
        private int builderPort = 0;
        private final Map<String, String> connectConfig = new HashMap<>();
        private CockroachDirectoryResolver cockroachDirectoryResolver;
        private Duration cockroachStartupWait = DEFAULT_CRDB_STARTUP_WAIT;

        private ProcessBuilder.Redirect errRedirector = ProcessBuilder.Redirect.PIPE;
        private ProcessBuilder.Redirect outRedirector = ProcessBuilder.Redirect.PIPE;

        Builder() {
//            config.put("timezone", "UTC");
//            config.put("synchronous_commit", "off");
//            config.put("max_connections", "300");
        }

        public Builder setCockroachStartupWait(Duration pgStartupWait) {
            Objects.requireNonNull(pgStartupWait);
            if (pgStartupWait.isNegative()) {
                throw new IllegalArgumentException("Negative durations are not permitted.");
            }

            this.cockroachStartupWait = pgStartupWait;
            return this;
        }

        public Builder setCleanDataDirectory(boolean cleanDataDirectory) {
            builderCleanDataDirectory = cleanDataDirectory;
            return this;
        }

        public Builder setDataDirectory(Path path) {
            return setDataDirectory(path.toFile());
        }

        public Builder setDataDirectory(File directory) {
            builderDataDirectory = directory;
            return this;
        }

        public Builder setDataDirectory(String path) {
            return setDataDirectory(new File(path));
        }

//        public Builder setServerConfig(String key, String value) {
//            config.put(key, value);
//            return this;
//        }
//
//        public Builder setLocaleConfig(String key, String value) {
//            localeConfig.put(key, value);
//            return this;
//        }

        public Builder setConnectConfig(String key, String value) {
            connectConfig.put(key, value);
            return this;
        }

        public Builder setOverrideWorkingDirectory(File workingDirectory) {
            overrideWorkingDirectory = Optional.ofNullable(workingDirectory);
            return this;
        }

        public Builder setPort(int port) {
            builderPort = port;
            return this;
        }

        public Builder setErrorRedirector(ProcessBuilder.Redirect errRedirector) {
            this.errRedirector = errRedirector;
            return this;
        }

        public Builder setOutputRedirector(ProcessBuilder.Redirect outRedirector) {
            this.outRedirector = outRedirector;
            return this;
        }

//        @Deprecated
//        public Builder setPgBinaryResolver(CockroachBinaryResolver pgBinaryResolver) {
//            return setPgDirectoryResolver(new UncompressBundleDirectoryResolver(pgBinaryResolver));
//        }

        public Builder setCockroachDirectoryResolver(CockroachDirectoryResolver pgDirectoryResolver) {
            this.cockroachDirectoryResolver = pgDirectoryResolver;
            return this;
        }

        public Builder setCockroachBinaryDirectory(File directory) {
            return setCockroachDirectoryResolver((x) -> directory);
        }

        public EmbeddedCockroach start() throws IOException {
            if (builderPort == 0) {
                builderPort = detectPort();
            }
            if (builderDataDirectory == null) {
                builderDataDirectory = Files.createTempDirectory("ecrdb").toFile();
            }
            if (cockroachDirectoryResolver == null) {
                LOG.trace("cockroachDirectoryResolver not overriden, using default (UncompressBundleDirectoryResolver)");
                cockroachDirectoryResolver = UncompressBundleDirectoryResolver.getDefault();
            }
            return new EmbeddedCockroach(parentDirectory, builderDataDirectory, builderCleanDataDirectory,/* config,
                    localeConfig, */builderPort, connectConfig, cockroachDirectoryResolver, errRedirector, outRedirector,
                    cockroachStartupWait, overrideWorkingDirectory);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Builder builder = (Builder) o;
            return builderCleanDataDirectory == builder.builderCleanDataDirectory &&
                    builderPort == builder.builderPort &&
                    Objects.equals(parentDirectory, builder.parentDirectory) &&
                    Objects.equals(builderDataDirectory, builder.builderDataDirectory) &&
//                    Objects.equals(config, builder.config) &&
//                    Objects.equals(localeConfig, builder.localeConfig) &&
                    Objects.equals(connectConfig, builder.connectConfig) &&
                    Objects.equals(cockroachDirectoryResolver, builder.cockroachDirectoryResolver) &&
                    Objects.equals(cockroachStartupWait, builder.cockroachStartupWait) &&
                    Objects.equals(errRedirector, builder.errRedirector) &&
                    Objects.equals(outRedirector, builder.outRedirector);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parentDirectory, builderDataDirectory/*, config, localeConfig*/, builderCleanDataDirectory, builderPort, connectConfig, cockroachDirectoryResolver, cockroachStartupWait, errRedirector, outRedirector);
        }
    }

    private void system(String... command)
    {
        try {
            final ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectError(errorRedirector);
            builder.redirectOutput(outputRedirector);
            final Process process = builder.start();

            if (outputRedirector.type() == ProcessBuilder.Redirect.Type.PIPE) {
                ProcessOutputLogger.logOutput(LoggerFactory.getLogger("init-" + instanceId + ":" + FilenameUtils.getName(command[0])), process);
            } else if(outputRedirector.type() == ProcessBuilder.Redirect.Type.APPEND) {
                ProcessOutputLogger.logOutput(LoggerFactory.getLogger(LOG_PREFIX + "init-" + instanceId + ":" + FilenameUtils.getName(command[0])), process);
            }
            if (0 != process.waitFor()) {
                throw new IllegalStateException(String.format("Process %s failed%n%s", Arrays.asList(command), IOUtils.toString(process.getErrorStream(), StandardCharsets.UTF_8)));
            }
        } catch (final RuntimeException e) { // NOPMD
            throw e;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "EmbeddedCRDB-" + instanceId;
    }
}
