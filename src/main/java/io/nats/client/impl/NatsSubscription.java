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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.nats.client.Message;
import io.nats.client.Subscription;

class NatsSubscription implements Subscription {

    private String subject;
    private String queueName;
    private String sid;

    private NatsConnection connection;
    private NatsDispatcher dispatcher;
    private MessageQueue incoming;

    private AtomicLong maxMessages;
    private AtomicLong messagesReceived;

    NatsSubscription(String sid, String subject, String queueName, NatsConnection connection,
            NatsDispatcher dispatcher) {
        this.subject = subject;
        this.queueName = queueName;
        this.sid = sid;
        this.dispatcher = dispatcher;
        this.connection = connection;
        this.maxMessages = new AtomicLong(-1);
        this.messagesReceived = new AtomicLong(0);

        if (this.dispatcher == null) {
            this.incoming = new MessageQueue();
        }
    }

    public boolean isActive() {
        return (this.dispatcher != null || this.incoming != null);
    }

    void invalidate() {
        if (this.incoming != null) {
            this.incoming.interrupt();
        }
        this.dispatcher = null;
        this.incoming = null;
    }

    void setMax(long cd) {
        this.maxMessages.set(cd);
    }

    long getMax() {
        return this.maxMessages.get();
    }

    void incrementMessageCount() {
        this.messagesReceived.incrementAndGet();
    }

    long getMessageCount() {
        return this.messagesReceived.get();
    }

    boolean reachedMax() {
        long max = this.maxMessages.get();
        long recv = this.messagesReceived.get();
        return (max > 0) && (max <= recv);
    }

    String getSID() {
        return this.sid;
    }

    NatsDispatcher getDispatcher() {
        return this.dispatcher;
    }

    MessageQueue getMessageQueue() {
        return this.incoming;
    }

    public String getSubject() {
        return this.subject;
    }

    public String getQueueName() {
        return this.queueName;
    }

    public Message nextMessage(Duration timeout) throws InterruptedException {
        if (this.dispatcher != null) {
            throw new IllegalStateException(
                    "Subscriptions that belong to a dispatcher cannot respond to nextMessage directly.");
        } else if (this.incoming == null) {
            throw new IllegalStateException("This subscription is inactive.");
        }

        Message msg = incoming.pop(timeout);

        if (this.incoming == null) { // We were unsubscribed while waiting TODO(sasbury): Make a test for this
            throw new IllegalStateException("This subscription is inactive.");
        }

        this.incrementMessageCount();

        if (this.reachedMax()) {
            this.connection.invalidate(this);
        }

        return msg;
    }

    /**
     * Unsubscribe this subscription and stop listening for messages.
     * 
     * <p>
     * <strong>TODO(sasbury)</strong> Timing on messages in the queue ...
     * </p>
     */
    public void unsubscribe() {
        if (this.dispatcher != null) {
            throw new IllegalStateException(
                    "Subscriptions that belong to a dispatcher cannot respond to unsubscribe directly.");
        } else if (this.incoming == null) {
            throw new IllegalStateException("This subscription is inactive.");
        }

        this.connection.unsubscribe(this, -1);
    }

    /**
     * Unsubscribe this subscription and stop listening for messages, after the
     * specified number of messages.
     * 
     * <p>
     * <strong>TODO(sasbury)</strong> Timing on messages in the queue ...
     * </p>
     * 
     * <p>
     * Supports chaining so that you can do things like:
     * </p>
     * <p>
     * <blockquote>
     * 
     * <pre>
     * nc = Nats.connect()
     * m = nc.subscribe("hello").unsubscribe(1).nextMessage(Duration.ZERO);
     * </pre>
     * 
     * </blockquote>
     * </p>
     * 
     * @param after
     *                  The number of messages to accept before unsubscribing
     * @return The subscription so that calls can be chained
     */
    public Subscription unsubscribe(int after) {
        if (this.dispatcher != null) {
            throw new IllegalStateException(
                    "Subscriptions that belong to a dispatcher cannot respond to unsubscribe directly.");
        } else if (this.incoming == null) {
            throw new IllegalStateException("This subscription is inactive.");
        }

        this.connection.unsubscribe(this, after);
        return this;
    }
}