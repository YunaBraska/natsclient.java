// Copyright 2015-2018 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.NatsTestServer;

public class SimpleDispatcherTests {

    @Test
    public void testSingleMessage() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        try (NatsTestServer ts = new NatsTestServer(false)) {
            Connection nc = Nats.connect("nats://localhost:" + ts.getPort());
            assertTrue("Connected Status", Connection.Status.CONNECTED == nc.getStatus());

            final CompletableFuture<Message> msgFuture = new CompletableFuture<>();
            Dispatcher d = nc.createDispatcher((msg) -> {
                msgFuture.complete(msg);
            });

            d.subscribe("subject");

            nc.publish("subject", new byte[16]);

            Message msg = msgFuture.get(500, TimeUnit.MILLISECONDS);

            assertEquals("subject", msg.getSubject());
            assertNotNull(msg.getSubscription());
            assertNull(msg.getReplyTo());
            assertEquals(16, msg.getData().length);

            nc.close();
            assertTrue("Closed Status", Connection.Status.CLOSED == nc.getStatus());
        }
    }

    @Test
    public void testMultiMessage() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        try (NatsTestServer ts = new NatsTestServer(false)) {
            final CompletableFuture<Boolean> done = new CompletableFuture<>();
            int msgCount = 100;
            Connection nc = Nats.connect("nats://localhost:" + ts.getPort());
            assertTrue("Connected Status", Connection.Status.CONNECTED == nc.getStatus());

            final ConcurrentLinkedQueue<Message> q = new ConcurrentLinkedQueue<>();
            Dispatcher d = nc.createDispatcher((msg) -> {
                if (msg.getSubject().equals("done")) {
                    done.complete(Boolean.TRUE);
                } else {
                    q.add(msg);
                }
            });

            d.subscribe("subject");
            d.subscribe("done");

            for (int i = 0; i < msgCount; i++) {
                nc.publish("subject", new byte[16]);
            }
            nc.publish("done", new byte[16]);

            nc.flush(Duration.ofMillis(1000)); // wait for them to go through
            done.get(200, TimeUnit.MILLISECONDS);
            
            assertEquals(msgCount, q.size());

            nc.close();
            assertTrue("Closed Status", Connection.Status.CLOSED == nc.getStatus());
        }
    }

    @Test(expected = IllegalStateException.class)
    public void testCantUnsubSubFromDispatcher()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        try (NatsTestServer ts = new NatsTestServer(false)) {
            Connection nc = Nats.connect("nats://localhost:" + ts.getPort());
            assertTrue("Connected Status", Connection.Status.CONNECTED == nc.getStatus());

            final CompletableFuture<Message> msgFuture = new CompletableFuture<>();
            Dispatcher d = nc.createDispatcher((msg) -> {
                msgFuture.complete(msg);
            });

            d.subscribe("subject");

            nc.publish("subject", new byte[16]);

            Message msg = msgFuture.get(500, TimeUnit.MILLISECONDS);

            msg.getSubscription().unsubscribe(); // Should throw
            assertFalse(true);
        }
    }

    @Test(expected = IllegalStateException.class)
    public void testCantAutoUnsubSubFromDispatcher()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        try (NatsTestServer ts = new NatsTestServer(false)) {
            Connection nc = Nats.connect("nats://localhost:" + ts.getPort());
            assertTrue("Connected Status", Connection.Status.CONNECTED == nc.getStatus());

            final CompletableFuture<Message> msgFuture = new CompletableFuture<>();
            Dispatcher d = nc.createDispatcher((msg) -> {
                msgFuture.complete(msg);
            });

            d.subscribe("subject");

            nc.publish("subject", new byte[16]);

            Message msg = msgFuture.get(500, TimeUnit.MILLISECONDS);

            msg.getSubscription().unsubscribe(1); // Should throw
            assertFalse(true);
        }
    }

    @Test
    public void testPublishAndFlushFromCallback()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        try (NatsTestServer ts = new NatsTestServer(false)) {
            final Connection nc = Nats.connect("nats://localhost:" + ts.getPort());
            assertTrue("Connected Status", Connection.Status.CONNECTED == nc.getStatus());

            final CompletableFuture<Message> msgFuture = new CompletableFuture<>();
            Dispatcher d = nc.createDispatcher((msg) -> {
                try {
                    nc.flush(Duration.ofMillis(1000));
                } catch (Exception ex) {
                    System.out.println("!!! Exception in callback");
                    ex.printStackTrace();
                }
                msgFuture.complete(msg);
            });

            d.subscribe("subject");

            nc.publish("subject", new byte[16]); // publish one to kick it off

            Message msg = msgFuture.get(500, TimeUnit.MILLISECONDS);
            assertNotNull(msg);

            assertEquals(1, nc.getStatistics().getFlushCounter());

            nc.close();
            assertTrue("Closed Status", Connection.Status.CLOSED == nc.getStatus());
        }
    }

    @Test
    public void testUnsub() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        try (NatsTestServer ts = new NatsTestServer(false)) {
            final CompletableFuture<Boolean> phase1 = new CompletableFuture<>();
            final CompletableFuture<Boolean> phase2 = new CompletableFuture<>();
            int msgCount = 10;
            Connection nc = Nats.connect("nats://localhost:" + ts.getPort());
            assertTrue("Connected Status", Connection.Status.CONNECTED == nc.getStatus());

            final ConcurrentLinkedQueue<Message> q = new ConcurrentLinkedQueue<>();
            Dispatcher d = nc.createDispatcher((msg) -> {
                if (msg.getSubject().equals("phase1")) {
                    phase1.complete(Boolean.TRUE);
                } else if (msg.getSubject().equals("phase2")) {
                    phase2.complete(Boolean.TRUE);
                } else {
                    q.add(msg);
                }
            });

            d.subscribe("subject");
            d.subscribe("phase1");
            d.subscribe("phase2");

            for (int i = 0; i < msgCount; i++) {
                nc.publish("subject", new byte[16]);
            }
            nc.publish("phase1", new byte[16]);
            nc.flush(Duration.ofMillis(1000)); // wait for them to go through
            phase1.get(500, TimeUnit.MILLISECONDS); // make sure we got them

            d.unsubscribe("subject");

            for (int i = 0; i < msgCount; i++) {
                nc.publish("subject", new byte[16]);
            }
            nc.publish("phase2", new byte[16]);
            nc.flush(Duration.ofMillis(1000)); // wait for them to go through
            phase2.get(500, TimeUnit.MILLISECONDS); // make sure we got them

            assertEquals(msgCount, q.size());

            nc.close();
            assertTrue("Closed Status", Connection.Status.CLOSED == nc.getStatus());
        }
    }

    @Test
    public void testAutoUnsub() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        try (NatsTestServer ts = new NatsTestServer(false)) {
            final CompletableFuture<Boolean> phase1 = new CompletableFuture<>();
            final CompletableFuture<Boolean> phase2 = new CompletableFuture<>();
            int msgCount = 10;
            Connection nc = Nats.connect("nats://localhost:" + ts.getPort());
            assertTrue("Connected Status", Connection.Status.CONNECTED == nc.getStatus());

            final ConcurrentLinkedQueue<Message> q = new ConcurrentLinkedQueue<>();
            NatsDispatcher d = (NatsDispatcher) nc.createDispatcher((msg) -> {
                if (msg.getSubject().equals("phase1")) {
                    phase1.complete(Boolean.TRUE);
                }else if (msg.getSubject().equals("phase2")) {
                    phase2.complete(Boolean.TRUE);
                } else {
                    q.add(msg);
                }
            });

            d.subscribe("subject");
            d.subscribe("phase1");
            d.subscribe("phase2");

            for (int i = 0; i < msgCount; i++) {
                nc.publish("subject", new byte[16]);
            }
            nc.publish("phase1", new byte[16]);

            nc.flush(Duration.ofMillis(1000)); // wait for them to go through
            phase1.get(200, TimeUnit.MILLISECONDS); // make sure we got them

            assertEquals(msgCount, q.size());

            d.unsubscribe("subject", msgCount + 1);

            for (int i = 0; i < msgCount; i++) {
                nc.publish("subject", new byte[16]);
            }
            nc.publish("phase2", new byte[16]);

            nc.flush(Duration.ofMillis(1000)); // Wait for it all to get processed
            phase2.get(200, TimeUnit.MILLISECONDS); // make sure we got them

            assertEquals(msgCount + 1, q.size());

            nc.close();
            assertTrue("Closed Status", Connection.Status.CLOSED == nc.getStatus());
        }
    }

    @Test
    public void testUnsubFromCallback() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        try (NatsTestServer ts = new NatsTestServer(false)) {
            final CompletableFuture<Boolean> done = new CompletableFuture<>();
            final Connection nc = Nats.connect("nats://localhost:" + ts.getPort());
            assertTrue("Connected Status", Connection.Status.CONNECTED == nc.getStatus());

            final AtomicReference<Dispatcher> dispatcher = new AtomicReference<>();
            final ConcurrentLinkedQueue<Message> q = new ConcurrentLinkedQueue<>();
            final Dispatcher d = nc.createDispatcher((msg) -> {
                if (msg.getSubject().equals("done")) {
                    done.complete(Boolean.TRUE);
                } else {
                    q.add(msg);
                    dispatcher.get().unsubscribe("subject");
                }
            });

            dispatcher.set(d);

            d.subscribe("subject");
            d.subscribe("done");

            nc.publish("subject", new byte[16]);
            nc.publish("subject", new byte[16]);
            nc.publish("done", new byte[16]); // when we get this we know the others are dispatched
            nc.flush(Duration.ofMillis(1000)); // Wait for the publish, or we will get multiples for sure
            done.get(200, TimeUnit.MILLISECONDS); // make sure we got them

            assertEquals(1, q.size());

            nc.close();
            assertTrue("Closed Status", Connection.Status.CLOSED == nc.getStatus());
        }
    }

    @Test
    public void testAutoUnsubFromCallback()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        try (NatsTestServer ts = new NatsTestServer(false)) {
            final CompletableFuture<Boolean> done = new CompletableFuture<>();
            final Connection nc = Nats.connect("nats://localhost:" + ts.getPort());
            assertTrue("Connected Status", Connection.Status.CONNECTED == nc.getStatus());

            final AtomicReference<Dispatcher> dispatcher = new AtomicReference<>();
            final ConcurrentLinkedQueue<Message> q = new ConcurrentLinkedQueue<>();
            final Dispatcher d = nc.createDispatcher((msg) -> {
                if (msg.getSubject().equals("done")) {
                    done.complete(Boolean.TRUE);
                } else {
                    q.add(msg);
                    dispatcher.get().unsubscribe("subject", 2); // get 1 more, for a total of 2
                }
            });

            dispatcher.set(d);

            d.subscribe("subject");
            d.subscribe("done");

            nc.publish("subject", new byte[16]);
            nc.publish("subject", new byte[16]);
            nc.publish("subject", new byte[16]);
            nc.publish("done", new byte[16]); // when we get this we know the others are dispatched
            nc.flush(Duration.ofMillis(1000)); // Wait for the publish

            done.get(200, TimeUnit.MILLISECONDS); // make sure we got them

            assertEquals(2, q.size());

            nc.close();
            assertTrue("Closed Status", Connection.Status.CLOSED == nc.getStatus());
        }
    }
}