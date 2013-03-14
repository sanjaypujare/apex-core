/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.bufferserver.internal;

import com.malhartech.bufferserver.client.BufferServerSubscriber;
import com.malhartech.bufferserver.client.BufferServerPublisher;
import com.malhartech.bufferserver.server.Server;
import com.malhartech.bufferserver.Buffer.Message;
import com.malhartech.bufferserver.Buffer.Message.MessageType;
import com.malhartech.bufferserver.util.Codec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import malhar.netlet.DefaultEventLoop;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 *
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public class NewSubscriberTest
{
  private static final Logger logger = LoggerFactory.getLogger(NewSubscriberTest.class);
  static Server instance;
  static String host;
  static int port;
  static int spinCount = 500;
  static DefaultEventLoop eventloop;

  @BeforeClass
  public static void setupServerAndClients() throws Exception
  {
    try {
      eventloop = new DefaultEventLoop("server");
    }
    catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
    eventloop.start();

    instance = new Server(0);
    SocketAddress result = instance.run(eventloop);
    assert (result instanceof InetSocketAddress);
    host = ((InetSocketAddress)result).getHostName();
    port = ((InetSocketAddress)result).getPort();
  }

  @AfterClass
  public static void teardownServerAndClients()
  {
    eventloop.stop(instance);
    eventloop.stop();
  }

  @Test
  @SuppressWarnings("SleepWhileInLoop")
  public void test() throws InterruptedException
  {
    logger.debug("test");

    final BufferServerPublisher bsp1 = new BufferServerPublisher("MyPublisher");
    bsp1.eventloop = eventloop;
    bsp1.setup(host, port);

    final BufferServerSubscriber bss1 = new BufferServerSubscriber("MyPublisher", 0, null);
    bss1.eventloop = eventloop;
    bss1.setup(host, port);

    bsp1.baseWindow = 0x7afebabe;
    bsp1.windowId = 00000000;
    bsp1.activate();
    bss1.activate();

    Thread.sleep(500);

    final AtomicBoolean publisherRun = new AtomicBoolean(true);
    new Thread("publisher")
    {
      @Override
      @SuppressWarnings("SleepWhileInLoop")
      public void run()
      {
        ResetTuple rt = new ResetTuple();
        rt.id = 0x7afebabe000000faL;
        bsp1.publishMessage(rt);

        long windowId = 0x7afebabe00000000L;
        try {
          while (publisherRun.get()) {

            BeginTuple bt = new BeginTuple();
            bt.id = windowId;
            bsp1.publishMessage(bt);

            Thread.sleep(5);
            bsp1.publishMessage(new byte[0]);
            Thread.sleep(5);

            EndTuple et = new EndTuple();
            et.id = windowId;
            bsp1.publishMessage(et);

            windowId++;
          }
        }
        catch (InterruptedException ex) {
        }
        finally {
          logger.debug("publisher the middle of window = {}", Codec.getStringWindowId(windowId));
        }
      }

    }.start();


    final AtomicBoolean subscriberRun = new AtomicBoolean(true);
    new Thread("subscriber")
    {
      @Override
      @SuppressWarnings("SleepWhileInLoop")
      public void run()
      {
        try {
          while (subscriberRun.get()) {
            Thread.sleep(10);
//            logger.debug("subscriber received first = {} and last = {}", bss.firstPayload, bss.lastPayload);

          }
        }
        catch (InterruptedException ex) {
        }
        finally {
          logger.debug("subscriber received first = {} and last = {}", bss1.firstPayload, bss1.lastPayload);
        }
      }

    }.start();

    do {
      Message message = bss1.lastPayload;
      if (message != null) {
        if (message.getType() == MessageType.BEGIN_WINDOW && message.getBeginWindow().getWindowId() > 9) {
          break;
        }
      }
      Thread.sleep(10);
    }
    while (true);

    publisherRun.set(false);
    subscriberRun.set(false);

    bsp1.deactivate();
    bss1.deactivate();

    bss1.teardown();
    bsp1.teardown();

    /*
     * At this point, we know that both the publishers and the subscribers have gotten at least window Id 10.
     * So we go ahead and make the publisher publish from 5 onwards with different data and have subscriber
     * subscribe from 8 onwards. What we should see is that subscriber gets the new data from 8 onwards.
     */
    final BufferServerPublisher bsp2 = new BufferServerPublisher("MyPublisher");
    bsp2.eventloop = eventloop;
    bsp2.setup(host, port);

    final BufferServerSubscriber bss2 = new BufferServerSubscriber("MyPublisher", 0, null);
    bss2.eventloop = eventloop;
    bss2.setup(host, port);

    bsp2.baseWindow = 0x7afebabe;
    bsp2.windowId = 5;
    bsp2.activate();
    Thread.sleep(500);

    publisherRun.set(true);
    new Thread("publisher")
    {
      @Override
      @SuppressWarnings("SleepWhileInLoop")
      public void run()
      {
        long windowId = 0x7afebabe00000005L;
        try {
          while (publisherRun.get()) {
            BeginTuple bt = new BeginTuple();
            bt.id = windowId;
            bsp2.publishMessage(bt);

            Thread.sleep(5);
            bsp2.publishMessage(new byte[] {'a'});
            Thread.sleep(5);

            EndTuple et = new EndTuple();
            et.id = windowId;
            bsp2.publishMessage(et);

            windowId++;
          }
        }
        catch (InterruptedException ex) {
        }
        finally {
          logger.debug("publisher the middle of window = {}", Codec.getStringWindowId(windowId));
        }
      }

    }.start();

    bss2.windowId = 0x7afebabe00000008L;
    bss2.activate();
    subscriberRun.set(true);

    new Thread("subscriber")
    {
      @Override
      @SuppressWarnings("SleepWhileInLoop")
      public void run()
      {
        try {
          while (subscriberRun.get()) {
            Thread.sleep(10);
          }
        }
        catch (InterruptedException ex) {
        }
        finally {
          logger.debug("subscriber received first = {} and last = {}", bss2.firstPayload, bss2.lastPayload);
        }
      }

    }.start();

    do {
      Message message = bss2.lastPayload;
      if (message != null && message.getBeginWindow().getWindowId() > 14) {
        break;
      }
      Thread.sleep(10);
    }
    while (true);

    publisherRun.set(false);
    subscriberRun.set(false);

    bsp2.deactivate();
    bss2.deactivate();

    bss2.teardown();
    bsp2.teardown();

    Assert.assertTrue((bss2.lastPayload.getBeginWindow().getWindowId() - 8) * 3 < bss2.tupleCount.get());
  }

  class ResetTuple implements Tuple
  {
    long id;

    @Override
    public MessageType getType()
    {
      return MessageType.RESET_WINDOW;
    }

    @Override
    public long getWindowId()
    {
      return id;
    }

    @Override
    public int getIntervalMillis()
    {
      return (int)id;
    }

    @Override
    public int getBaseSeconds()
    {
      return (int)(id >> 32);
    }

  }

  class BeginTuple extends ResetTuple
  {
    @Override
    public MessageType getType()
    {
      return MessageType.BEGIN_WINDOW;
    }

  }

  class EndTuple extends ResetTuple
  {
    @Override
    public MessageType getType()
    {
      return MessageType.END_WINDOW;
    }

  }

}
