/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.orb.baseline;

import jakarta.ejb.Remote;

/** The stateless remote view under test. */
@Remote
public interface Greeter {

    String greet(String name, int times);

    Values.Node echoNode(Values.Node node);

    Values.Shared echoShared(Values.Shared shared);

    Values.WithTransient echoTransient(Values.WithTransient value);

    String echoLarge(String payload);

    void refuse();

    void explode();
}
