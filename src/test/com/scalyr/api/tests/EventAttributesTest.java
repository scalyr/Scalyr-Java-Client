package com.scalyr.api.tests;

import com.scalyr.api.logs.EventAttributes;
import org.junit.Test;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for EventAttributes
 * @author oliver@scalyr.com
 */
public class EventAttributesTest {

    private final int THREAD_START_WAIT_MS =500;

    /**
     * Test concurrent modification support of EventAttributes by adding attributes to EventAttributes
     * during iteration
     */
    @Test
    public void eventAttrConcurrentModifyDuringIteration() {
        EventAttributes eventAttributes = new EventAttributes("key1", "val1");

        Iterator<Map.Entry<String, Object>> eventAttrIterator = eventAttributes.getEntries().iterator();
        eventAttributes.put("key2", "val2");
        eventAttrIterator.next();

    }

    /**
     * Test concurrent modification support of EventAttributes by adding attributes to EventAttributes
     * during event attributes names iteration
     */
    @Test
    public void eventAttrNameConcurrentModifyDuringIteration() {
        EventAttributes eventAttributes = new EventAttributes("key1", "val1");

        Iterator<String> attrName = eventAttributes.getNames().iterator();
        eventAttributes.put("key2", "val2");
        attrName.next();

    }

    /**
     * Test create new EventAttributes object while source EventAttributes object is modified by another thread
     */
    @Test
    public void concurrentConstructorTest() {
        EventAttributes sourceEventAttributes = new EventAttributes();
        for (int i = 1; i <= 50; i++) {
            sourceEventAttributes.put("key"+i, "val"+i);
        }

        AtomicBoolean isDone = new AtomicBoolean(false);
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            int i = 51;
            while (!isDone.get()) {
                sourceEventAttributes.put("key"+i, "val"+i);
                i++;
            }
        });

        // sleep to allow thread to start
        try{
            Thread.sleep(THREAD_START_WAIT_MS);
        } catch (InterruptedException ie ){

        }

        // Create new EventAttributes while source EventAttributes is being modified by another thread
        EventAttributes newEventAttrs = new EventAttributes(sourceEventAttributes);
        isDone.set(true);

        executorService.shutdown();
    }


    /**
     * Test underwriteFrom source EventAttributes object while source EventAttributes object is modified by another thread
     */
    @Test
    public void concurrentUnderwriteFromTest() {
        EventAttributes sourceEventAttributes = new EventAttributes();
        for (int i = 1; i <= 50; i++) {
            sourceEventAttributes.put("key"+i, "val"+i);
        }

        AtomicBoolean isDone = new AtomicBoolean(false);
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            int i = 51;
            while (!isDone.get()) {
                sourceEventAttributes.put("key"+i, "val"+i);
                i++;
            }
        });

        // sleep to allow thread to start
        try{
            Thread.sleep(THREAD_START_WAIT_MS);
        } catch (InterruptedException ie ){

        }

        // Create new EventAttributes while source EventAttributes is being modified by another thread
        EventAttributes newEventAttrs = new EventAttributes();
        newEventAttrs.underwriteFrom(sourceEventAttributes);
        isDone.set(true);

        executorService.shutdown();

    }

}
