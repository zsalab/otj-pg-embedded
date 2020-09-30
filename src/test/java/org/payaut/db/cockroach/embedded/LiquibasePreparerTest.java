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

import org.junit.Rule;
import org.junit.Test;
import org.payaut.db.cockroach.embedded.LiquibasePreparer;
import org.payaut.db.cockroach.junit.EmbeddedCockroachRules;
import org.payaut.db.cockroach.junit.PreparedDbRule;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;

public class LiquibasePreparerTest {
    @Rule
    public PreparedDbRule db = EmbeddedCockroachRules.preparedDatabase(LiquibasePreparer.forClasspathLocation("liqui/master.xml"));

    @Test
    public void testTablesMade() throws Exception {
        try (Connection c = db.getTestDatabase().getConnection();
                Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT * FROM foo");
            rs.next();
            assertEquals("bar", rs.getString(1));
        }
    }
}
