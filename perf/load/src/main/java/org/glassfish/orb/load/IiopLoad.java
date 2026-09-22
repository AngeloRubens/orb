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

package org.glassfish.orb.load;

import java.util.Arrays;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import javax.naming.Context;
import javax.naming.InitialContext;

import org.glassfish.orb.baseline.Greeter;
import org.glassfish.orb.baseline.Values;

/**
 * Drives a remote stateless bean over RMI-IIOP from many threads and reports
 * throughput and latency percentiles.
 *
 * System properties: scenario (small|graph|large|all), threads, warmup and
 * seconds (per phase), graphNodes, largeBytes, lookupPerThread.
 */
public final class IiopLoad {

    // GreeterBean (stateless, container managed transactions), GreeterBmtBean
    // (no transaction per call) or GreeterSingletonBean (no pool either).
    private static final String BEAN = System.getProperty("bean", "GreeterBean");
    private static final String NAME = "java:global/orb-baseline-bean/" + BEAN + "!" + Greeter.class.getName();

    public static void main(String[] args) throws Exception {
        String scenario = System.getProperty("scenario", "all");
        int threads = Integer.getInteger("threads", 16);
        int warmup = Integer.getInteger("warmup", 20);
        int seconds = Integer.getInteger("seconds", 60);
        boolean lookupPerThread = Boolean.getBoolean("lookupPerThread");

        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.enterprise.naming.SerialInitContextFactory");
        env.put("org.omg.CORBA.ORBInitialHost", System.getProperty("orb.host", "localhost"));
        env.put("org.omg.CORBA.ORBInitialPort", System.getProperty("orb.port", "3700"));
        InitialContext context = new InitialContext(env);

        Greeter shared = (Greeter) context.lookup(NAME);
        String[] scenarios = "all".equals(scenario) ? new String[] {"small", "graph", "large"} : scenario.split(",");
        for (String s : scenarios) {
            Op op = op(s);
            run(s + " warmup", op, threads, warmup, shared, lookupPerThread ? context : null, false);
            run(s, op, threads, seconds, shared, lookupPerThread ? context : null, true);
        }
        context.close();
        System.exit(0);
    }

    interface Op {
        void call(Greeter greeter);
    }

    private static Op op(String scenario) {
        switch (scenario) {
            case "small":
                return g -> check(g.greet("hi", 1).length() == 2);
            case "graph": {
                int n = Integer.getInteger("graphNodes", 50);
                Values.Node head = new Values.Node("n0");
                Values.Node cur = head;
                for (int i = 1; i < n; i++) {
                    cur.next = new Values.Node("n" + i);
                    cur = cur.next;
                }
                Values.Node payload = head;
                return g -> {
                    // Every node back, in order: a lost or misplaced fragment shows here.
                    Values.Node back = g.echoNode(payload);
                    for (int i = 0; i < n; i++, back = back.next) {
                        check(back != null && back.name.equals("n" + i));
                    }
                    check(back == null);
                };
            }
            case "large": {
                // Not one repeated char: the content must come back exactly.
                StringBuilder sb = new StringBuilder();
                int size = Integer.getInteger("largeBytes", 64 * 1024);
                for (int i = 0; sb.length() < size; i++) {
                    sb.append((char) ('a' + i % 26)).append(i % 97 == 0 ? "é€" : "");
                }
                String payload = sb.substring(0, size);
                return g -> check(g.echoLarge(payload).equals(payload));
            }
            default:
                throw new IllegalArgumentException(scenario);
        }
    }

    private static void check(boolean ok) {
        if (!ok) {
            throw new IllegalStateException("wrong answer");
        }
    }

    private static void run(String label, Op op, int threads, int seconds, Greeter shared, Context ctx,
            boolean report) throws Exception {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long[][] samples = new long[threads][];
        int[] counts = new int[threads];
        AtomicLong errors = new AtomicLong();
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread thread = new Thread(() -> {
                try {
                    Greeter g = ctx == null ? shared : (Greeter) ctx.lookup(NAME);
                    long[] lat = new long[1 << 16];
                    int c = 0;
                    while (System.nanoTime() < end) {
                        long t0 = System.nanoTime();
                        try {
                            op.call(g);
                        } catch (RuntimeException e) {
                            if (errors.getAndIncrement() < 3) {
                                e.printStackTrace();
                            }
                        }
                        long d = System.nanoTime() - t0;
                        if (c == lat.length) {
                            lat = Arrays.copyOf(lat, lat.length * 2);
                        }
                        lat[c++] = d;
                    }
                    samples[id] = lat;
                    counts[id] = c;
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }, "load-" + t);
            thread.start();
        }
        done.await();
        if (!report) {
            System.out.println("[" + label + "] done");
            return;
        }
        int total = Arrays.stream(counts).sum();
        long[] all = new long[total];
        int pos = 0;
        for (int t = 0; t < threads; t++) {
            if (samples[t] != null) {
                System.arraycopy(samples[t], 0, all, pos, counts[t]);
                pos += counts[t];
            }
        }
        Arrays.sort(all, 0, pos);
        System.out.printf("RESULT bean=" + BEAN + " scenario=%s threads=%d seconds=%d calls=%d errors=%d throughput=%.0f/s "
                + "p50=%.3fms p90=%.3fms p99=%.3fms p999=%.3fms max=%.3fms%n",
                label, threads, seconds, pos, errors.get(), pos / (double) seconds,
                pct(all, pos, 0.50), pct(all, pos, 0.90), pct(all, pos, 0.99), pct(all, pos, 0.999),
                pos == 0 ? 0 : all[pos - 1] / 1e6);
    }

    private static double pct(long[] sorted, int n, double p) {
        return n == 0 ? 0 : sorted[Math.min(n - 1, (int) (n * p))] / 1e6;
    }
}
