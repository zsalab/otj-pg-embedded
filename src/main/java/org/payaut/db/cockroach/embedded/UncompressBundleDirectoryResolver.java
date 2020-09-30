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

import static org.payaut.db.cockroach.embedded.EmbeddedCockroach.LOCK_FILE_NAME;
import static org.payaut.db.cockroach.embedded.EmbeddedUtil.extractTxz;
import static org.payaut.db.cockroach.embedded.EmbeddedUtil.getArchitecture;
import static org.payaut.db.cockroach.embedded.EmbeddedUtil.getOS;
import static org.payaut.db.cockroach.embedded.EmbeddedUtil.getWorkingDirectory;
import static org.payaut.db.cockroach.embedded.EmbeddedUtil.mkdirs;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileLock;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UncompressBundleDirectoryResolver implements CockroachDirectoryResolver {

    private static volatile UncompressBundleDirectoryResolver DEFAULT_INSTANCE;

    public static synchronized UncompressBundleDirectoryResolver getDefault() {
        if (DEFAULT_INSTANCE == null) {
            DEFAULT_INSTANCE = new UncompressBundleDirectoryResolver(new BundledCockroachBinaryResolver());
        }
        return DEFAULT_INSTANCE;
    }

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedCockroach.class);
    private final Lock prepareBinariesLock = new ReentrantLock();

    private final CockroachBinaryResolver cockroachBinaryResolver;

    public UncompressBundleDirectoryResolver(CockroachBinaryResolver cockroachBinaryResolver) {
        this.cockroachBinaryResolver = cockroachBinaryResolver;
    }

    private final Map<CockroachBinaryResolver, File> prepareBinaries = new HashMap<>();

    @Override
    public File getDirectory(Optional<File> overrideWorkingDirectory) {
        prepareBinariesLock.lock();
        try {
            if (prepareBinaries.containsKey(cockroachBinaryResolver) && prepareBinaries.get(cockroachBinaryResolver).exists()) {
                return prepareBinaries.get(cockroachBinaryResolver);
            }

            final String system = getOS();
            final String machineHardware = getArchitecture();

            LOG.info("Detected a {} {} system", system, machineHardware);
            File cockroachDir;
            final InputStream cockroachBinary; // NOPMD
            try {
                cockroachBinary = cockroachBinaryResolver.getCockroachBinary(system, machineHardware);
            } catch (final IOException e) {
                throw new ExceptionInInitializerError(e);
            }

            if (cockroachBinary == null) {
                throw new IllegalStateException("No cockroach binary found for " + system + " / " + machineHardware);
            }

            try (DigestInputStream pgArchiveData = new DigestInputStream(cockroachBinary, MessageDigest.getInstance("MD5"));
                    ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                IOUtils.copy(pgArchiveData, baos);
                pgArchiveData.close();

                String pgDigest = Hex.encodeHexString(pgArchiveData.getMessageDigest().digest());
                File workingDirectory = overrideWorkingDirectory.isPresent() ? overrideWorkingDirectory.get()
                        : getWorkingDirectory();
                cockroachDir = new File(workingDirectory, String.format("CRDB-%s", pgDigest));

                mkdirs(cockroachDir);
                final File unpackLockFile = new File(cockroachDir, LOCK_FILE_NAME);
                final File crdbDirExists = new File(cockroachDir, ".exists");

                if (!crdbDirExists.exists()) {
                    try (FileOutputStream lockStream = new FileOutputStream(unpackLockFile);
                            FileLock unpackLock = lockStream.getChannel().tryLock()) {
                        if (unpackLock != null) {
                            try {
                                if (crdbDirExists.exists()) {
                                    throw new IllegalStateException(
                                            "unpack lock acquired but .exists file is present " + crdbDirExists);
                                }
                                LOG.info("Extracting CockroachDB...");
                                try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
                                    extractTxz(bais, cockroachDir.getPath());
                                }
                                if (!crdbDirExists.createNewFile()) {
                                    throw new IllegalStateException("couldn't make .exists file " + crdbDirExists);
                                }
                            } catch (Exception e) {
                                LOG.error("while unpacking CockroachDB", e);
                            }
                        } else {
                            // the other guy is unpacking for us.
                            int maxAttempts = 60;
                            while (!crdbDirExists.exists() && --maxAttempts > 0) { // NOPMD
                                Thread.sleep(1000L);
                            }
                            if (!crdbDirExists.exists()) {
                                throw new IllegalStateException(
                                        "Waited 60 seconds for cockroach to be unpacked but it never finished!");
                            }
                        }
                    } finally {
                        if (unpackLockFile.exists() && !unpackLockFile.delete()) {
                            LOG.error("could not remove lock file {}", unpackLockFile.getAbsolutePath());
                        }
                    }
                }
            } catch (final IOException | NoSuchAlgorithmException e) {
                throw new ExceptionInInitializerError(e);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ExceptionInInitializerError(ie);
            }
            prepareBinaries.put(cockroachBinaryResolver, cockroachDir);
            LOG.info("Cockroach binaries at {}", cockroachDir);
            return cockroachDir;
        } finally {
            prepareBinariesLock.unlock();
        }
    }
}
