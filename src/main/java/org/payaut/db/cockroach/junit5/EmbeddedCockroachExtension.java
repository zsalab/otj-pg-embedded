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
package org.payaut.db.cockroach.junit5;

import org.junit.rules.TestRule;
import org.payaut.db.cockroach.embedded.DatabasePreparer;

public final class EmbeddedCockroachExtension {

    private EmbeddedCockroachExtension() {}

    /**
     * Create a vanilla Cockroach single node -- just initialized, no customizations applied.
     */
    public static SingleInstanceCockroachExtension singleInstance() {
        return new SingleInstanceCockroachExtension();
    }

    /**
     * Returns a {@link TestRule} to create a Cockroach single node, shared amongst all test cases in this JVM.
     * The rule contributes Config switches to configure each test case to get its own database.
     */
    public static PreparedDbExtension preparedDatabase(DatabasePreparer preparer)
    {
        return new PreparedDbExtension(preparer);
    }

}
