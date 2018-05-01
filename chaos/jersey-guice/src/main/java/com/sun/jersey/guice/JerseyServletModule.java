/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2013 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.jersey.guice;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Providers;

import com.google.inject.Provides;
import com.google.inject.servlet.RequestScoped;
import com.google.inject.servlet.ServletModule;

import com.sun.jersey.api.core.ExtendedUriInfo;
import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.api.core.HttpRequestContext;
import com.sun.jersey.api.core.HttpResponseContext;
import com.sun.jersey.api.core.ResourceContext;
import com.sun.jersey.core.util.FeaturesAndProperties;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import com.sun.jersey.spi.MessageBodyWorkers;
import com.sun.jersey.spi.container.ExceptionMapperContext;
import com.sun.jersey.spi.container.WebApplication;

/**
 * A {@link ServletModule} module that provides JAX-RS and Jersey related
 * bindings.
 * <p>
 * The module has a dependency on {@link GuiceContainer}, which is required
 * to be served in the {@link ServletModule#configure() } method.
 * <p>
 * The following bindings are defined:
 * <ul>
 * <li>{@link WebApplication}</li>
 * <li>{@link Providers}</li>
 * <li>{@link FeaturesAndProperties}</li>
 * <li>{@link MessageBodyWorkers}</li>
 * <li>{@link ExceptionMapperContext}</li>
 * <li>{@link HttpContext}</li>
 * <li>{@link UriInfo}</li>
 * <li>{@link ExtendedUriInfo}</li>
 * <li>{@link HttpRequestContext}</li>
 * <li>{@link HttpHeaders}</li>
 * <li>{@link Request}</li>
 * <li>{@link SecurityContext}</li>
 * <li>{@link HttpResponseContext}</li>
 * <li>{@link ResourceContext}</li>
 * </ul>
 * @author Paul.Sandoz@Sun.Com
 */
public class JerseyServletModule extends ServletModule {

    @Provides
    public WebApplication webApp(GuiceContainer guiceContainer) {
        return guiceContainer.getWebApplication();
    }

    @Provides
    public Providers providers(WebApplication webApplication) {
        return webApplication.getProviders();
    }

    @Provides
    public FeaturesAndProperties featuresAndProperties(WebApplication webApplication) {
        return webApplication.getFeaturesAndProperties();
    }

    @Provides
    public MessageBodyWorkers messageBodyWorkers(WebApplication webApplication) {
        return webApplication.getMessageBodyWorkers();
    }

    @Provides
    public ExceptionMapperContext exceptionMapperContext(WebApplication webApplication) {
        return webApplication.getExceptionMapperContext();
    }

    @Provides
    public ResourceContext resourceContext(WebApplication webApplication) {
        return webApplication.getResourceContext();
    }

    @RequestScoped
    @Provides
    public HttpContext httpContext(WebApplication webApplication) {
        return webApplication.getThreadLocalHttpContext();
    }

    @Provides
    @RequestScoped
    public UriInfo uriInfo(WebApplication wa) {
        return wa.getThreadLocalHttpContext().getUriInfo();
    }

    @Provides
    @RequestScoped
    public ExtendedUriInfo extendedUriInfo(WebApplication wa) {
        return wa.getThreadLocalHttpContext().getUriInfo();
    }

    @RequestScoped
    @Provides
    public HttpRequestContext requestContext(WebApplication wa) {
        return wa.getThreadLocalHttpContext().getRequest();
    }

    @RequestScoped
    @Provides
    public HttpHeaders httpHeaders(WebApplication wa) {
        return wa.getThreadLocalHttpContext().getRequest();
    }

    @RequestScoped
    @Provides
    public Request request(WebApplication wa) {
        return wa.getThreadLocalHttpContext().getRequest();
    }

    @RequestScoped
    @Provides
    public SecurityContext securityContext(WebApplication wa) {
        return wa.getThreadLocalHttpContext().getRequest();
    }

    @RequestScoped
    @Provides
    public HttpResponseContext responseContext(WebApplication wa) {
        return wa.getThreadLocalHttpContext().getResponse();
    }
}
