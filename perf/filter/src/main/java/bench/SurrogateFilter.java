/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package bench;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * "Can this run of UTF-16 contain a surrogate?" - the filter that decides
 * whether a string can be copied as code units or has to be validated first.
 *
 * <p>It is the top entry of the server profile on large calls, at 42% of Java
 * samples, which is what this measures. The question is not whether the
 * branchless form beats a branchy one - that was settled - but whether the
 * hand-unrolling in the shipped version helps or hurts: four accumulators and
 * a stride of four can stop C2 recognising the loop as a reduction it may
 * vectorise, and a plainer loop can then be faster.
 *
 * <p>Every variant here answers the same question, except {@code orRaw},
 * which is deliberately conservative: it may say yes for a run that holds no
 * surrogate, which costs a validation pass and never costs correctness.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class SurrogateFilter {

    @Param({"64", "1024", "65536"})
    int length;

    char[] chars;

    @Setup
    public void setup() {
        String alphabet = "abcdefghij€αβ漢字0123456789 ";
        chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = alphabet.charAt(i % alphabet.length());
        }
        // Equivalence, checked rather than assumed: no surrogate present.
        if (unrolled4() || singleAccumulator() || maxReduction() || orRaw()) {
            throw new AssertionError("false positive on a clean run");
        }
        // And with one, at the far end, where a filter that stops early would miss it.
        char keep = chars[length - 1];
        chars[length - 1] = '\uD800';
        if (!unrolled4() || !singleAccumulator() || !maxReduction() || !orRaw()) {
            throw new AssertionError("missed a surrogate");
        }
        chars[length - 1] = keep;
    }

    /** What the branch ships today: four accumulators, stride of four. */
    @Benchmark
    public boolean unrolled4() {
        char[] a = chars;
        int p = 0, q = 0, r = 0, s = 0;
        int i = 0;
        int to = a.length;
        for (; i <= to - 4; i += 4) {
            p |= a[i] + 0x2800;
            q |= a[i + 1] + 0x2800;
            r |= a[i + 2] + 0x2800;
            s |= a[i + 3] + 0x2800;
        }
        int carry = p | q | r | s;
        for (; i < to; i++) {
            carry |= a[i] + 0x2800;
        }
        return (carry >>> 16) != 0;
    }

    /** The same carry test, written as the plain reduction C2 knows how to vectorise. */
    @Benchmark
    public boolean singleAccumulator() {
        char[] a = chars;
        int carry = 0;
        for (int i = 0; i < a.length; i++) {
            carry |= a[i] + 0x2800;
        }
        return (carry >>> 16) != 0;
    }

    /** A max reduction: no surrogate iff the largest code unit is below U+D800. */
    @Benchmark
    public boolean maxReduction() {
        char[] a = chars;
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, a[i]);
        }
        return max >= 0xD800;
    }

    /** Conservative: no add, just an OR. May say yes for a clean run. */
    @Benchmark
    public boolean orRaw() {
        char[] a = chars;
        int or = 0;
        for (int i = 0; i < a.length; i++) {
            or |= a[i];
        }
        return or >= 0xD800;
    }
}
