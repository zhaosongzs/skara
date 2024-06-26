/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bot;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;
import org.openjdk.skara.json.JSON;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestWorkItem implements WorkItem {
    private final ConcurrencyCheck concurrencyCheck;
    private final String description;
    boolean hasRun = false;

    interface ConcurrencyCheck {
        boolean concurrentWith(WorkItem other);
    }

    TestWorkItem(ConcurrencyCheck concurrencyCheck) {
        this.concurrencyCheck = concurrencyCheck;
        this.description = null;
    }

    TestWorkItem(ConcurrencyCheck concurrencyCheck, String description) {
        this.concurrencyCheck = concurrencyCheck;
        this.description = description;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        hasRun = true;
        System.out.println("Item " + this.toString() + " now running");
        return List.of();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        return concurrencyCheck.concurrentWith(other);
    }

    @Override
    public String toString() {
        return description != null ? description : super.toString();
    }

    @Override
    public String botName() {
        return "test-bot";
    }

    @Override
    public String workItemName() {
        return botName();
    }
}

class TestWorkItemChild extends TestWorkItem {
    TestWorkItemChild(ConcurrencyCheck concurrencyCheck, String description) {
        super(concurrencyCheck, description);
    }
}

class TestWorkItemWithFollowup extends TestWorkItem {
    private List<WorkItem> followUpItems;

    TestWorkItemWithFollowup(ConcurrencyCheck concurrencyCheck, String description, List<WorkItem> followUpItems) {
        super(concurrencyCheck, description);

        this.followUpItems = followUpItems;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        hasRun = true;
        System.out.println("Item with followups " + this.toString() + " now running");
        return followUpItems;
    }
}

class TestBlockedWorkItem implements WorkItem {
    private final CountDownLatch countDownLatch;

    TestBlockedWorkItem(CountDownLatch countDownLatch) {
        this.countDownLatch = countDownLatch;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        return false;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        System.out.println("Starting to wait...");;
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Done waiting");
        return List.of();
    }

    @Override
    public String botName() {
        return "test-blocked";
    }

    @Override
    public String workItemName() {
        return botName();
    }
}

class TestBot implements Bot {
    private final List<WorkItem> items;
    private final Supplier<List<WorkItem>> itemSupplier;

    TestBot(WorkItem... items) {
        this.items = Arrays.asList(items);
        itemSupplier = null;
    }

    TestBot(Supplier<List<WorkItem>> itemSupplier) {
        items = null;
        this.itemSupplier = itemSupplier;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        if (items != null) {
            return items;
        } else {
            return itemSupplier.get();
        }
    }

    @Override
    public String name() {
        return "test-bot";
    }
}

class BotRunnerTests {
    @BeforeAll
    static void setUp() {
        Logger log = Logger.getGlobal();
        log.setLevel(Level.FINER);
        log = Logger.getLogger("org.openjdk.bots.cli");
        log.setLevel(Level.FINER);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINER);
        log.addHandler(handler);
    }

    private BotRunnerConfiguration config() {
        var config = JSON.object();
        try {
            return BotRunnerConfiguration.parse(config);
        } catch (ConfigurationError configurationError) {
            throw new RuntimeException(configurationError);
        }
    }

    private BotRunnerConfiguration config(String json) {
        var config = JSON.parse(json).asObject();
        try {
            return BotRunnerConfiguration.parse(config);
        } catch (ConfigurationError configurationError) {
            throw new RuntimeException(configurationError);
        }
    }
    @Test
    void simpleConcurrent() throws TimeoutException {
        var item1 = new TestWorkItem(i -> true, "Item 1");
        var item2 = new TestWorkItem(i -> true, "Item 2");
        var bot = new TestBot(item1, item2);
        var runner = new BotRunner(config(), List.of(bot));

        runner.runOnce(Duration.ofSeconds(10));

        assertTrue(item1.hasRun);
        assertTrue(item2.hasRun);
    }

    @Test
    void simpleSerial() throws TimeoutException {
        var item1 = new TestWorkItem(i -> false, "Item 1");
        var item2 = new TestWorkItem(i -> false, "Item 2");
        var bot = new TestBot(item1, item2);
        var runner = new BotRunner(config(), List.of(bot));

        runner.runOnce(Duration.ofSeconds(10));

        assertTrue(item1.hasRun);
        assertTrue(item2.hasRun);
    }

    @Test
    void moreItemsThanScratchPaths() throws TimeoutException {
        List<TestWorkItem> items = new LinkedList<>();
        for (int i = 0; i < 20; ++i) {
            items.add(new TestWorkItem(x -> true, "Item " + i));
        }
        var bot = new TestBot(items.toArray(new TestWorkItem[0]));
        var runner = new BotRunner(config(), List.of(bot));

        runner.runOnce(Duration.ofSeconds(10));

        for (var item : items) {
            assertTrue(item.hasRun);
        }
    }

    static class ThrowingItemProvider {
        private final List<WorkItem> items;
        private int throwCount;

        ThrowingItemProvider(List<WorkItem> items, int throwCount) {
            this.items = items;
            this.throwCount = throwCount;
        }

        List<WorkItem> get() {
            if (throwCount-- > 0) {
                throw new RuntimeException("Sorry, can't provide items just yet");
            } else {
                return items;
            }
        }
    }

    @Test
    void periodItemsThrow() throws TimeoutException {
        var item1 = new TestWorkItem(i -> false, "Item 1");
        var item2 = new TestWorkItem(i -> false, "Item 2");
        var provider = new ThrowingItemProvider(List.of(item1, item2), 1);

        var bot = new TestBot(provider::get);

        new BotRunner(config(), List.of(bot)).runOnce(Duration.ofSeconds(10));
        Assertions.assertFalse(item1.hasRun);
        Assertions.assertFalse(item2.hasRun);

        new BotRunner(config(), List.of(bot)).runOnce(Duration.ofSeconds(10));
        assertTrue(item1.hasRun);
        assertTrue(item2.hasRun);
    }

    @Test
    void discardAdditionalBlockedItems() throws TimeoutException {
        var item1 = new TestWorkItem(i -> false, "Item 1");
        var item2 = new TestWorkItem(i -> false, "Item 2");
        var item3 = new TestWorkItem(i -> false, "Item 3");
        var item4 = new TestWorkItem(i -> false, "Item 4");
        var bot = new TestBot(item1, item2, item3, item4);

        var config = config("{\"runner\": { \"concurrency\": 1 } }");
        var runner = new BotRunner(config, List.of(bot));

        runner.runOnce(Duration.ofSeconds(10));

        assertTrue(item1.hasRun);
        Assertions.assertFalse(item2.hasRun);
        Assertions.assertFalse(item3.hasRun);
        assertTrue(item4.hasRun);
    }

    @Test
    void dontDiscardDifferentBlockedItems() throws TimeoutException {
        var item1 = new TestWorkItem(i -> false, "Item 1");
        var item2 = new TestWorkItem(i -> false, "Item 2");
        var item3 = new TestWorkItem(i -> false, "Item 3");
        var item4 = new TestWorkItem(i -> false, "Item 4");
        var item5 = new TestWorkItemChild(i -> false, "Item 5");
        var item6 = new TestWorkItemChild(i -> false, "Item 6");
        var item7 = new TestWorkItemChild(i -> false, "Item 7");
        var bot = new TestBot(item1, item2, item3, item4, item5, item6, item7);

        var config = config("{\"runner\": { \"concurrency\": 1 } }");
        var runner = new BotRunner(config, List.of(bot));

        runner.runOnce(Duration.ofSeconds(10));

        assertTrue(item1.hasRun);
        Assertions.assertFalse(item2.hasRun);
        Assertions.assertFalse(item3.hasRun);
        assertTrue(item4.hasRun);
        Assertions.assertFalse(item5.hasRun);
        Assertions.assertFalse(item6.hasRun);
        assertTrue(item7.hasRun);
    }

    @Test
    @EnabledOnOs({LINUX, MAC})
    void watchdogTrigger() throws TimeoutException {
        var countdownLatch = new CountDownLatch(1);
        var item = new TestBlockedWorkItem(countdownLatch);
        var bot = new TestBot(item);
        var runner = new BotRunner(config("{ \"runner\": { \"watchdog_warn\": \"PT0.01S\", \"interval\": \"PT0.001S\" } }"), List.of(bot));

        var errors = new ArrayList<String>();
        var log = Logger.getLogger("org.openjdk.skara.bot");
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().equals(Level.SEVERE)) {
                    errors.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() throws SecurityException {
            }
        });

        runner.run(Duration.ofMillis(100));
        assertTrue(errors.size() > 0);
        assertTrue(errors.size() <= 100);
        countdownLatch.countDown();
    }

    @Test
    void dependentItems() throws TimeoutException {
        var item2 = new TestWorkItem(i -> true, "Item 2");
        var item3 = new TestWorkItem(i -> true, "Item 3");

        var item1 = new TestWorkItemWithFollowup(i -> true, "Item 1", List.of(item2, item3));
        var bot = new TestBot(item1);
        var runner = new BotRunner(config(), List.of(bot));

        runner.runOnce(Duration.ofSeconds(10));

        assertTrue(item1.hasRun);
        assertTrue(item2.hasRun);
        assertTrue(item3.hasRun);
    }
}
