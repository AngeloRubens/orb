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

package org.glassfish.orb.fidelity;

import java.util.Hashtable;
import java.util.List;

import javax.naming.Context;
import javax.naming.InitialContext;

import org.glassfish.orb.baseline.Greeter;
import org.glassfish.orb.baseline.Values;

/**
 * Checks that what goes out over RMI-IIOP comes back unchanged.
 *
 * <p>The load client next door measures how fast the encoding runs. Fast and
 * wrong is the failure mode that costs the most to find later, and the load
 * client cannot see it: it counts calls and errors, so a call that answered
 * with the wrong string counts as a success. This one checks the answers.
 *
 * <p>The cases are not arbitrary. Each one stands on a place where the CDR
 * encoding was changed:
 *
 * <ul>
 * <li><b>Strings.</b> The branchless filter that decides whether a string can
 *     be copied as UTF-16 code units, or has to go through the converter,
 *     answers "maybe a surrogate" for every character at or above U+D800. So
 *     the interesting inputs are the ones either side of that line - U+D7FF
 *     takes the fast path, U+E000 takes the slow one without being a
 *     surrogate at all - plus real surrogate pairs, plus a lone surrogate,
 *     which is the case where an encoder has to decide something.
 * <li><b>Graphs.</b> Shared references and cycles survive only if the
 *     indirection table maps them, and that table was changed: a small-array
 *     mode for the common case and a re-link when it grows. A graph large
 *     enough to grow it past that threshold is the point of the big case.
 * <li><b>Fragments.</b> A payload longer than the fragment size is written
 *     across fragments, and the buffer that holds a partially read string
 *     across one is now reused rather than reallocated.
 * </ul>
 *
 * <p>Two kinds of check. Where the right answer is not in doubt - a string
 * that comes back equal to the one sent, a cycle that is still a cycle - it
 * is asserted here and a failure fails the job. Where the right answer is
 * whatever this ORB has always done, such as a lone surrogate, the outcome is
 * printed in a stable form instead, and the job compares the transcript
 * against the one the unchanged ORB produced. Predicting that answer would
 * only prove that the prediction and the code were written by the same
 * person.
 */
public final class IiopFidelity {

    private static final String BEAN = System.getProperty("bean", "GreeterBean");
    private static final String NAME =
            "java:global/orb-baseline-bean/" + BEAN + '!' + Greeter.class.getName();

    private static int failures;

    private IiopFidelity() {
    }

    public static void main(String[] args) throws Exception {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.enterprise.naming.SerialInitContextFactory");
        env.put("org.omg.CORBA.ORBInitialHost", System.getProperty("orb.host", "localhost"));
        env.put("org.omg.CORBA.ORBInitialPort", System.getProperty("orb.port", "3700"));
        InitialContext context = new InitialContext(env);
        Greeter greeter = (Greeter) context.lookup(NAME);

        strings(greeter);
        graphs(greeter);
        transientFields(greeter);
        exceptions(greeter);
        // Last on purpose. If a lone surrogate turns out to break the
        // connection rather than the string, everything after it would fail
        // for a reason that is not its own.
        loneSurrogates(greeter);

        context.close();
        System.out.println(failures == 0 ? "FIDELITY OK" : "FIDELITY FAILED: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- strings ---------------------------------------------------------

    private static void strings(Greeter greeter) {
        roundTrip(greeter, "empty", "");
        roundTrip(greeter, "ascii", "Ada");
        roundTrip(greeter, "ascii-long", "Ada Lovelace ".repeat(2000));

        // Either side of the line the filter draws at U+D800.
        roundTrip(greeter, "below-the-filter", "a\uD7FFb");
        roundTrip(greeter, "at-the-filter-boundary", "a\uE000b\uFFFFc");
        roundTrip(greeter, "private-use-long", "\uE000".repeat(5000));

        // Real pairs: one at the start of the supplementary planes and one at
        // the very top, so a mistake in either half of a pair shows.
        roundTrip(greeter, "surrogate-pairs", "grin \uD83D\uDE00 top \uDBFF\uDFFF");
        roundTrip(greeter, "surrogate-pairs-long", "\uD83D\uDE00".repeat(4000));

        // Everything at once, so an off-by-one between a two-unit character
        // and its neighbours cannot hide behind a uniform string.
        roundTrip(greeter, "mixed", "a\u00E9\u4E2D\uD83D\uDE00z\uD7FF\uE000".repeat(1500));

        // Long enough to be written across fragments at every fragment size
        // the job runs, and not a multiple of any of them.
        roundTrip(greeter, "crosses-fragments", grow("\u00E9\uD83D\uDE00x", 70001));

    }

    /** What a lone surrogate does is this ORB's business, not this test's. */
    private static void loneSurrogates(Greeter greeter) {
        report(greeter, "lone-high-surrogate", "a\uD800b");
        report(greeter, "lone-low-surrogate", "a\uDC00b");
    }

    /** A string of at least {@code length} chars, built from a repeating unit. */
    private static String grow(String unit, int length) {
        StringBuilder out = new StringBuilder(length + unit.length());
        while (out.length() < length) {
            out.append(unit);
        }
        return out.toString();
    }

    private static void roundTrip(Greeter greeter, String label, String sent) {
        String back;
        try {
            back = greeter.echoLarge(sent);
        } catch (RuntimeException e) {
            fail(label, "threw " + e);
            return;
        }
        if (sent.equals(back)) {
            System.out.println("ok   " + label + " (" + sent.length() + " chars)");
        } else {
            fail(label, difference(sent, back));
        }
    }

    /**
     * Prints the outcome rather than judging it, in a form two runs can be
     * compared on.
     */
    private static void report(Greeter greeter, String label, String sent) {
        String outcome;
        try {
            String back = greeter.echoLarge(sent);
            outcome = sent.equals(back) ? "unchanged" : "changed to " + codeUnits(back);
        } catch (Throwable e) {
            outcome = "threw " + e.getClass().getName();
        }
        System.out.println("note " + label + ": " + outcome);
    }

    /** Where two strings first differ, with enough context to act on. */
    private static String difference(String sent, String back) {
        if (back == null) {
            return "came back null";
        }
        if (sent.length() != back.length()) {
            return "sent " + sent.length() + " chars, got " + back.length();
        }
        for (int i = 0; i < sent.length(); i++) {
            if (sent.charAt(i) != back.charAt(i)) {
                return "differs at char " + i + ": sent U+"
                        + Integer.toHexString(sent.charAt(i)).toUpperCase()
                        + ", got U+" + Integer.toHexString(back.charAt(i)).toUpperCase();
            }
        }
        return "equal by character but not by equals()";
    }

    private static String codeUnits(String value) {
        StringBuilder out = new StringBuilder(value.length() * 5);
        for (int i = 0; i < value.length(); i++) {
            out.append(i == 0 ? "" : " ").append(String.format("%04X", (int) value.charAt(i)));
        }
        return out.toString();
    }

    // ---- graphs ----------------------------------------------------------

    private static void graphs(Greeter greeter) {
        Values.Node one = new Values.Node("one");
        Values.Node two = new Values.Node("two");
        one.next = two;
        two.next = one;

        Values.Node backOne = greeter.echoNode(one);
        check("cycle-survives", backOne.next.next == backOne,
                "a two node cycle came back as a chain");
        check("cycle-names", "one".equals(backOne.name) && "two".equals(backOne.next.name),
                "names did not survive the cycle");

        Values.Node shared = new Values.Node("shared");
        Values.Shared both = greeter.echoShared(new Values.Shared(shared, shared));
        check("shared-identity", both.left == both.right,
                "one object referenced twice came back as two");

        // Past the point where the indirection table stops being a small
        // array and has to grow, which is where its entries are re-linked.
        int nodes = Integer.getInteger("graphNodes", 4000);
        Values.Node head = new Values.Node("n0");
        Values.Node tail = head;
        for (int i = 1; i < nodes; i++) {
            tail.next = new Values.Node("n" + i);
            tail = tail.next;
        }
        tail.next = head;

        Values.Node backHead = greeter.echoNode(head);
        Values.Node cursor = backHead;
        for (int i = 0; i < nodes; i++) {
            if (!("n" + i).equals(cursor.name)) {
                fail("large-graph", "node " + i + " came back as " + cursor.name);
                return;
            }
            cursor = cursor.next;
        }
        check("large-graph", cursor == backHead,
                "a " + nodes + " node cycle did not close");
    }

    // ---- other value semantics -------------------------------------------

    private static void transientFields(Greeter greeter) {
        Values.WithTransient back =
                greeter.echoTransient(new Values.WithTransient("kept", "dropped"));
        check("transient-kept", "kept".equals(back.kept), "a normal field was lost");
        check("transient-dropped", back.dropped == null,
                "a transient field crossed the wire: " + back.dropped);
    }

    private static void exceptions(Greeter greeter) {
        try {
            greeter.refuse();
            fail("application-exception", "the bean returned instead of throwing");
        } catch (Values.Refused e) {
            check("application-exception", "E_CLOSED".equals(e.reasonCode())
                            && List.of("first", "second").equals(e.offending()),
                    "the exception arrived without its state");
        } catch (Throwable e) {
            fail("application-exception", "arrived as " + e.getClass().getName());
        }

        try {
            greeter.explode();
            fail("system-exception", "the bean returned instead of throwing");
        } catch (jakarta.ejb.EJBException e) {
            System.out.println("ok   system-exception");
        } catch (Throwable e) {
            fail("system-exception",
                    "an unannotated runtime exception must arrive as EJBException, not "
                            + e.getClass().getName());
        }
    }

    // ---- reporting -------------------------------------------------------

    private static void check(String label, boolean ok, String whenNot) {
        if (ok) {
            System.out.println("ok   " + label);
        } else {
            fail(label, whenNot);
        }
    }

    private static void fail(String label, String detail) {
        failures++;
        System.out.println("FAIL " + label + ": " + detail);
    }
}
