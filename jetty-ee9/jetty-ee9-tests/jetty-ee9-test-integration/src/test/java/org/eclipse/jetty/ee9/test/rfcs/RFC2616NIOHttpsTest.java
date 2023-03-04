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

package org.eclipse.jetty.ee9.test.rfcs;

import java.nio.file.Path;

import org.eclipse.jetty.ee9.test.support.XmlBasedJettyServer;
import org.eclipse.jetty.ee9.test.support.rawhttp.HttpSocket;
import org.eclipse.jetty.ee9.test.support.rawhttp.HttpsSocketImpl;
import org.eclipse.jetty.http.HttpScheme;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Perform the RFC2616 tests against a server running with the Jetty NIO Connector and listening on HTTPS (HTTP over SSL).
 */
public class RFC2616NIOHttpsTest extends RFC2616BaseTest
{

    private static XmlBasedJettyServer xmlBasedJettyServer;

    @BeforeAll
    public static void setupServer(@TempDir(cleanup = CleanupMode.ON_SUCCESS)Path tmpPath) throws Exception
    {
        XmlBasedJettyServer server = new XmlBasedJettyServer();
        server.setScheme(HttpScheme.HTTPS.asString());
        server.addXmlConfiguration("RFC2616Base.xml");
        server.addXmlConfiguration("RFC2616_Redirects.xml");
        server.addXmlConfiguration("RFC2616_Filters.xml");
        server.addXmlConfiguration("ssl.xml");
        server.addXmlConfiguration("NIOHttps.xml");
        xmlBasedJettyServer = setUpServer(server, RFC2616NIOHttpsTest.class, tmpPath);
    }

    @Override
    public HttpSocket getHttpClientSocket() throws Exception
    {
        return new HttpsSocketImpl();
    }

    @AfterAll
    public static void tearDownServer() throws Exception
    {
        xmlBasedJettyServer.stop();
    }

    @Override
    public XmlBasedJettyServer getServer()
    {
        return xmlBasedJettyServer;
    }
}
