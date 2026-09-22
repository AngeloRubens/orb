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

import jakarta.ejb.Stateless;

@Stateless
public class GreeterBean implements Greeter {

    @Override
    public String greet(String name, int times) {
        return (name + ' ').repeat(times).trim();
    }

    @Override
    public Values.Node echoNode(Values.Node node) {
        return node;
    }

    @Override
    public Values.Shared echoShared(Values.Shared shared) {
        return shared;
    }

    @Override
    public Values.WithTransient echoTransient(Values.WithTransient value) {
        return value;
    }

    @Override
    public String echoLarge(String payload) {
        return payload;
    }

    @Override
    public void explode() {
        throw new Values.Exploded("an unchecked exception the bean never declared");
    }

    @Override
    public void refuse() {
        throw new Values.Refused("not today", "E_CLOSED",
                new IllegalStateException("underlying cause"));
    }
}
