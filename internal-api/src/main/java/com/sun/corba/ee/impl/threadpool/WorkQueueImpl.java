/*
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates.
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

package com.sun.corba.ee.impl.threadpool;

import com.sun.corba.ee.spi.threadpool.ThreadPool;
import com.sun.corba.ee.spi.threadpool.Work;
import com.sun.corba.ee.spi.threadpool.WorkQueue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.glassfish.gmbal.Description ;
import org.glassfish.gmbal.ManagedAttribute ;
import org.glassfish.gmbal.NameValue ;

/**
 * The queue between the threads that accept work - the selector, a
 * connection's reader - and the pool's worker threads.
 *
 * <p>Every request the ORB dispatches passes through here, usually twice, and
 * every fragment of a fragmented message once more. This used to be a
 * LinkedList guarded by the queue's monitor, with wait/notify for the hand-off,
 * and the pool's thread counters were guarded by the same monitor; a profile of
 * small remote calls put some sixteen percent of server CPU time in it.
 *
 * <p>No lock is taken here now. Work goes into a ConcurrentLinkedQueue that
 * any worker takes from, and idle workers wait parked on a Treiber stack.
 *
 * <p>That any worker may take the next item matters as much as the absence of
 * a lock. A worker that has just finished an item comes straight back and
 * takes the next one, without anybody being woken - the monitor allowed that
 * too. A queue that hands each item to a parked thread instead, as
 * LinkedTransferQueue does, puts a thread wake-up on the critical path of
 * every item: measured with 64 KB messages in 1 KB fragments, where the
 * fragments of one message follow each other through here, that cost 14
 * percent of throughput against the monitor.
 *
 * <p>Waking follows the scheme of ForkJoinPool. addWork wakes one parked
 * worker only when no worker is searching the queue, since a searching worker
 * will find the item; and a worker that takes an item and still sees work
 * queued wakes one more, so that a burst spreads over the pool instead of
 * waiting behind one thread. A worker publishes itself on the stack, stops
 * counting as searching, and only then looks at the queue a last time before
 * it parks; addWork enqueues and only then looks at the searching count and
 * the stack. With sequentially consistent atomics one of the two sees the
 * other, so an item is never left with every worker asleep.
 *
 * <p>The pool's policy is unchanged: a worker thread is added when queued work
 * finds no worker to wake and fewer threads are available than items queued,
 * up to the maximum; a worker that waits a whole inactivity timeout for nothing
 * ends, unless that would leave no more idle threads than the minimum.
 */
public class WorkQueueImpl implements WorkQueue
{
    public static final String WORKQUEUE_DEFAULT_NAME = "default-workqueue";

    final private ConcurrentLinkedQueue<Work> queue = new ConcurrentLinkedQueue<>();

    // ConcurrentLinkedQueue.size() walks the queue; the pool policy needs the
    // count on every addWork.
    final private AtomicInteger queued = new AtomicInteger();

    // Workers looking at the queue, about to take an item or to park.
    final private AtomicInteger searching = new AtomicInteger();

    // Parked workers, newest first: the most recently active thread is the
    // one woken, which keeps the others idle long enough to time out.
    final private AtomicReference<Waiter> idle = new AtomicReference<>();

    private volatile ThreadPool workerThreadPool;
    final private LongAdder workItemsAdded = new LongAdder();
    final private LongAdder workItemsDequeued = new LongAdder();
    final private LongAdder totalTimeInQueue = new LongAdder();

    // Name of the work queue
    final private String name;

    // Test seam: run by a worker whose wait has just timed out, before it
    // decides whether to end. Only the timeout path reads it.
    volatile Runnable afterTimeoutForTesting;

    // Test seam: run by a worker that found the queue empty and still counts
    // as searching, before it publishes itself as idle.
    volatile Runnable afterEmptyLookForTesting;

    /** A parked worker. Its state moves once, from WAITING, by compare-and-set. */
    private static final class Waiter {
        static final int WAITING = 0;
        static final int CLAIMED = 1;     // a producer took it and unparks it
        static final int CANCELLED = 2;   // it left by itself: work found or timed out

        static final VarHandle STATE;
        static {
            try {
                STATE = MethodHandles.lookup().findVarHandle(Waiter.class, "state", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final Thread thread = Thread.currentThread();
        volatile int state = WAITING;
        Waiter next;

        boolean claim() {
            return STATE.compareAndSet(this, WAITING, CLAIMED);
        }

        boolean cancel() {
            return STATE.compareAndSet(this, WAITING, CANCELLED);
        }
    }

    public WorkQueueImpl() {
        this.name = WORKQUEUE_DEFAULT_NAME;
    }

    public WorkQueueImpl(ThreadPool workerThreadPool) {
        this(workerThreadPool, WORKQUEUE_DEFAULT_NAME);
    }

    public WorkQueueImpl(ThreadPool workerThreadPool, String name) {
        this.workerThreadPool = workerThreadPool;
        this.name = name;
    }

    public void addWork(Work work) {
        workItemsAdded.increment();
        work.setEnqueueTime(System.currentTimeMillis());

        queue.offer(work);
        queued.incrementAndGet();

        if (searching.get() == 0) {
            signal();
        }
    }

    /**
     * Gets a thread onto the queued work: wakes a parked worker, or failing
     * that adds a thread when fewer threads are available than items queued.
     * Called when nobody is searching, by addWork and by a worker that took
     * an item and left others behind - so growth, like waking, propagates.
     */
    private void signal() {
        if (wakeIdleWorker()) {
            return;
        }
        ThreadPool pool = workerThreadPool;
        if (pool.numberOfAvailableThreads() < queued.get()) {
            // NOTE: It is possible that the Work that was just added may unblock
            //       Worker Threads waiting on the Work just added and all Worker
            //       Threads are busy, (blocked & waiting for a response). This
            //       situation can lead to a deadlock.  The solution to such a
            //       a problem should it occur is to increase the maximum number
            //       of threads.
            ((ThreadPoolImpl) pool).createWorkerThreadIfBelowMaximum();
        }
    }

    /** Wakes the most recently parked worker, if there is one. */
    private boolean wakeIdleWorker() {
        Waiter waiter;
        while ((waiter = pop()) != null) {
            if (waiter.claim()) {
                LockSupport.unpark(waiter.thread);
                return true;
            }
            // Cancelled: it found work or timed out by itself. Skip it.
        }
        return false;
    }

    private void push(Waiter waiter) {
        Waiter head;
        do {
            head = idle.get();
            waiter.next = head;
        } while (!idle.compareAndSet(head, waiter));
    }

    private Waiter pop() {
        Waiter head;
        do {
            head = idle.get();
            if (head == null) {
                return null;
            }
        } while (!idle.compareAndSet(head, head.next));
        return head;
    }

    // Takes a cancelled waiter off the stack if it is still on top, so that
    // workers that find work on their last look do not pile up nodes for
    // producers to skip. Best effort: nodes further down are skipped by pop.
    private void unlinkIfTop(Waiter waiter) {
        idle.compareAndSet(waiter, waiter.next);
    }

    private Work take() {
        Work work = queue.poll();
        if (work != null) {
            queued.decrementAndGet();
            workItemsDequeued.increment();
            totalTimeInQueue.add(System.currentTimeMillis() - work.getEnqueueTime());
        }
        return work;
    }

    // Called with the taken item: if more are queued and nobody is looking,
    // get another thread onto them.
    private Work took(Work work) {
        if (queued.get() > 0 && searching.get() == 0) {
            signal();
        }
        return work;
    }

    /**
     * Waits up to waitTime milliseconds for a work item.
     *
     * @return the work item, or null when the wait timed out and this thread
     *         should wait again
     * @throws WorkerThreadNotNeededException when the wait timed out and the
     *         pool has idle threads to spare; the thread must end
     */
    Work requestWork(long waitTime) throws WorkerThreadNotNeededException,
        InterruptedException {

        ThreadPoolImpl pool = (ThreadPoolImpl) workerThreadPool;
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitTime);

        searching.incrementAndGet();
        while (true) {
            Work work = take();
            if (work != null) {
                searching.decrementAndGet();
                return took(work);
            }

            Runnable emptyLook = afterEmptyLookForTesting;
            if (emptyLook != null) {
                emptyLook.run();
            }

            Waiter waiter = new Waiter();
            push(waiter);
            pool.incrementNumberOfAvailableThreads();
            searching.decrementAndGet();

            // The last look, after publishing: see the class comment.
            work = take();
            if (work != null) {
                if (waiter.cancel()) {
                    unlinkIfTop(waiter);
                }
                pool.decrementNumberOfAvailableThreads();
                return took(work);
            }

            long remaining;
            while (waiter.state == Waiter.WAITING
                    && (remaining = deadline - System.nanoTime()) > 0) {
                LockSupport.parkNanos(this, remaining);
                if (Thread.interrupted()) {
                    if (waiter.cancel()) {
                        unlinkIfTop(waiter);
                    }
                    pool.decrementNumberOfAvailableThreads();
                    throw new InterruptedException();
                }
            }

            if (waiter.state == Waiter.CLAIMED || !waiter.cancel()) {
                // Woken for work: look again.
                pool.decrementNumberOfAvailableThreads();
                searching.incrementAndGet();
                continue;
            }
            unlinkIfTop(waiter);

            // Timed out, and still counted as available while deciding
            // whether to end: two threads timing out together must not both
            // conclude that the other one keeps the pool above its minimum.
            // Ending takes a successful compare-and-set on the available
            // count, which only one of them can win while the count is just
            // above the minimum.
            Runnable hook = afterTimeoutForTesting;
            if (hook != null) {
                hook.run();
            }

            if (pool.tryRetireAvailableThread()) {
                if (queued.get() == 0) {
                    // This thread has timed out and can die because
                    // we have enough available idle threads.
                    // NOTE: It is expected that the WorkerThread calling this
                    //       method will gracefully exit as a result of
                    //       catching the WorkerThreadNotNeededException.
                    pool.decrementCurrentNumberOfThreads();
                    throw new WorkerThreadNotNeededException();
                }
                // Work arrived as the wait ran out. The retirement already
                // took this thread off the available count.
                return null;
            }

            pool.decrementNumberOfAvailableThreads();
            return null;
        }
    }

    public void setThreadPool(ThreadPool workerThreadPool) {
        this.workerThreadPool = workerThreadPool;
    }

    public ThreadPool getThreadPool() {
        return workerThreadPool;
    }

    /**
     * Returns the total number of Work items added to the Queue.
     */
    @ManagedAttribute
    @Description( "Total number of items added to the queue" )
    public long totalWorkItemsAdded() {
        return workItemsAdded.sum();
    }

    /**
     * Returns the total number of Work items in the Queue to be processed.
     */
    @ManagedAttribute
    @Description( "Total number of items in the queue to be processed" )
    public int workItemsInQueue() {
        return queued.get();
    }

    /**
     * Returns the average amount Work items have spent in the Queue waiting
     * to be processed.
     */
    @ManagedAttribute
    @Description( "Average time work items spend waiting in the queue in milliseconds" )
    public long averageTimeInQueue() {
        long dequeued = workItemsDequeued.sum();
        if (dequeued == 0) {
            return 0 ;
        } else {
            return (totalTimeInQueue.sum()/dequeued);
        }
    }

    @NameValue
    public String getName() {
        return name;
    }
}

// End of file.
