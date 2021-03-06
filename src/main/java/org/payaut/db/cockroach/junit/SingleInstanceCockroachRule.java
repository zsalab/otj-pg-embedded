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
package org.payaut.db.cockroach.junit;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.rules.ExternalResource;
import org.payaut.db.cockroach.embedded.EmbeddedCockroach;

public class SingleInstanceCockroachRule extends ExternalResource
{
    private volatile EmbeddedCockroach ecrdb;
    private volatile Connection postgresDatabaseConnection;
    private final List<Consumer<EmbeddedCockroach.Builder>> builderCustomizers = new CopyOnWriteArrayList<>();

    SingleInstanceCockroachRule() { }

    @Override
    protected void before() throws Throwable
    {
        super.before();
        ecrdb = cockroach();
        postgresDatabaseConnection = ecrdb.getPostgresDatabase().getConnection();
    }

    private EmbeddedCockroach cockroach() throws IOException {
        final EmbeddedCockroach.Builder builder = EmbeddedCockroach.builder();
        builderCustomizers.forEach(c -> c.accept(builder));
        return builder.start();
    }

    public SingleInstanceCockroachRule customize(Consumer<EmbeddedCockroach.Builder> customizer) {
        if (ecrdb != null) {
            throw new AssertionError("already started");
        }
        builderCustomizers.add(customizer);
        return this;
    }

    public EmbeddedCockroach getEmbeddedCockroach()
    {
        EmbeddedCockroach ecrdb = this.ecrdb;
        if (ecrdb == null) {
            throw new AssertionError("JUnit test not started yet!");
        }
        return ecrdb;
    }

    @Override
    protected void after()
    {
        try {
            postgresDatabaseConnection.close();
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        try {
            ecrdb.close();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
