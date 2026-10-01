/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
 * v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License v. 2.0 are satisfied: GNU General Public License v2.0
 * w/Classpath exception which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause OR GPL-2.0 WITH
 * Classpath-exception-2.0
 */

package com.sun.corba.ee.impl.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

/**
 * The recursion manager was a HashMap from offset to object and is now an
 * open addressing table. These tests drive both with the same operations and
 * require the same answers.
 */
public class ActiveRecursionManagerTest {

    @Test
    public void answers_like_a_hash_map_under_random_operations() throws IOException {
        for (long seed = 0; seed < 200; seed++) {
            Random random = new Random(seed);
            IIOPInputStream.ActiveRecursionManager manager = new IIOPInputStream.ActiveRecursionManager();
            Map<Integer, Object> expected = new HashMap<>();
            // Offsets as a stream produces them, multiples of 4 or 8, from a
            // range small enough that they collide and recur.
            int range = 8 + random.nextInt(400);
            int stride = random.nextBoolean() ? 4 : 8;
            for (int op = 0; op < 2000; op++) {
                int offset = random.nextInt(range) * stride;
                int roll = random.nextInt(10);
                if (roll < 5) {
                    Object value = random.nextInt(20) == 0 ? null : new Object();
                    manager.addObject(offset, value);
                    expected.put(offset, value);
                } else if (roll < 8) {
                    manager.removeObject(offset);
                    expected.remove(offset);
                }
                check(seed, manager, expected, range, stride);
            }
        }
    }

    @Test
    public void a_deep_nesting_is_added_and_unwound() throws IOException {
        IIOPInputStream.ActiveRecursionManager manager = new IIOPInputStream.ActiveRecursionManager();
        Object[] values = new Object[1000];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Object();
            manager.addObject(i * 8, values[i]);
        }
        for (int i = 0; i < values.length; i++) {
            assertSame(values[i], manager.getObject(i * 8));
        }
        for (int i = values.length - 1; i >= 0; i--) {
            manager.removeObject(i * 8);
            assertFalse(manager.containsObject(i * 8));
            if (i > 0) {
                assertSame(values[i - 1], manager.getObject((i - 1) * 8));
            }
        }
    }

    @Test
    public void a_null_value_is_present() throws IOException {
        IIOPInputStream.ActiveRecursionManager manager = new IIOPInputStream.ActiveRecursionManager();
        manager.addObject(16, null);
        assertTrue(manager.containsObject(16));
        assertNull(manager.getObject(16));
    }

    @Test
    public void an_absent_offset_is_an_invalid_indirection() {
        IIOPInputStream.ActiveRecursionManager manager = new IIOPInputStream.ActiveRecursionManager();
        manager.addObject(16, new Object());
        manager.removeObject(16);
        manager.removeObject(24); // never added: nothing to do
        try {
            manager.getObject(16);
            fail();
        } catch (IOException expected) {
            assertEquals("Invalid indirection to offset 16", expected.getMessage());
        }
    }

    private static void check(long seed, IIOPInputStream.ActiveRecursionManager manager, Map<Integer, Object> expected,
            int range, int stride) throws IOException {
        for (int i = 0; i < range; i++) {
            int offset = i * stride;
            boolean present = expected.containsKey(offset);
            assertEquals("seed " + seed + " offset " + offset, present, manager.containsObject(offset));
            if (present) {
                assertSame("seed " + seed + " offset " + offset, expected.get(offset), manager.getObject(offset));
            }
        }
    }
}
