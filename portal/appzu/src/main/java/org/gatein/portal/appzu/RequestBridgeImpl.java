/*
 * Copyright (C) 2012 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.gatein.portal.appzu;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import juzu.PropertyMap;
import juzu.PropertyType;
import juzu.Scope;
import juzu.asset.AssetLocation;
import juzu.impl.bridge.spi.DispatchBridge;
import juzu.impl.bridge.spi.RequestBridge;
import juzu.impl.bridge.spi.ScopedContext;
import juzu.impl.bridge.spi.servlet.ServletScopedContext;
import juzu.impl.common.Logger;
import juzu.impl.common.MethodHandle;
import juzu.impl.common.MimeType;
import juzu.impl.plugin.application.Application;
import juzu.impl.plugin.controller.ControllerPlugin;
import juzu.impl.plugin.controller.ControllerResolver;
import juzu.impl.request.ContextLifeCycle;
import juzu.impl.request.ControlParameter;
import juzu.impl.request.Method;
import juzu.impl.request.Request;
import juzu.request.ApplicationContext;
import juzu.request.ClientContext;
import juzu.request.HttpContext;
import juzu.request.Phase;
import juzu.request.RequestParameter;
import juzu.request.ResponseParameter;
import juzu.request.Result;
import juzu.request.SecurityContext;
import juzu.request.UserContext;
import juzu.request.WindowContext;
import org.gatein.portal.content.WindowContentContext;
import org.gatein.portal.web.page.Encoder;

/**
 * @author Julien Viet
 */
public class RequestBridgeImpl implements RequestBridge {

    /** . */
    final AppContent content;

    /** . */
    final WindowContentContext<AppState> contextContext;

    /** . */
    final Phase phase;

    /** . */
    final Logger logger;

    /** . */
    final Method target;

    /** . */
    final HashMap<ControlParameter, Object> arguments;

    /** . */
    final Map<String ,RequestParameter> requestParameters;

    /** . */
    final ContextLifeCycle contextLifeCycle;

    /** . */
    private Result result;

    /** . */
    private Request request;

    /** The request context. */
    private ScopedContext context;

    public RequestBridgeImpl(
            AppContent content,
            WindowContentContext<AppState> contextContext,
            ContextLifeCycle contextLifeCycle,
            Phase phase,
            Logger logger,
            Map<String, String[]> parameters) {

        // Determine method id and request parameters
        String methodId = null;
        Map<String ,RequestParameter> requestParameters = Collections.emptyMap();
        for (Iterator<Map.Entry<String, String[]>> i = parameters.entrySet().iterator();i.hasNext();) {
            Map.Entry<String, String[]> parameter = i.next();
            String name = parameter.getKey();
            if (name.startsWith("juzu.")) {
                if (name.equals("juzu.op")) {
                    methodId = parameter.getValue()[0];
                }
                i.remove();
            } else {
                if (requestParameters.isEmpty()) {
                    requestParameters = new HashMap<String, RequestParameter>();
                }
                requestParameters.put(name, RequestParameter.create(parameter));
            }
        }

        // Get target method
        Application application = content.app.bridge.getApplication();
        ControllerPlugin controller = application.resolveBean(ControllerPlugin.class);
        ControllerResolver<Method> resolver = controller.getResolver();
        Method<?> target;
        if (methodId != null) {
            target = resolver.resolveMethod(phase, methodId, parameters.keySet());
        } else {
            target = resolver.resolve(phase, parameters.keySet());
        }

        // Get argument map
        HashMap<ControlParameter, Object> arguments = new HashMap<ControlParameter, Object>(target.getArguments(requestParameters));

        //
        this.phase = phase;
        this.logger = logger;
        this.result = null;
        this.request = null;
        this.requestParameters = requestParameters;
        this.target = target;
        this.arguments = arguments;
        this.contextLifeCycle = contextLifeCycle;
        this.content = content;
        this.contextContext = contextContext;
    }

    @Override
    public Phase getPhase() {
        return phase;
    }

    @Override
    public Logger getLogger(String name) {
        return logger;
    }

    @Override
    public MethodHandle getTarget() {
        return target.getHandle();
    }

    @Override
    public Map<ControlParameter, Object> getArguments() {
        return arguments;
    }

    @Override
    public Map<String, RequestParameter> getRequestParameters() {
        return requestParameters;
    }

    @Override
    public <T> T getProperty(PropertyType<T> propertyType) {
        return null;
    }

    @Override
    public ScopedContext getScopedContext(Scope scope, boolean create) {
        if (scope == Scope.REQUEST) {
            if (context == null) {
                context = new ServletScopedContext(logger);
            }
            return context;
        }
        return null;
    }

    @Override
    public ClientContext getClientContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpContext getHttpContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SecurityContext getSecurityContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WindowContext getWindowContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public UserContext getUserContext() {
        return new UserContext() {
            @Override
            public Locale getLocale() {
                return Locale.ENGLISH;
            }
            @Override
            public Iterable<Locale> getLocales() {
                return Collections.singleton(Locale.ENGLISH);
            }
        };
    }

    @Override
    public ApplicationContext getApplicationContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setResult(Result result) throws IllegalArgumentException, IOException {
        this.result = result;
    }

    @Override
    public void begin(Request request) {
        this.request = request;
    }

    @Override
    public void end() {
    }

    @Override
    public void close() {
        this.request = null;
        this.result = null;
    }

    @Override
    public void execute(Runnable runnable) throws RejectedExecutionException {
        throw new UnsupportedOperationException();
    }

    @Override
    public DispatchBridge createDispatch(
            final Phase phase,
            final MethodHandle target,
            final Map<String, ResponseParameter> parameters) throws NullPointerException, IllegalArgumentException {
        final Method method = content.app.bridge.getApplication().resolveBean(ControllerPlugin.class).getDescriptor().getMethodByHandle(target);
        return new DispatchBridge() {
            @Override
            public MethodHandle getTarget() {
                return target;
            }
            @Override
            public Map<String, ResponseParameter> getParameters() {
                return parameters;
            }
            @Override
            public <T> String checkPropertyValidity(PropertyType<T> propertyType, T propertyValue) {
                return null;
            }
            @Override
            public void renderURL(PropertyMap properties, MimeType mimeType, Appendable appendable) throws IOException {
                ContextLifeCycle currentLifeCycle = Request.getCurrent().suspend();
                try {
                    contextLifeCycle.resume();
                    try {
                        HashMap<String, String[]> bridgeParameters = new HashMap<String, String[]>();
                        bridgeParameters.put("juzu.op", new String[]{method.getId()});
                        for (ResponseParameter parameter : parameters.values()) {
                            bridgeParameters.put(parameter.getName(), parameter.toArray());
                        }
                        String s;
                        if (phase == Phase.VIEW) {
                            AppContent content = new AppContent(RequestBridgeImpl.this.content);
                            Encoder encoder = new Encoder(bridgeParameters);
                            content.setParameters(encoder.encode());
                            s = contextContext.createRenderURL(content, Collections.<String, String[]>emptyMap());
                        } else if (phase == Phase.ACTION) {
                            s = contextContext.createActionURL(bridgeParameters, null, null);
                        } else {
                            s = "not yet implemented";
                        }
                        appendable.append(s);
                    } finally {
                        Request.getCurrent().suspend();
                    }

                } finally {
                    currentLifeCycle.resume();
                }
            }
        };
    }

    @Override
    public void renderAssetURL(AssetLocation location, String uri, Appendable appendable) throws NullPointerException, UnsupportedOperationException, IOException {
        throw new UnsupportedOperationException();
    }

    public Result getResult() {
        return result;
    }
}
