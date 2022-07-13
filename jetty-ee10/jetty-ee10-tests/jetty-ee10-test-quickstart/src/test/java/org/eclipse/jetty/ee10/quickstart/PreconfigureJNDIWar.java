//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.quickstart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PreconfigureJNDIWar
{
    private static final long __start = System.nanoTime();
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) throws Exception
    {
        Path workdir = MavenTestingUtils.getTargetTestingPath(PreconfigureJNDIWar.class.getSimpleName());
        FS.ensureEmpty(workdir);

        Path target = workdir.resolve("test-jndi-preconfigured");
        FS.ensureEmpty(target);

        PreconfigureQuickStartWar.main(
            MavenTestingUtils.getTargetFile("test-jndi.war").toString(),
            target.toString(),
            MavenTestingUtils.getTestResourceFile("test-jndi.xml").toString());

        LOG.info("Preconfigured in {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - __start));

        if (LOG.isDebugEnabled())
        {
            Path quickStartXml = target.resolve("WEB-INF/quickstart-web.xml");
            System.out.println(Files.readString(quickStartXml));
        }
    }
}
