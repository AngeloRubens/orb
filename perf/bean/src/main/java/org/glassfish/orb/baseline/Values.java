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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The value types whose treatment separates a remote view from an
 * approximation of one. Every case here is something a JSON binding fails.
 */
public final class Values {

    private Values() {
    }

    /** Holds a reference to another, so a graph can be made cyclic. */
    public static final class Node implements Serializable {

        private static final long serialVersionUID = 1L;

        public final String name;
        public Node next;

        public Node(String name) {
            this.name = name;
        }
    }

    /** Two fields pointing at one object: identity, not merely equality. */
    public static final class Shared implements Serializable {

        private static final long serialVersionUID = 1L;

        public final Node left;
        public final Node right;

        public Shared(Node left, Node right) {
            this.left = left;
            this.right = right;
        }
    }

    public static final class WithTransient implements Serializable {

        private static final long serialVersionUID = 1L;

        public final String kept;
        public transient String dropped;

        public WithTransient(String kept, String dropped) {
            this.kept = kept;
            this.dropped = dropped;
        }
    }

    /**
     * An application exception carrying business state.
     *
     * <p>The annotation is the whole difference. Without it an unchecked
     * exception is a <em>system</em> exception: the container wraps it in
     * EJBException and discards the bean instance, and the caller never sees
     * the type it threw. With it, the exception is part of the business
     * contract and arrives intact.
     */
    @jakarta.ejb.ApplicationException(rollback = true)
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String reasonCode;
        private final List<String> offending = new ArrayList<>();

        public Refused(String message, String reasonCode, Throwable cause) {
            super(message, cause);
            this.reasonCode = reasonCode;
            this.offending.add("first");
            this.offending.add("second");
        }

        public String reasonCode() {
            return reasonCode;
        }

        public List<String> offending() {
            return offending;
        }
    }

    /**
     * Not annotated, so the container must treat it as a system failure.
     *
     * <p>Measuring this matters as much as measuring the case above: a
     * transport that let this one through unchanged would be quietly changing
     * the exception contract an application was written against.
     */
    public static final class Exploded extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Exploded(String message) {
            super(message);
        }
    }
}
