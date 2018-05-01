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

/**
 * Provides support for Guice-based Web applications.
 * <p>
 * Guice support is enabled by referencing the Guice filter
 * {@link com.google.inject.servlet.GuiceFilter} and an application
 * specific {@link javax.servlet.ServletContextListener} that extends from
 * {@link com.google.inject.servlet.GuiceServletContextListener} in the web.xml.
 * For example, the web.xml may be as follows:
 * <blockquote><pre>
 *   &lt;web-app&gt;
 *     &lt;listener&gt;
 *       &lt;listener-class&gt;foo.MyGuiceConfig&lt;/listener-class&gt;
 *     &lt;/listener&gt;
 *     &lt;filter&gt;
 *       &lt;filter-name&gt;Guice Filter&lt;/filter-name&gt;
 *       &lt;filter-class&gt;com.google.inject.servlet.GuiceFilter&lt;/filter-class&gt;
 *     &lt;/filter&gt;
 *     &lt;filter-mapping&gt;
 *       &lt;filter-name>Guice Filter&lt;/filter-name&gt;
 *       &lt;url-pattern>/*&lt;/url-pattern&gt;
 *     &lt;/filter-mapping&gt;
 *   &lt;/web-app&gt;
 * </blockquote></pre>
 * and the application specific servlet context listener may be as follows:
 * <blockquote><pre>
 *     package foo;
 *
 *     import com.google.inject.Guice;
 *     import com.google.inject.Injector;
 *     import com.google.inject.servlet.GuiceServletContextListener;
 *     import com.google.inject.servlet.ServletModule;
 *     import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
 *     import com.sun.jersey.guice.JerseyServletModule;
 *     import foo.GuiceResource;
 *
 *     public class MyGuiceConfig extends GuiceServletContextListener {
 *
 *         &#64;Override
 *         protected Injector getInjector() {
 *             return Guice.createInjector(new JerseyServletModule() {
 *
 *                 &#64;Override
 *                 protected void configureServlets() {
 *                     bind(GuiceResource.class);
 *
 *                     serve("/*").with(GuiceContainer.class);
 *                 }
 *         });
 *     }
 * }
 * </blockquote></pre>
 * Notice that one class <code>GuiceResource</code> is bound and the
 * {@link com.sun.jersey.guice.spi.container.servlet.GuiceContainer} is
 * declared in the <code>serve</code> method. A instance of
 * module {@link com.sun.jersey.guice.JerseyServletModule} is created. This
 * module extends from {@link com.google.inject.servlet.ServletModule} and
 * provides JAX-RS and Jersey bindings.
 * <p>
 * Instances of
 * <code>GuiceResource</code> will be managed according to the scope declared
 * using Guice defined scopes. For example the <code>GuiceResource</code>
 * could be as follows:
 * <blockquote><pre>
 *    package foo;
 *
 *    import javax.ws.rs.GET;
 *    import javax.ws.rs.Produces;
 *    import javax.ws.rs.Path;
 *    import javax.ws.rs.QueryParam;
 *    import javax.enterprise.context.RequestScoped;
 *
 *    &#64;Path("bound/perrequest")
 *    &#64;RequestScoped
 *    public class GuiceResource {
 *
 *        &#64;QueryParam("x") String x;
 *
 *        &#64;GET
 *        &#64;Produces("text/plain")
 *        public String getIt() {
 *            return "Hello From Guice: " + x;
 *        }
 *    }
 * </blockquote></pre>
 * <p>
 * Any root resource or provider classes bound by Guice
 * will be automatically registered. It is possible to intermix Guice and
 * non-Guice registration of classes by additionally using the normal
 * Jersey-based registration mechanisms in the servlet context listener
 * implementation. For example:
 * <blockquote><pre>
 *     package foo;
 *
 *     import com.google.inject.Guice;
 *     import com.google.inject.Injector;
 *     import com.google.inject.servlet.GuiceServletContextListener;
 *     import com.google.inject.servlet.ServletModule;
 *     import com.sun.jersey.api.core.PackagesResourceConfig;
 *     import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
 *     import com.sun.jersey.guice.JerseyServletModule;
 *     import foo.GuiceResource;
 *     import java.util.HashMap;
 *     import java.util.Map;
 *
 *     public class GuiceServletConfig extends GuiceServletContextListener {
 *
 *         &#64;Override
 *         protected Injector getInjector() {
 *             return Guice.createInjector(new JerseyServletModule() {
 *
 *                 &#64;Override
 *                 protected void configureServlets() {
 *                     bind(GuiceResource.class);
 *
 *                     Map&lt;String, String&gt; params = new HashMap&lt;String, String&gt;();
 *                     params.put(PackagesResourceConfig.PROPERTY_PACKAGES, "unbound");
 *                     serve("/*").with(GuiceContainer.class, params);
 *                 }
 *             });
 *         }
 *     }
 * </blockquote></pre>
 * <p>
 * Any root resource or provider classes found in the package <code>unbound</code>
 * or sub-packages of will be registered whether they be Guice-bound nor not.
 * <p>
 * Sometimes it is convenient for developers not to explicitly bind a
 * resource or provider, let Guice instantiate, and let Jersey manage
 * the life-cycle. This behavior can be enabled for a resource or
 * provider class as follows:
 * <ol>
 * <li>a class constructor is annotated with {@link com.google.inject.Inject};
 * <li>the class is not explicitly bound in Guice; and
 * <li>the class is registered using a Jersey based registration mechanism,
 *     for example using package scanning registration.
 * </ol>
 * <p>
 * In other cases it is convenient to let Jersey instantiate and manage
 * the life-cycle and let Guice perform injection. This behavior can be
 * enabled for a resource or provider class as follows:
 * <ol>
 * <li>a field or method is annotated with {@link com.google.inject.Inject};
 * <li>the class is not explicitly bound in Guice; and
 * <li>the class is registered using a Jersey based registration mechanism,
 *     for example using package scanning registration.
 * </ol>
 */
package com.sun.jersey.guice.spi.container.servlet;
