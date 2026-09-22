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

import jakarta.ejb.ConcurrencyManagement;
import jakarta.ejb.ConcurrencyManagementType;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionManagement;
import jakarta.ejb.TransactionManagementType;

/**
 * GreeterBean as lean as the container allows: one instance, so no pool;
 * bean managed concurrency, so no container lock around each call - the
 * methods keep no state; and bean managed transactions, so no transaction
 * per call. The IIOP security interceptors and the authorization check
 * still run, as they do for every remote EJB call.
 */
// Its own mappedName: GlassFish otherwise also binds every remote view under
// the interface name, which GreeterBean already holds.
@Singleton(mappedName = "orb-baseline/GreeterSingletonBean")
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
@TransactionManagement(TransactionManagementType.BEAN)
// implements Greeter again: business interfaces come from the bean class's own
// implements clause, not from its superclass.
public class GreeterSingletonBean extends GreeterBean implements Greeter {
}
