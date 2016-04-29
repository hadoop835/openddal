/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.openddal.jdbcx;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.Reference;
import javax.naming.spi.ObjectFactory;

import com.openddal.jdbc.JdbcDriver;
import com.openddal.message.Trace;
import com.openddal.message.TraceSystem;

/**
 * This class is used to create new DataSource objects.
 * An application should not use this class directly.
 */
public class JdbcDataSourceFactory implements ObjectFactory {

    private static TraceSystem cachedTraceSystem;
    private final Trace trace;

    static {
        JdbcDriver.load();
    }

    /**
     * The public constructor to create new factory objects.
     */
    public JdbcDataSourceFactory() {
        trace = getTraceSystem().getTrace(Trace.JDBC);
    }

    /**
     * Creates a new object using the specified location or reference
     * information.
     *
     * @param obj the reference (this factory only supports objects of type
     *            javax.naming.Reference)
     * @param name unused
     * @param nameCtx unused
     * @param environment unused
     * @return the new JdbcDataSource, or null if the reference class name is
     *         not JdbcDataSource.
     */
    @Override
    public synchronized Object getObjectInstance(Object obj, Name name,
            Context nameCtx, Hashtable<?, ?> environment) {
        if (trace.isDebugEnabled()) {
            trace.debug("getObjectInstance obj={0} name={1} " +
                    "nameCtx={2} environment={3}", obj, name, nameCtx, environment);
        }
        if (obj instanceof Reference) {
            Reference ref = (Reference) obj;
            if (ref.getClassName().equals(JdbcDataSource.class.getName())) {
                JdbcDataSource dataSource = new JdbcDataSource();
                dataSource.setURL((String) ref.get("url").getContent());
                dataSource.setUser((String) ref.get("user").getContent());
                dataSource.setPassword((String) ref.get("password").getContent());
                dataSource.setDescription((String) ref.get("description").getContent());
                String s = (String) ref.get("loginTimeout").getContent();
                dataSource.setLoginTimeout(Integer.parseInt(s));
                return dataSource;
            }
        }
        return null;
    }

    /**
     * INTERNAL
     */
    public static TraceSystem getTraceSystem() {
        synchronized (JdbcDataSourceFactory.class) {
            if (cachedTraceSystem == null) {
                cachedTraceSystem = new TraceSystem();
            }
            return cachedTraceSystem;
        }
    }

    Trace getTrace() {
        return trace;
    }

}
