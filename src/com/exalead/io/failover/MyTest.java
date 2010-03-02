package com.exalead.io.failover;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpVersion;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;


public class MyTest {
    private static Logger logger = Logger.getLogger("log");

    /**
     * @param args
     */
    NoRetryFailoverHttpClient relay;

    class MyThread extends Thread {
        public void run() {
            while (true) {
                try {Thread.sleep(300);} catch (InterruptedException e) {}

                GetMethod httpMethod = new GetMethod("/exascript/Ping");
                try {
                    logger.info("********** START method");
                    int retcode = relay.executeMethod(httpMethod);
                    logger.info("********** DONE");
                    InputStream is = httpMethod.getResponseBodyAsStream();
                    is.close();
                } catch (IOException e) {
                    logger.warn("**************** " + System.currentTimeMillis() + ": MAIN EXCEPTION", e);
                }
            }    

        }
    }

    public void run() throws Exception {
        BasicConfigurator.configure();
        Logger.getLogger("org").setLevel(Level.INFO);
        Logger.getLogger("httpclient").setLevel(Level.INFO);
       // Logger.getLogger("org").setLevel(Level.TRACE);

        relay = new NoRetryFailoverHttpClient();
        relay.addHost("localhost", 31604, 1);
        relay.addHost("localhost", 31616, 1);
        PoolMonitoringThread pmt = new PoolMonitoringThread();
        pmt.pool = relay.manager;
        pmt.start();
        
        List<MyThread> threads = new ArrayList<MyThread>();
        for (int i = 0; i < 12; i++) {
            MyThread t = new MyThread();
            t.start();
            threads.add(t);
        }
        for (MyThread mt:  threads) {
            mt.join();
        }
    }

    public static void main(String[] args) throws Exception {
        new MyTest().run();
    }
}