package com.exalead.io.failover;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.httpclient.ConnectTimeoutException;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpConnection;
import org.apache.commons.httpclient.HttpState;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.util.IdleConnectionHandler;
import org.apache.log4j.Logger;

/**
 * @file
 * This class is largely derived of the MonitoredHttpConnectionManager.
 * Copyright the Apache Software Foundation.
 * Licensed under the Apache License 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The MultiHostConnectionPool keeps pools of open connections to each member of the pool.
 * It uses background threads to monitor each connection and each member of the pool and performs
 * dispatch of connections 
 */
public class MultiHostConnectionPool {
    /* **************** Static helpers ******************** */

    private static final Logger LOG = Logger.getLogger("monitored");

    /**
     * Gets the host configuration for a connection.
     * @param conn the connection to get the configuration of
     * @return a new HostConfiguration
     */
    static HostConfiguration rebuildConfigurationFromConnection(HttpConnection conn) {
        HostConfiguration connectionConfiguration = new HostConfiguration();
        connectionConfiguration.setHost(
                conn.getHost(), 
                conn.getPort(), 
                conn.getProtocol()
        );
        if (conn.getLocalAddress() != null) {
            connectionConfiguration.setLocalAddress(conn.getLocalAddress());
        }
        if (conn.getProxyHost() != null) {
            connectionConfiguration.setProxy(conn.getProxyHost(), conn.getProxyPort());
        }
        return connectionConfiguration;
    }

    /** The list of all hosts in the pool */
    List<HostState> hosts;
    /** Fast access map by configuration */
    Map<HostConfiguration, HostState> hostsMap;

    private IdleConnectionHandler idleConnectionHandler = new IdleConnectionHandler();        

    /**
     * Cleans up all connection pool resources.
     */
    public synchronized void shutdown() {
        //        // close all free connections
        //        Iterator<HttpConnection> iter = freeConnections.iterator();
        //        while (iter.hasNext()) {
        //            HttpConnection conn = iter.next();
        //            iter.remove();
        //            conn.close();
        //        }
        //
        //        // interrupt all waiting threads
        //        Iterator<WaitingThread> itert = waitingThreads.iterator();
        //        while (itert.hasNext()) {
        //            WaitingThread waiter = itert.next();
        //            iter.remove();
        //            waiter.interruptedByConnectionPool = true;
        //            waiter.thread.interrupt();
        //        }
        //
        //        // clear out map hosts
        //        mapHosts.clear();
        //        // remove all references to connections
        //        idleConnectionHandler.removeAll();
    }

    /* *************************** Location helpers ************************* */

    /** Find a Host given its configuration. Must be called with the lock */
    private HostState getHostFromConfiguration(HostConfiguration config) {
        return hostsMap.get(config);
    }

    /* *************************** Hosts round-robin dispatch ************************* */

    /** Index of the next host in the list */
    private int nextHost;
    /** Number of dispatches already performed on current host */
    private int dispatchesOnCurrentHost;

    /** 
     * Get the host to use for next connection. 
     * It performs round-robin amongst currently alive hosts, respecting the power property.
     * This method must be called with the pool lock. 
     */
    private HostState getNextRoundRobinHost() {
        // TODO
        return null;
    }

    /**
     * Get the host that should be used next in the round-robin dispatch
     * @return a HostConfiguration that should be passed to the HttpClient
     */
    public HostConfiguration getHostToUse() {
        synchronized(this) { 
            HostState hs = getNextRoundRobinHost();
            return hs.configuration;
        }
    }

    /** We synchronously check the connection if its check is more than this delay old */
    long maxCheckDelayWithoutSynchronousCheck = 1000;
    int connectionTimeout = 500;
    int isAliveTimeout = 500;
    int applicativeTimeout = 5000;

    /** 
     * Returns "true" if host is up and alive.
     * Returns "false" if host is up but not alive
     * Throws an exception in case of connection error
     */
    boolean checkConnection(MonitoredConnection connection) throws IOException {
        /* TODO: HANDLE really state */
        HttpState hs = new HttpState();
        GetMethod gm = new GetMethod(connection.host.getURI() + "/isAlive");
        connection.conn.setSocketTimeout(isAliveTimeout);
        int statusCode = gm.execute(hs, connection.conn);
        return statusCode < 400;
    }

    /**
     * Creates a new connection and returns it for use of the calling method.
     * This method should only be called by the HttpClient  
     *
     * @param hostConfiguration the configuration for the connection
     * @return a new connection or <code>null</code> if none are available
     */
    public HttpConnection createConnection(HostConfiguration hostConfiguration) throws IOException {
        HostState host = null;
        synchronized(this) {
            host = getHostFromConfiguration(hostConfiguration);
        }

        /* We are now going to loop until:
         *  - We have noticed that host is down -> fail
         *  - We have found a suitable connection:
         *      - either a recently checked connection
         *      - or an old connection that we synchronously recheck 
         *  - There is no more waiting connection and:
         *      - we create one right now and it works
         *      - we create one right now and it fails -> host is down
         *  - The number of iterations is limited to avoid potential
         *    race condition between monitoring thread(s) and this loop 
         */

        MonitoredConnection c = null;
        for (int curLoop = 0; curLoop < 10; curLoop++) {
            boolean needSynchronousCheck = false;

            /* Try to select an existing connection */
            synchronized(this) {
                long now = System.currentTimeMillis();

                if (host.down) {
                    throw new IOException("Host is down (marked as down)");
                }

                long minDate = now - maxCheckDelayWithoutSynchronousCheck;
                List<MonitoredConnection> recentlyChecked = host.getRecentlyCheckedConnections(minDate);
                if (recentlyChecked.size() == 0) {
                    /* There is no recently checked connection */
                    needSynchronousCheck = true;
                    /* Let's help a bit the monitoring thread by taking a connection that was not checked recently */
                    c = host.getOldestCheckedConnection();
                } else {
                    /** TODO: better scheduling ? */
                    c = recentlyChecked.get(0);
                }

                if (c != null) {
                    host.removeFreeConnection(c);
                }
            }

            /* There was no free connection for this host, so let's connect now */
            if (c == null) {
                needSynchronousCheck = false;
                try {
                    c = host.connect(connectionTimeout);
                } catch (IOException e) {
                    /* In that case, we don't care if it's a fail or timeout:
                     * we can't connect to the host in time, so the host is down.
                     */
                    synchronized(this) {
                        host.down = true;
                        // If the host has connections, it means that they were 
                        // established while we were trying to connect. .. So maybe the host
                        // is not down, but to be sure, let's still consider as down and 
                        // kill everything
                        host.killAllConnections();
                        throw new IOException("Host is down (couldn't connect)");
                    }
                }
            }

            /* The connection we got was too old, perform a check */
            if (needSynchronousCheck) {
                try {
                    boolean ret = checkConnection(c);
                    /* Host is up but not alive: just kill all connections.
                     * It's useless to try another connection: host knows it's not alive
                     */

                    if (ret == false) {
                        synchronized(this) {
                            host.down = true;
                            host.killAllConnections();
                            throw new IOException("Host is down (not alive)");
                        }
                    } else {
                        // Great, we have a working connection !
                        break;
                    }
                } catch (IOException e) {
                    synchronized(this) {
                        if (e instanceof SocketTimeoutException) {
                            /* Timeout while trying to get isAlive -> host is hanged.
                             * Don't waste time checking connections, we would just timeout more.
                             * So, kill everything
                             */
                            host.down = true;
                            host.killAllConnections();
                            /* Don't forget to close this connection to avoid FD leak */
                            c.conn.close();
                            throw new IOException("Host is down (isAlive timeout)");
                        } else {
                            /* Connection failure. Server looks down (connection reset by peer). But it could
                             * be only that connection which failed (TCP timeout for example). In that case, 
                             * we try to fast-kill the stale connections and we retry. Either there are still
                             * some alive connections and we can retry with them, or the loop will try to 
                             * reconnect and success (for example, the host went down and up very fast) or fail 
                             */
                            host.killStaleConnections();
                            /* Don't forget to close this connection to avoid FD leak */
                            c.conn.close();
                            continue;
                        }
                    }
                }
            } else {
                /* We don't need a synchronous check -> Great, we have a working connection ! */
                break;
            }
        }

        /* End of the loop */
        if (c == null) {
            throw new Error("Unexpected null connection out of the loop. Bad escape path ?");
        }

        c.conn.setSocketTimeout(applicativeTimeout);

        host.inFlightConnections++;

        /* TODO: 
         *         connection.getParams().setDefaults(parent.getParams());
         * connection.setHttpConnectionManager(parent);
         * numConnections++;
         * hostPool.numConnections++;
         */

        /* We loose the association with the monitored connection, we'll recreate one at release time */
        return c.conn;
    }

    public enum FailureType {
        OK,
        TIMEOUT,
        OTHER_ERROR
    }

    static ThreadLocal<FailureType> nextReleasedConnectionFailureType = new ThreadLocal<FailureType>();

    public void onHostFailure(HostConfiguration host, FailureType type) {
        nextReleasedConnectionFailureType.set(type);
    }

    /**
     * Marks the given connection as free.
     * @param conn a connection that is no longer being used
     */
    public void releaseConnection(HttpConnection conn) {
        /* Find the host state for this connection */
        HostConfiguration config = rebuildConfigurationFromConnection(conn);
        HostState host = null;
        synchronized(this) {
            host = getHostFromConfiguration(config);
        }
        
        /* Rebuild the MonitoredConnection object */
        MonitoredConnection mc = new MonitoredConnection();
        mc.conn = conn;
        mc.host = host;

        if (nextReleasedConnectionFailureType.get() == null) {
            nextReleasedConnectionFailureType.set(FailureType.OK);
        }

        switch(nextReleasedConnectionFailureType.get()) {
        case TIMEOUT:
            /* This case is the most tricky.
             * But maybe it was because the remote host is down.
             * So let's take an average path: we mark all free connections for this
             * host as very old so that they get rechecked before any attempt
             * to use them.
             */
            synchronized(this) {
                host.markConnectionsAsUnchecked();
            }
            /* As a safety measure, we kill this connection and don't enqueue it */
            mc.conn.close();
            break;
            
        case OTHER_ERROR:
            /* Connection is dead, so we fast-kill the stale connections and we
             * schedule the server for monitoring ASAP.
             */
            synchronized(this) {
                host.killStaleConnections();
                setNextToMonitor(host);
            }
            /* And don't forget to kill this connection */
            mc.conn.close();
            break;
            
        case OK:
            long now = System.currentTimeMillis();
            synchronized(this) {
                mc.lastMonitoringTime = now;
                mc.lastUseTime = now;
                host.addFreeConnection(mc);
            }
            break;

        }


    }
    
    
    /* *************************** Hosts monitoring scheduler ************************* */
    
    LinkedList<HostState> nextToMonitorList = new LinkedList<HostState>();
    LinkedList<HostState> alreadyMonitored = new LinkedList<HostState>();
    void setNextToMonitor(HostState host) {

        /* Remove the host from the two lists */
        nextToMonitorList.remove(host);
        alreadyMonitored.remove(host);
        /* And put it at front of next */
        nextToMonitorList.addFirst(host);
    }
    
    HostState nextToMonitor() {
        if (nextToMonitorList.size() + alreadyMonitored.size() != hosts.size()){
            throw new Error("Inconsistent monitoring lists !!");
        }
       
        /* Swap buffers */
        if (nextToMonitorList.size() == 0) {
            LinkedList<HostState> tmp = nextToMonitorList;
            nextToMonitorList = alreadyMonitored;
            alreadyMonitored = tmp;
        }
       
        HostState next = nextToMonitorList.removeFirst();
        alreadyMonitored.addLast(next);
        return next;
    }
    
    


/* ***************** Main monitoring thread ***************** */

/* The HTTPClient API does not give access to the connection itself, so we need to do the 
 * client-side code in two steps:
 *  - Get the identifier of a host
 *  - Ask HTTPClient to query it
 *     HTTPClient will call our acquireConnection() callback
 * 
 * If an error occurs, HTTPClient rethrows it, so in that case, we call hostFailure before
 * releasing the connection to HTTPClient. HTTPClient will then call our releaseConnection() callback
 */

//    Host getHostToUse() {
//        return hosts.filter(alive=true).roundRobin();
//    }
//
//    Connection acquire(Host host) {
//        while (true) {
//            boolean needSynchronousCheck;
//            Connection c;
//            ACQUIRE
//            {
//                if (host.down) {
//                    throw Exception("host is down");
//                }
//
//                Connection[] recentlyChecked = host.connections.filter(lastChecked < 1000ms);
//                if (recentlyChecked.length == 0) {
//                    needsSynchronousCheck = true;
//                    c = host.connections.get(oldest);
//                } else if (host.connections.length > 0) {
//                    c = recentlyChecked.get(oldest);
//                }
//            }
//            RELEASE
//
//            /* There was no connection active for the host, so connect now */
//            if (c == null) {
//                needSynchronousCheck = false;
//                c = connect(connectTimeout)
//                if (failed) {
//                    ACQUIRE
//                    {
//                        host.down = true;
//                        // If the host has connections, it means that they were 
//                        // established while we were trying to connect. .. So maybe the host
//                        // is not down, but to be sure, let's still consider as down and 
//                        // kill everything
//                        killAllHostConnections(host);
//                        throw Exception("host is down");
//                    } 
//                    RELEASE
//                }
//            }
//
//            /* The connection we got was too old, perform a quick check */
//            if (needSynchronousCheck) {
//                setIsAliveTimeout();
//                /* Fast check */
//                performHTTPOPTIONS(c);
//                if (ok) {
//                    break;
//                } else if (timeout) {
//                    ACQUIRE
//                    {
//                        /* Don't waste time, server is hanged */
//                        host.down = true;
//                        killAllHostConnections(host);
//                        throw Exception("host is down");
//                    }
//                    RELEASE
//                } else if (fail) {
//                    ACQUIRE
//                    {
//                        /* Server looks down (connection RST ?), try to fast kill the connections */
//                        killTCPResetConnections(host);
//                        killConnection(c);
//                        /* But we still retry with other connections OR retry a reconnect.*
//                         * The loop will either run until we're out of potential victims or a connection
//                         * works
//                         */
//                        host.down = true;
//                        killAllHostConnections(host);
//                        continue;
//                    }
//                    RELEASE
//                }
//            }
//        }
//        setApplicativeTimeout(c);
//        return c;
//    }
//
//    static local nextConnectionIsDead = { FALSE, TIMEOUT, FAILED }
//    /** The client tells us about the failure of a host */
//    void hostFailure(host, typeOfailure) {
//        nextConnectionIsDead = typeOfFailure;
//    }
//
//    void releaseConnection(Connection c) {
//        if (nextConnectionIsDead == FAILED) {
//            ACQUIRE
//            {
//                /* Ok, so c is brain dead */
//                // Fast-kill the RST connections and schedule this host for immediate
//                // check by the background thread
//                killTCPResetConnections(host);
//                setNextToMonitor(host);
//            }
//            RELEASE
//        } else if (nextConnectionIsDead == TIMEOUT) {
//            ACQUIRE
//            {
//                /* This one is the most tricky. 
//                 * Maybe we timeouted because the query was too complex.
//                 * But maybe it was because the remote host is down.
//                 * So let's take an average path: we mark all connections for this
//                 * host as very old so that they get rechecked before any attempt
//                 * to use them.
//                 */
//                foreach (freeConnection){ lastChecked = 0; }
//            }
//            RELEASE
//        } else {
//            connection.lastUsed = now();
//            /* It was just used successfully so mark it as checked */
//            connection.lastChecked = now();
//        }
//
//
//        /* Reset the thread-local signalling variable */
//        nextConnectionIsDead = FALSE;
//    }
//
//    class MonitoringThread extends Thread {
//        public void run() {
//            while (!stop) {
//                monitorLoop();
//            }
//        }
//        
//        public void monitorLoop() {
//            ACQUIRE
//            {
//                HostState hs = nextToMonitor();
//
//                Connection c;
//                if (freeConnections[host] != 0) {
//                    c = removeOldestConnectionFromFree();
//                }
//            }
//            RELEASE
//
//            /* No current connection for this host, need to connect */
//            if (c == null) {
//                try {
//                    c = connect(isAliveTimeout);
//                } onfail {
//                    ACQUIRE
//                    host.down = true;
//                    killAllConnections() // See comment about concurrent connect in acquire()
//                    RELEASE
//                    goto sleep;
//                } ontimeout {
//                    ACQUIRE
//                    host.down = true;
//                    killAllConnections() // See comment about concurrent connect in acquire()
//                    RELEASE
//                    goto sleep;
//                }
//            }
//
//            setIsAliveTimeout();
//            validateServerUsingConnection(c);
//
//            ACQUIRE
//            {
//                if (explicitFailure) {
//                    /* Explicit error: connection reset ?
//                     * There is only a low probability that just this connection is 
//                     * in bad state, the host is probably down.
//                     * Let's do a quick pruning of connections that 
//                     * want to die by using the setTimeout(1); peekByte(); method.
//                     * 
//                     * This can't be very long (at most nfreeconnection * 1ms) so let's keep
//                     * the lock for simplicity.
//                     */
//                    killTCPResetConnections(host);
//                    /* Then, reschedule this host for immediate recheck */
//                    setNextToMonitor(host);
//                    continue;
//                } else if (timeout) {
//                    /* Server hanged ? Checking all connections could be too costly
//                     * so kill all connections. We'll retry later
//                     */
//                    closeAllConnections(host);
//                    host.down = true;
//                    goto sleep;
//                } else if (success) {
//                    host.lastChecked = now();
//                    connection.lastChecked = now();
//                }
//            }
//            RELEASE
//        }
//
//    }
}
