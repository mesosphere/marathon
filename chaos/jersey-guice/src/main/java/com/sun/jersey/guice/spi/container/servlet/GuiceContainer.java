/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.jersey.guice.spi.container.servlet;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletException;

import com.google.inject.Injector;
import com.google.inject.Scope;
import com.google.inject.servlet.ServletScopes;
import com.sun.jersey.api.core.DefaultResourceConfig;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.core.spi.component.ioc.IoCComponentProviderFactory;
import com.sun.jersey.guice.spi.container.GuiceComponentProviderFactory;
import com.sun.jersey.spi.container.WebApplication;
import com.sun.jersey.spi.container.servlet.ServletContainer;
import com.sun.jersey.spi.container.servlet.WebConfig;

/**
 * A {@link Servlet} or {@link Filter} for deploying root resource classes
 * with Guice integration.
 * <p>
 * This class must be registered using
 * <code>com.google.inject.servlet.ServletModule</code>.
 * <p>
 * This class extends {@link ServletContainer} and initiates the
 * {@link WebApplication} with a Guice-based {@link IoCComponentProviderFactory},
 * {@link GuiceComponentProviderFactory}, such that instances of resource and
 * provider classes declared and managed by Guice can be obtained.
 * <p>
 * Guice-bound classes will be automatically registered if such
 * classes are root resource classes or provider classes. It is not necessary
 * to provide initialization parameters for declaring classes in the web.xml
 * unless a mixture of Guice-bound and Jersey-managed classes is required.
 *
 * @author Gili Tzabari
 * @author Paul Sandoz
 * @see com.google.inject.servlet.ServletModule
 */
@Singleton
public class GuiceContainer extends ServletContainer {

    private static final long serialVersionUID = 1931878850157940335L;

    private final Injector injector;
    private WebApplication webapp;

    public class ServletGuiceComponentProviderFactory extends GuiceComponentProviderFactory {
        public ServletGuiceComponentProviderFactory(ResourceConfig config, Injector injector) {
            super(config, injector);
        }

        @Override
        public Map<Scope, ComponentScope> createScopeMap() {
            Map<Scope, ComponentScope> m = super.createScopeMap();

            m.put(ServletScopes.REQUEST, ComponentScope.PerRequest);
            return m;
        }
    }
    /**
     * Creates a new Injector.
     *
     * @param injector the Guice injector
     */
    @Inject
    public GuiceContainer(Injector injector) {
        this.injector = injector;
    }

    @Override
    protected ResourceConfig getDefaultResourceConfig(Map<String, Object> props,
            WebConfig webConfig) throws ServletException {
        return new DefaultResourceConfig();
    }

    @Override
    protected void initiate(ResourceConfig config, WebApplication webapp) {
        this.webapp = webapp;
        webapp.initiate(config, new ServletGuiceComponentProviderFactory(config, injector));
    }

    public WebApplication getWebApplication() {
        return webapp;
    }
}