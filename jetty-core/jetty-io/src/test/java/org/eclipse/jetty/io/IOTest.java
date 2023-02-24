//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.io.CleanupMode.ALWAYS;
import static org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS;

public class IOTest
{

    @Test
    public void testIO() throws Exception
    {
        // Only a little test
        ByteArrayInputStream in = new ByteArrayInputStream("The quick brown fox jumped over the lazy dog".getBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        IO.copy(in, out);

        assertEquals(out.toString(), "The quick brown fox jumped over the lazy dog", "copyThread");
    }

    @Test
    public void testHalfClose() throws Exception
    {
        try (ServerSocket connector = new ServerSocket(0);
             Socket client = new Socket("localhost", connector.getLocalPort());
             Socket server = connector.accept())
        {
            // we can write both ways
            client.getOutputStream().write(1);
            assertEquals(1, server.getInputStream().read());
            server.getOutputStream().write(1);
            assertEquals(1, client.getInputStream().read());

            // shutdown output results in read -1
            client.shutdownOutput();
            assertEquals(-1, server.getInputStream().read());

            // Even though EOF has been read, the server input is not seen as shutdown
            assertFalse(server.isInputShutdown());

            // and we can read -1 again
            assertEquals(-1, server.getInputStream().read());

            // but cannot write
            Assertions.assertThrows(SocketException.class, () -> client.getOutputStream().write(1));

            // but can still write in opposite direction.
            server.getOutputStream().write(1);
            assertEquals(1, client.getInputStream().read());

            // server can shutdown input to match the shutdown out of client
            server.shutdownInput();

            // now we EOF instead of reading -1
            Assertions.assertThrows(SocketException.class, () -> server.getInputStream().read());

            // but can still write in opposite direction.
            server.getOutputStream().write(1);
            assertEquals(1, client.getInputStream().read());

            // client can shutdown input
            client.shutdownInput();

            // now we EOF instead of reading -1
            Assertions.assertThrows(SocketException.class, () -> client.getInputStream().read());

            // But we can still write at the server (data which will never be read)
            server.getOutputStream().write(1);

            // and the server output is not shutdown
            assertFalse(server.isOutputShutdown());

            // until we explicitly shut it down
            server.shutdownOutput();

            // and now we can't write
            Assertions.assertThrows(SocketException.class, () -> server.getOutputStream().write(1));

            // but the sockets are still open
            assertFalse(client.isClosed());
            assertFalse(server.isClosed());

            // but if we close one end
            client.close();

            // it is seen as closed.
            assertTrue(client.isClosed());

            // but not the other end
            assertFalse(server.isClosed());

            // which has to be closed explicitly
            server.close();
            assertTrue(server.isClosed());
        }
    }

    @Test
    public void testHalfCloseClientServer() throws Exception
    {
        try (ServerSocketChannel connector = ServerSocketChannel.open())
        {
            connector.socket().bind(null);
            try (ServerSocket serverSocket = connector.socket();
                 SocketChannel socketChannel = SocketChannel.open(serverSocket.getLocalSocketAddress());
                 Socket client = socketChannel.socket())
            {
                client.setSoTimeout(1000);
                try (Socket server = connector.accept().socket())
                {
                    server.setSoTimeout(1000);

                    // Write from client to server
                    client.getOutputStream().write(1);

                    // Server reads
                    assertEquals(1, server.getInputStream().read());

                    // Write from server to client with oshut
                    server.getOutputStream().write(1);
                    // System.err.println("OSHUT "+server);
                    server.shutdownOutput();

                    // Client reads response
                    assertEquals(1, client.getInputStream().read());

                    try
                    {
                        // Client reads -1 and does ishut
                        assertEquals(-1, client.getInputStream().read());
                        assertFalse(client.isInputShutdown());
                        //System.err.println("ISHUT "+client);
                        client.shutdownInput();

                        // Client ???
                        //System.err.println("OSHUT "+client);
                        client.shutdownOutput();
                        //System.err.println("CLOSE "+client);
                        client.close();

                        // Server reads -1, does ishut and then close
                        assertEquals(-1, server.getInputStream().read());
                        assertFalse(server.isInputShutdown());
                        //System.err.println("ISHUT "+server);

                        server.shutdownInput();
                        server.close();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        assertTrue(OS.MAC.isCurrentOs());
                    }
                }
            }
        }
    }

    @Test
    public void testHalfCloseBadClient() throws Exception
    {
        try (ServerSocketChannel connector = ServerSocketChannel.open())
        {
            connector.socket().bind(null);

            try (ServerSocket serverSocket = connector.socket();
                 SocketChannel socketChannel = SocketChannel.open(serverSocket.getLocalSocketAddress());
                 Socket client = socketChannel.socket())
            {
                client.setSoTimeout(1000);
                try (Socket server = connector.accept().socket())
                {
                    server.setSoTimeout(1000);

                    // Write from client to server
                    client.getOutputStream().write(1);

                    // Server reads
                    assertEquals(1, server.getInputStream().read());

                    // Write from server to client with oshut
                    server.getOutputStream().write(1);
                    //System.err.println("OSHUT "+server);
                    server.shutdownOutput();

                    // Client reads response
                    assertEquals(1, client.getInputStream().read());

                    // Client reads -1
                    assertEquals(-1, client.getInputStream().read());
                    assertFalse(client.isInputShutdown());

                    // Client can still write as we are half closed
                    client.getOutputStream().write(1);

                    // Server can still read
                    assertEquals(1, server.getInputStream().read());

                    // Server now closes
                    server.close();

                    // Client still reads -1 (not broken pipe !!)
                    assertEquals(-1, client.getInputStream().read());
                    assertFalse(client.isInputShutdown());

                    Thread.sleep(100);

                    // Client still reads -1 (not broken pipe !!)
                    assertEquals(-1, client.getInputStream().read());
                    assertFalse(client.isInputShutdown());

                    // Client can still write data even though server is closed???
                    client.getOutputStream().write(1);

                    // Client eventually sees Broken Pipe
                    assertThrows(IOException.class, () ->
                    {
                        for (int i = 0; i < 100000; i++)
                        {
                            client.getOutputStream().write(1);
                        }
                    });
                }
            }
        }
    }

    @Test
    public void testServerChannelInterrupt() throws Exception
    {
        try (ServerSocketChannel connector = ServerSocketChannel.open())
        {
            connector.configureBlocking(true);
            connector.socket().bind(null);
            try (ServerSocket serverSocket = connector.socket();
                 SocketChannel socketChannel = SocketChannel.open(serverSocket.getLocalSocketAddress());
                 Socket client = socketChannel.socket())
            {
                client.setSoTimeout(2000);
                try (Socket server = connector.accept().socket())
                {
                    server.setSoTimeout(2000);

                    // Write from client to server
                    client.getOutputStream().write(1);
                    // Server reads
                    assertEquals(1, server.getInputStream().read());

                    // Write from server to client
                    server.getOutputStream().write(1);
                    // Client reads
                    assertEquals(1, client.getInputStream().read());

                    // block a thread in accept
                    final CountDownLatch latch = new CountDownLatch(2);
                    Thread acceptor = new Thread(() ->
                    {
                        try
                        {
                            latch.countDown();
                            connector.accept();
                        }
                        catch (Throwable ignored)
                        {
                        }
                        finally
                        {
                            latch.countDown();
                        }
                    });
                    acceptor.start();
                    while (latch.getCount() == 2)
                    {
                        Thread.sleep(10);
                    }

                    // interrupt the acceptor
                    acceptor.interrupt();

                    // wait for acceptor to exit
                    assertTrue(latch.await(10, TimeUnit.SECONDS));

                    // connector is closed
                    assertFalse(connector.isOpen());

                    // but connection is still open
                    assertFalse(client.isClosed());
                    assertFalse(server.isClosed());

                    // Write from client to server
                    client.getOutputStream().write(42);
                    // Server reads
                    assertEquals(42, server.getInputStream().read());

                    // Write from server to client
                    server.getOutputStream().write(43);
                    // Client reads
                    assertEquals(43, client.getInputStream().read());
                }
            }
        }
    }

    @Test
    public void testReset() throws Exception
    {
        try (ServerSocket connector = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", connector.getLocalPort());
             Socket server = connector.accept())
        {
            client.setTcpNoDelay(true);
            client.setSoLinger(true, 0);
            server.setTcpNoDelay(true);
            server.setSoLinger(true, 0);

            client.getOutputStream().write(1);
            assertEquals(1, server.getInputStream().read());
            server.getOutputStream().write(1);
            assertEquals(1, client.getInputStream().read());

            // Server generator shutdowns output after non persistent sending response.
            server.shutdownOutput();

            // client endpoint reads EOF and shutdown input as result
            assertEquals(-1, client.getInputStream().read());
            client.shutdownInput();

            // client connection see's EOF and shutsdown output as no more requests to be sent.
            client.shutdownOutput();

            // Since input already shutdown, client also closes socket.
            client.close();

            // Server reads the EOF from client oshut and shut's down it's input
            assertEquals(-1, server.getInputStream().read());
            server.shutdownInput();

            // Since output was already shutdown, server
            // closes in the try-with-resources block end.
        }
    }

    @Test
    public void testAsyncSocketChannel() throws Exception
    {
        try (AsynchronousServerSocketChannel connector = AsynchronousServerSocketChannel.open())
        {
            connector.bind(null);
            InetSocketAddress addr = (InetSocketAddress)connector.getLocalAddress();
            Future<AsynchronousSocketChannel> acceptor = connector.accept();

            try (AsynchronousSocketChannel client = AsynchronousSocketChannel.open())
            {
                client.connect(new InetSocketAddress("127.0.0.1", addr.getPort())).get(5, TimeUnit.SECONDS);

                AsynchronousSocketChannel server = acceptor.get(5, TimeUnit.SECONDS);

                ByteBuffer read = ByteBuffer.allocate(1024);
                Future<Integer> reading = server.read(read);

                byte[] data = "Testing 1 2 3".getBytes(StandardCharsets.UTF_8);
                ByteBuffer write = BufferUtil.toBuffer(data);
                Future<Integer> writing = client.write(write);

                writing.get(5, TimeUnit.SECONDS);
                reading.get(5, TimeUnit.SECONDS);
                read.flip();

                assertEquals(ByteBuffer.wrap(data), read);
            }
        }
    }

    @Test
    public void testGatherWrite(@TempDir(cleanup = ON_SUCCESS) Path tmpPath) throws Exception
    {
        Path file = Files.createTempFile(tmpPath, "test", ".txt");
        FileChannel out = FileChannel.open(file,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.DELETE_ON_CLOSE);

        ByteBuffer[] buffers = new ByteBuffer[4096];
        long expected = 0;
        for (int i = 0; i < buffers.length; i++)
        {
            buffers[i] = BufferUtil.toBuffer(i);
            expected += buffers[i].remaining();
        }

        long wrote = IO.write(out, buffers, 0, buffers.length);

        assertEquals(expected, wrote);

        for (ByteBuffer buffer : buffers)
        {
            assertEquals(0, buffer.remaining());
        }
    }

    @Test
    public void testDeleteNull()
    {
        assertFalse(IO.delete((File)null));
        assertFalse(IO.delete((Path)null));
    }

    @Test
    public void testDeleteNonExistentFile(@TempDir(cleanup = ON_SUCCESS) Path dir)
    {
        FS.ensureEmpty(dir);
        Path noFile = dir.resolve("nada");
        assertFalse(IO.delete(noFile));
        assertFalse(IO.delete(noFile.toFile()));
    }

    @Test
    public void testIsEmptyNull()
    {
        assertTrue(IO.isEmptyDir(null));
    }

    @Test
    public void testIsEmptyDoesNotExist(@TempDir(cleanup = ON_SUCCESS) Path dir)
    {
        FS.ensureEmpty(dir);
        Path noFile = dir.resolve("nada");
        assertTrue(IO.isEmptyDir(noFile.toFile()));
    }

    @Test
    public void testIsEmptyExistButAsFile(@TempDir(cleanup = ON_SUCCESS) Path dir) throws IOException
    {
        FS.ensureEmpty(dir);
        Path file = dir.resolve("nada");
        FS.touch(file);
        assertFalse(IO.isEmptyDir(file.toFile()));
    }

    @Test
    public void testIsEmptyExistAndIsEmpty(@TempDir(cleanup = ON_SUCCESS) Path dir)
    {
        FS.ensureEmpty(dir);
        assertTrue(IO.isEmptyDir(dir.toFile()));
    }

    @Test
    public void testIsEmptyExistAndHasContent(@TempDir(cleanup = ON_SUCCESS) Path dir) throws IOException
    {
        FS.ensureEmpty(dir);
        Path file = dir.resolve("nada");
        FS.touch(file);
        assertFalse(IO.isEmptyDir(dir.toFile()));
    }

    @Test
    public void testSelectorWakeup() throws Exception
    {
        try (ServerSocketChannel connector = ServerSocketChannel.open())
        {
            connector.bind(null);
            InetSocketAddress addr = (InetSocketAddress)connector.getLocalAddress();
            try (SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", addr.getPort()));
                 SocketChannel server = connector.accept())
            {
                server.configureBlocking(false);

                Selector selector = Selector.open();
                SelectionKey key = server.register(selector, SelectionKey.OP_READ);

                assertThat(key, notNullValue());
                assertThat(selector.selectNow(), is(0));

                // Test wakeup before select
                selector.wakeup();
                assertThat(selector.select(), is(0));

                // Test wakeup after select
                new Thread(() ->
                {
                    try
                    {
                        Thread.sleep(100);
                        selector.wakeup();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }).start();
                assertThat(selector.select(), is(0));
            }
        }
    }

    @Test
    public void testSymbolicLink(@TempDir(cleanup = ON_SUCCESS) Path dir) throws Exception
    {
        FS.ensureEmpty(dir);
        Path realPath = dir.resolve("real");
        FS.touch(realPath);

        Path linkPath = dir.resolve("link");
        Files.createSymbolicLink(linkPath, realPath);
        Path targPath = linkPath.toRealPath();

        System.err.printf("realPath = %s%n", realPath);
        System.err.printf("linkPath = %s%n", linkPath);
        System.err.printf("targPath = %s%n", targPath);

        assertFalse(Files.isSymbolicLink(realPath));
        assertTrue(Files.isSymbolicLink(linkPath));

        Resource link = ResourceFactory.root().newResource(dir.resolve("link"));
        assertThat(link.isAlias(), is(true));
    }

    @Test
    public void testSymbolicLinkDir(@TempDir(cleanup = ON_SUCCESS) Path dir) throws Exception
    {
        FS.ensureEmpty(dir);

        Path realDirPath = dir.resolve("real");
        Files.createDirectories(realDirPath);

        Path linkDirPath = dir.resolve("link");
        Files.createSymbolicLink(linkDirPath, realDirPath);

        Path realPath = realDirPath.resolve("file");
        FS.touch(realPath);

        Path linkPath = linkDirPath.resolve("file");
        Path targPath = linkPath.toRealPath();

        System.err.printf("realPath = %s%n", realPath);
        System.err.printf("linkPath = %s%n", linkPath);
        System.err.printf("targPath = %s%n", targPath);

        assertFalse(Files.isSymbolicLink(realPath));
        assertFalse(Files.isSymbolicLink(linkPath));
    }

    @Test
    public void foo(@TempDir(cleanup = ALWAYS) Path path) throws Exception
    {
        System.out.println("foo test");
        System.out.println("java.io.tmpdir:" + System.getProperty("java.io.tmpdir"));
        //System.setProperty("java.io.tmpdir", "target");
        System.out.println("Files.createTempDirectory(\"foo\").toString(): " +
                Files.createTempDirectory("foo").toString());
        System.out.println("Path from @TempDir: " +  path.toString());
        System.out.println("Files.createTempDirectory(Paths.get(System.getProperty(\"java.io.tmpdir\")), \"foo\").toString()" +
                Files.createTempDirectory(Paths.get(System.getProperty("java.io.tmpdir")), "foo").toString());
    }

}
