package io.quarkus.undertow.runtime;

import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.SessionTrackingMode;

import org.jboss.logging.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.quarkus.arc.InjectableContext;
import io.quarkus.arc.ManagedContext;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.runtime.BlockingOperationControl;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.vertx.http.runtime.HttpCompressionHandler;
import io.quarkus.vertx.http.runtime.VertxHttpBuildTimeConfig;
import io.quarkus.vertx.http.runtime.VertxHttpConfig;
import io.quarkus.vertx.http.runtime.VertxHttpRecorder;
import io.quarkus.vertx.http.runtime.devmode.ResourceNotFoundData;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.undertow.httpcore.BufferAllocator;
import io.undertow.httpcore.StatusCodes;
import io.undertow.httpcore.UndertowOptionMap;
import io.undertow.httpcore.UndertowOptions;
import io.undertow.security.api.AuthenticationMode;
import io.undertow.security.api.NotificationReceiver;
import io.undertow.security.api.SecurityNotification;
import io.undertow.security.idm.IdentityManager;
import io.undertow.server.DefaultExchangeHandler;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.CanonicalPathHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.resource.CachingResourceManager;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.server.session.SessionIdGenerator;
import io.undertow.servlet.ServletExtension;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.ClassIntrospecter;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ErrorPage;
import io.undertow.servlet.api.ExceptionHandler;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.api.InstanceHandle;
import io.undertow.servlet.api.ListenerInfo;
import io.undertow.servlet.api.LoginConfig;
import io.undertow.servlet.api.MimeMapping;
import io.undertow.servlet.api.SecurityConstraint;
import io.undertow.servlet.api.SecurityInfo;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletContainerInitializerInfo;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.api.ServletSecurityInfo;
import io.undertow.servlet.api.ServletSessionConfig;
import io.undertow.servlet.api.ThreadSetupHandler;
import io.undertow.servlet.api.TransportGuaranteeType;
import io.undertow.servlet.api.WebResourceCollection;
import io.undertow.servlet.handlers.DefaultServlet;
import io.undertow.servlet.handlers.ServletPathMatches;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.servlet.spec.HttpServletRequestImpl;
import io.undertow.servlet.spec.ServletContextImpl;
import io.undertow.util.AttachmentKey;
import io.undertow.util.ImmediateAuthenticationMechanismFactory;
import io.undertow.vertx.VertxHttpExchange;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Provides the runtime methods to bootstrap Undertow. This class is present in the final uber-jar,
 * and is invoked from generated bytecode
 */
@Recorder
public class UndertowDeploymentRecorder {

    private static final Logger log = Logger.getLogger("io.quarkus.undertow");

    public static final HttpHandler ROOT_HANDLER = new HttpHandler() {
        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            currentRoot.handleRequest(exchange);
        }
    };

    private static final List<HandlerWrapper> hotDeploymentWrappers = new CopyOnWriteArrayList<>();
    private static volatile List<Path> hotDeploymentResourcePaths;
    private static volatile HttpHandler currentRoot = ResponseCodeHandler.HANDLE_404;
    private static volatile ServletContextImpl servletContext;

    private static final AttachmentKey<InjectableContext.ContextState> REQUEST_CONTEXT = AttachmentKey
            .create(InjectableContext.ContextState.class);

    protected static final int DEFAULT_BUFFER_SIZE;
    protected static final boolean DEFAULT_DIRECT_BUFFERS;

    static {
        long maxMemory = Runtime.getRuntime().maxMemory();
        //smaller than 64mb of ram we use 512b buffers
        if (maxMemory < 64 * 1024 * 1024) {
            //use 512b buffers
            DEFAULT_DIRECT_BUFFERS = false;
            DEFAULT_BUFFER_SIZE = 512;
        } else if (maxMemory < 128 * 1024 * 1024) {
            //use 1k buffers
            DEFAULT_DIRECT_BUFFERS = true;
            DEFAULT_BUFFER_SIZE = 1024;
        } else {
            //use 16k buffers for best performance
            //as 16k is generally the max amount of data that can be sent in a single write() call
            DEFAULT_DIRECT_BUFFERS = true;
            DEFAULT_BUFFER_SIZE = 1024 * 16 - 20; //the 20 is to allow some space for protocol headers, see UNDERTOW-1209
        }

    }

    private final VertxHttpBuildTimeConfig httpBuildTimeConfig;
    private final RuntimeValue<VertxHttpConfig> httpRuntimeConfig;
    private final RuntimeValue<ServletRuntimeConfig> servletRuntimeConfig;

    public UndertowDeploymentRecorder(
            final VertxHttpBuildTimeConfig httpBuildTimeConfig,
            final RuntimeValue<VertxHttpConfig> httpRuntimeConfig,
            final RuntimeValue<ServletRuntimeConfig> servletRuntimeConfig) {
        this.httpBuildTimeConfig = httpBuildTimeConfig;
        this.httpRuntimeConfig = httpRuntimeConfig;
        this.servletRuntimeConfig = servletRuntimeConfig;
    }

    public static void setHotDeploymentResources(List<Path> resources) {
        hotDeploymentResourcePaths = resources;
    }

    public RuntimeValue<DeploymentInfo> createDeployment(String name, Set<String> knownFiles, Set<String> knownDirectories,
            LaunchMode launchMode, ShutdownContext context, String mountPoint, String defaultCharset,
            String requestCharacterEncoding, String responseCharacterEncoding, boolean proactiveAuth,
            List<String> welcomeFiles, final boolean hasSecurityCapability) {
        DeploymentInfo d = new DeploymentInfo();
        d.setDefaultRequestEncoding(requestCharacterEncoding);
        d.setDefaultResponseEncoding(responseCharacterEncoding);
        d.setDefaultEncoding(defaultCharset);
        d.setSessionIdGenerator(new QuarkusSessionIdGenerator());
        d.setDeploymentName(name);
        d.setContextPath(mountPoint);
        d.setEagerFilterInit(true);
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        d.setClassLoader(cl);
        //TODO: we need better handling of static resources
        ResourceManager resourceManager;
        if (hotDeploymentResourcePaths == null) {
            resourceManager = new KnownPathResourceManager(knownFiles, knownDirectories,
                    new ClassPathResourceManager(d.getClassLoader(), "META-INF/resources"));
        } else {
            List<ResourceManager> managers = new ArrayList<>();
            for (Path i : hotDeploymentResourcePaths) {
                managers.add(new PathResourceManager(i));
            }
            managers.add(new ClassPathResourceManager(d.getClassLoader(), "META-INF/resources"));
            resourceManager = new DelegatingResourceManager(managers.toArray(new ResourceManager[0]));
        }

        if (launchMode.isProduction()) {
            //todo: cache configuration
            resourceManager = new CachingResourceManager(1000, 0, null, resourceManager, 2000);
        }
        d.setResourceManager(resourceManager);

        if (welcomeFiles != null) {
            // if available, use welcome-files from web.xml
            d.addWelcomePages(welcomeFiles);
        } else {
            d.addWelcomePages("index.html", "index.htm");
        }

        d.addServlet(new ServletInfo(ServletPathMatches.DEFAULT_SERVLET_NAME, DefaultServlet.class).setAsyncSupported(true));
        for (HandlerWrapper i : hotDeploymentWrappers) {
            d.addOuterHandlerChainWrapper(i);
        }
        d.addAuthenticationMechanism("QUARKUS", new ImmediateAuthenticationMechanismFactory(QuarkusAuthMechanism.INSTANCE));
        d.setLoginConfig(new LoginConfig("QUARKUS", "QUARKUS"));
        context.addShutdownTask(new ShutdownContext.CloseRunnable(d.getResourceManager()));

        d.addNotificationReceiver(new NotificationReceiver() {
            @Override
            public void handleNotification(SecurityNotification notification) {
                if (notification.getEventType() == SecurityNotification.EventType.AUTHENTICATED) {
                    QuarkusUndertowAccount account = (QuarkusUndertowAccount) notification.getAccount();
                    Instance<CurrentIdentityAssociation> instance = CDI.current().select(CurrentIdentityAssociation.class);
                    if (instance.isResolvable())
                        instance.get().setIdentity(account.getSecurityIdentity());
                }
            }
        });
        if (proactiveAuth) {
            d.setAuthenticationMode(AuthenticationMode.PRO_ACTIVE);
        } else {
            d.setAuthenticationMode(AuthenticationMode.CONSTRAINT_DRIVEN);
        }
        if (hasSecurityCapability) {
            //Fixes NPE at io.undertow.security.impl.SecurityContextImpl.login(SecurityContextImpl.java:198)
            d.setIdentityManager(CDI.current().select(IdentityManager.class).get());
        }
        return new RuntimeValue<>(d);
    }

    public static SocketAddress getHttpAddress() {
        return null;
    }

    public RuntimeValue<ServletInfo> registerServlet(RuntimeValue<DeploymentInfo> deploymentInfo,
            String name,
            Class<?> servletClass,
            boolean asyncSupported,
            int loadOnStartup,
            BeanContainer beanContainer,
            InstanceFactory<? extends Servlet> instanceFactory) throws Exception {

        InstanceFactory<? extends Servlet> factory = instanceFactory != null ? instanceFactory
                : new QuarkusInstanceFactory(beanContainer.beanInstanceFactory(servletClass));
        ServletInfo servletInfo = new ServletInfo(name, (Class<? extends Servlet>) servletClass,
                factory);
        deploymentInfo.getValue().addServlet(servletInfo);
        servletInfo.setAsyncSupported(asyncSupported);
        if (loadOnStartup > 0) {
            servletInfo.setLoadOnStartup(loadOnStartup);
        }
        return new RuntimeValue<>(servletInfo);
    }

    public void addServletInitParam(RuntimeValue<ServletInfo> info, String name, String value) {
        info.getValue().addInitParam(name, value);
    }

    public void addServletMapping(RuntimeValue<DeploymentInfo> info, String name, String mapping) throws Exception {
        ServletInfo sv = info.getValue().getServlets().get(name);
        if (sv != null) {
            sv.addMapping(mapping);
            if (LaunchMode.current() == LaunchMode.DEVELOPMENT) {
                ResourceNotFoundData.addServlet(mapping);
            }
        }
    }

    public void setMultipartConfig(RuntimeValue<ServletInfo> sref, String location, long fileSize, long maxRequestSize,
            int fileSizeThreshold) {
        MultipartConfigElement mp = new MultipartConfigElement(location, fileSize, maxRequestSize, fileSizeThreshold);
        sref.getValue().setMultipartConfig(mp);
    }

    /**
     * @param sref
     * @param securityInfo
     */
    public void setSecurityInfo(RuntimeValue<ServletInfo> sref, ServletSecurityInfo securityInfo) {
        sref.getValue().setServletSecurityInfo(securityInfo);
    }

    /**
     * @param sref
     * @param roleName
     * @param roleLink
     */
    public void addSecurityRoleRef(RuntimeValue<ServletInfo> sref, String roleName, String roleLink) {
        sref.getValue().addSecurityRoleRef(roleName, roleLink);
    }

    public RuntimeValue<FilterInfo> registerFilter(RuntimeValue<DeploymentInfo> info,
            String name, Class<?> filterClass,
            boolean asyncSupported,
            BeanContainer beanContainer,
            InstanceFactory<? extends Filter> instanceFactory) throws Exception {

        InstanceFactory<? extends Filter> factory = instanceFactory != null ? instanceFactory
                : new QuarkusInstanceFactory(beanContainer.beanInstanceFactory(filterClass));
        FilterInfo filterInfo = new FilterInfo(name, (Class<? extends Filter>) filterClass, factory);
        info.getValue().addFilter(filterInfo);
        filterInfo.setAsyncSupported(asyncSupported);
        return new RuntimeValue<>(filterInfo);
    }

    public void addFilterInitParam(RuntimeValue<FilterInfo> info, String name, String value) {
        info.getValue().addInitParam(name, value);
    }

    public void addFilterURLMapping(RuntimeValue<DeploymentInfo> info, String name, String mapping,
            DispatcherType dispatcherType) throws Exception {
        info.getValue().addFilterUrlMapping(name, mapping, dispatcherType);
    }

    public void addFilterServletNameMapping(RuntimeValue<DeploymentInfo> info, String name, String mapping,
            DispatcherType dispatcherType) throws Exception {
        info.getValue().addFilterServletNameMapping(name, mapping, dispatcherType);
    }

    public void registerListener(RuntimeValue<DeploymentInfo> info, Class<?> listenerClass, BeanContainer factory) {
        info.getValue()
                .addListener(new ListenerInfo((Class<? extends EventListener>) listenerClass,
                        (InstanceFactory<? extends EventListener>) new QuarkusInstanceFactory<>(
                                factory.beanInstanceFactory(listenerClass))));
    }

    public void addMimeMapping(RuntimeValue<DeploymentInfo> info, String extension,
            String mimeType) throws Exception {
        info.getValue().addMimeMapping(new MimeMapping(extension, mimeType));
    }

    public void addServletInitParameter(RuntimeValue<DeploymentInfo> info, String name, String value) {
        info.getValue().addInitParameter(name, value);
    }

    public void setupSecurity(DeploymentManager manager) {

        CDI.current().select(ServletHttpSecurityPolicy.class).get().setDeployment(manager.getDeployment());
    }

    public Handler<RoutingContext> startUndertow(ShutdownContext shutdown, ExecutorService executorService,
            DeploymentManager manager, List<HandlerWrapper> wrappers) throws Exception {
        shutdown.addShutdownTask(new Runnable() {
            @Override
            public void run() {
                try {
                    manager.stop();
                } catch (ServletException e) {
                    log.error("Failed to stop deployment", e);
                }
                manager.undeploy();
            }
        });
        HttpHandler main = manager.getDeployment().getHandler();
        for (HandlerWrapper i : wrappers) {
            main = i.wrap(main);
        }
        if (!manager.getDeployment().getDeploymentInfo().getContextPath().equals("/")) {
            PathHandler pathHandler = new PathHandler()
                    .addPrefixPath(manager.getDeployment().getDeploymentInfo().getContextPath(), main);
            main = pathHandler;
        }
        main = new CanonicalPathHandler(main);
        currentRoot = main;

        DefaultExchangeHandler defaultHandler = new DefaultExchangeHandler(ROOT_HANDLER);

        UndertowBufferAllocator allocator = new UndertowBufferAllocator(
                servletRuntimeConfig.getValue().directBuffers().orElse(DEFAULT_DIRECT_BUFFERS),
                (int) servletRuntimeConfig.getValue().bufferSize()
                        .orElse(new MemorySize(BigInteger.valueOf(DEFAULT_BUFFER_SIZE))).asLongValue());

        UndertowOptionMap.Builder undertowOptions = UndertowOptionMap.builder();
        undertowOptions.set(UndertowOptions.MAX_PARAMETERS, servletRuntimeConfig.getValue().maxParameters());
        UndertowOptionMap undertowOptionMap = undertowOptions.getMap();

        Set<String> compressMediaTypes = httpBuildTimeConfig.enableCompression()
                ? Set.copyOf(httpBuildTimeConfig.compressMediaTypes().get())
                : Collections.emptySet();

        return new Handler<RoutingContext>() {
            @Override
            public void handle(RoutingContext event) {
                if (!event.request().isEnded()) {
                    event.request().pause();
                }

                //we handle auth failure directly
                event.remove(QuarkusHttpUser.AUTH_FAILURE_HANDLER);

                VertxHttpExchange exchange = new VertxHttpExchange(event.request(), allocator, executorService, event,
                        event.getBody());
                exchange.setPushHandler(VertxHttpRecorder.getRootHandler());

                // Note that we can't add an end handler in a separate HttpCompressionHandler because VertxHttpExchange does set
                // its own end handler and so the end handlers added previously are just ignored...
                if (!compressMediaTypes.isEmpty()) {
                    event.addEndHandler(new Handler<AsyncResult<Void>>() {

                        @Override
                        public void handle(AsyncResult<Void> result) {
                            if (result.succeeded()) {
                                HttpCompressionHandler.compressIfNeeded(event, compressMediaTypes);
                            }
                        }
                    });
                }

                Optional<MemorySize> maxBodySize = httpRuntimeConfig.getValue().limits().maxBodySize();
                if (maxBodySize.isPresent()) {
                    exchange.setMaxEntitySize(maxBodySize.get().asLongValue());
                }
                Duration readTimeout = httpRuntimeConfig.getValue().readTimeout();
                exchange.setReadTimeout(readTimeout.toMillis());

                exchange.setUndertowOptions(undertowOptionMap);

                //we eagerly dispatch to the executor, as Undertow needs to be blocking anyway
                //it's actually possible to be on a different IO thread at this point which confuses Undertow
                //see https://github.com/quarkusio/quarkus/issues/7782
                if (BlockingOperationControl.isBlockingAllowed()) {
                    defaultHandler.handle(exchange);
                } else {
                    executorService.submit(new Runnable() {
                        @Override
                        public void run() {
                            defaultHandler.handle(exchange);
                        }
                    });
                }
            }
        };
    }

    public static void addHotDeploymentWrapper(HandlerWrapper handlerWrapper) {
        hotDeploymentWrappers.add(handlerWrapper);
    }

    public Supplier<ServletContext> servletContextSupplier() {
        return new ServletContextSupplier();
    }

    public DeploymentManager bootServletContainer(RuntimeValue<DeploymentInfo> info, BeanContainer beanContainer,
            LaunchMode launchMode, ShutdownContext shutdownContext, boolean decorateStacktrace, String scrMainJava,
            List<String> knownClasses) {
        if (info.getValue().getExceptionHandler() == null) {
            //if a 500 error page has not been mapped we change the default to our more modern one, with a UID in the
            //log. If this is not production we also include the stack trace
            boolean alreadyMapped500 = false;
            boolean alreadyMapped404 = false;
            for (ErrorPage i : info.getValue().getErrorPages()) {
                if (i.getErrorCode() != null && i.getErrorCode() == StatusCodes.INTERNAL_SERVER_ERROR) {
                    alreadyMapped500 = true;
                } else if (i.getErrorCode() != null && i.getErrorCode() == StatusCodes.NOT_FOUND) {
                    alreadyMapped404 = true;
                }
            }
            if (!alreadyMapped500 || launchMode.isDevOrTest()) {
                info.getValue().setExceptionHandler(new QuarkusExceptionHandler());
                info.getValue().addErrorPage(new ErrorPage("/@QuarkusError", StatusCodes.INTERNAL_SERVER_ERROR));
                String knownClassesString = null;
                if (knownClasses != null)
                    knownClassesString = String.join(",", knownClasses);
                info.getValue().addServlet(new ServletInfo("@QuarkusError", QuarkusErrorServlet.class)
                        .addMapping("/@QuarkusError").setAsyncSupported(true)
                        .addInitParam(QuarkusErrorServlet.SHOW_STACK, Boolean.toString(launchMode.isDevOrTest()))
                        .addInitParam(QuarkusErrorServlet.SHOW_DECORATION,
                                Boolean.toString(decorateStacktrace(launchMode, decorateStacktrace)))
                        .addInitParam(QuarkusErrorServlet.SRC_MAIN_JAVA, scrMainJava)
                        .addInitParam(QuarkusErrorServlet.KNOWN_CLASSES, knownClassesString));
            }
            if (!alreadyMapped404 && launchMode.equals(LaunchMode.DEVELOPMENT)) {
                info.getValue().addErrorPage(new ErrorPage("/@QuarkusNotFound", StatusCodes.NOT_FOUND));
                info.getValue().addServlet(new ServletInfo("@QuarkusNotFound", QuarkusNotFoundServlet.class)
                        .addMapping("/@QuarkusNotFound").setAsyncSupported(true));
            }
        }
        setupRequestScope(info.getValue(), beanContainer);

        try {
            ClassIntrospecter defaultVal = info.getValue().getClassIntrospecter();
            info.getValue().setClassIntrospecter(new ClassIntrospecter() {
                @Override
                public <T> InstanceFactory<T> createInstanceFactory(Class<T> clazz) throws NoSuchMethodException {
                    BeanContainer.Factory<T> res = beanContainer.beanInstanceFactory(clazz);
                    if (res == null) {
                        return defaultVal.createInstanceFactory(clazz);
                    }
                    return new InstanceFactory<T>() {
                        @Override
                        public InstanceHandle<T> createInstance() throws InstantiationException {
                            BeanContainer.Instance<T> ih = res.create();
                            return new InstanceHandle<T>() {
                                @Override
                                public T getInstance() {
                                    return ih.get();
                                }

                                @Override
                                public void release() {
                                    ih.close();
                                }
                            };
                        }
                    };
                }
            });
            ExceptionHandler existing = info.getValue().getExceptionHandler();
            info.getValue().setExceptionHandler(new ExceptionHandler() {
                @Override
                public boolean handleThrowable(HttpServerExchange exchange, ServletRequest request, ServletResponse response,
                        Throwable throwable) {
                    if (throwable instanceof AuthenticationFailedException || throwable instanceof UnauthorizedException) {
                        String location = servletContext.getDeployment().getErrorPages().getErrorLocation(throwable);
                        //if these have been mapped we use the mapping
                        if (location == null || location.equals("/@QuarkusError")) {
                            if (throwable instanceof AuthenticationFailedException
                                    || exchange.getSecurityContext().getAuthenticatedAccount() == null) {
                                if (!exchange.isResponseStarted()) {
                                    exchange.getSecurityContext().setAuthenticationRequired();
                                    if (exchange.getSecurityContext().authenticate()) {
                                        //if we can authenticate then the request is just forbidden
                                        //this can happen with lazy auth
                                        exchange.setStatusCode(StatusCodes.FORBIDDEN);
                                    }
                                }
                            } else {
                                exchange.setStatusCode(StatusCodes.FORBIDDEN);
                            }
                            return true;
                        }
                    } else if (throwable instanceof ForbiddenException) {
                        String location = servletContext.getDeployment().getErrorPages().getErrorLocation(throwable);
                        //if these have been mapped we use the mapping
                        if (location == null || location.equals("/@QuarkusError")) {
                            exchange.setStatusCode(StatusCodes.FORBIDDEN);
                            return true;
                        }
                    }
                    return existing.handleThrowable(exchange, request, response, throwable);
                }
            });

            ServletContainer servletContainer = Servlets.defaultContainer();
            DeploymentManager manager = servletContainer.addDeployment(info.getValue());
            manager.deploy();
            manager.start();
            servletContext = manager.getDeployment().getServletContext();
            shutdownContext.addShutdownTask(new Runnable() {
                @Override
                public void run() {
                    servletContext = null;
                }
            });
            return manager;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void addServletContextAttribute(RuntimeValue<DeploymentInfo> deployment, String key, Object value1) {
        if (value1 instanceof RuntimeValue) {
            deployment.getValue().addServletContextAttribute(key, ((RuntimeValue<?>) value1).getValue());
        } else {
            deployment.getValue().addServletContextAttribute(key, value1);
        }
    }

    public void addServletExtension(RuntimeValue<DeploymentInfo> deployment, ServletExtension extension) {
        deployment.getValue().addServletExtension(extension);
    }

    public void setupRequestScope(DeploymentInfo deploymentInfo, BeanContainer beanContainer) {
        CurrentVertxRequest currentVertxRequest = CDI.current().select(CurrentVertxRequest.class).get();
        Instance<CurrentIdentityAssociation> identityAssociations = CDI.current()
                .select(CurrentIdentityAssociation.class);
        CurrentIdentityAssociation association;
        if (identityAssociations.isResolvable()) {
            association = identityAssociations.get();
        } else {
            association = null;
        }
        deploymentInfo.addThreadSetupAction(new ThreadSetupHandler() {
            @Override
            public <T, C> ThreadSetupHandler.Action<T, C> create(Action<T, C> action) {
                return new Action<T, C>() {
                    @Override
                    public T call(HttpServerExchange exchange, C context) throws Exception {
                        // Not sure what to do here
                        ManagedContext requestContext = beanContainer.requestContext();
                        if (requestContext.isActive()) {
                            if (currentVertxRequest.getCurrent() == null && exchange != null
                                    && exchange.getDelegate() instanceof VertxHttpExchange) {
                                // goal here is to add event to the Vert.X request when Smallrye Context Propagation
                                // creates fresh instance of request context without the event; we experienced
                                // the request context activated and terminated by ActivateRequestContextInterceptor
                                // invoked for the SecurityIdentityAugmentor that was (re)created during permission checks
                                addEventToVertxRequest(exchange);
                            }

                            return action.call(exchange, context);
                        } else if (exchange == null) {
                            requestContext.activate();
                            try {
                                return action.call(exchange, context);
                            } finally {
                                requestContext.terminate();
                            }
                        } else {
                            InjectableContext.ContextState existingRequestContext = exchange
                                    .getAttachment(REQUEST_CONTEXT);
                            try {
                                requestContext.activate(existingRequestContext);

                                RoutingContext rc = addEventToVertxRequest(exchange);

                                if (association != null) {
                                    QuarkusHttpUser existing = (QuarkusHttpUser) rc.user();
                                    if (existing != null) {
                                        SecurityIdentity identity = existing.getSecurityIdentity();
                                        association.setIdentity(identity);
                                    } else {
                                        association.setIdentity(QuarkusHttpUser.getSecurityIdentity(rc, null));
                                    }
                                }

                                return action.call(exchange, context);
                            } finally {
                                ServletRequestContext src = exchange
                                        .getAttachment(ServletRequestContext.ATTACHMENT_KEY);
                                HttpServletRequestImpl req = src.getOriginalRequest();
                                if (req.isAsyncStarted()) {
                                    exchange.putAttachment(REQUEST_CONTEXT, requestContext.getState());
                                    requestContext.deactivate();
                                    if (existingRequestContext == null) {
                                        req.getAsyncContextInternal().addListener(new AsyncListener() {
                                            @Override
                                            public void onComplete(AsyncEvent event) throws IOException {
                                                requestContext.activate(exchange
                                                        .getAttachment(REQUEST_CONTEXT));
                                                requestContext.terminate();
                                            }

                                            @Override
                                            public void onTimeout(AsyncEvent event) throws IOException {
                                                onComplete(event);
                                            }

                                            @Override
                                            public void onError(AsyncEvent event) throws IOException {
                                                onComplete(event);
                                            }

                                            @Override
                                            public void onStartAsync(AsyncEvent event) throws IOException {

                                            }
                                        });
                                    }
                                } else {
                                    requestContext.terminate();
                                }
                            }
                        }
                    }

                    private RoutingContext addEventToVertxRequest(HttpServerExchange exchange) {
                        VertxHttpExchange delegate = (VertxHttpExchange) exchange.getDelegate();
                        RoutingContext rc = (RoutingContext) delegate.getContext();
                        currentVertxRequest.setCurrent(rc);
                        return rc;
                    }
                };
            }
        });
    }

    public void addServletContainerInitializer(RuntimeValue<DeploymentInfo> deployment,
            Class<? extends ServletContainerInitializer> sciClass, Set<Class<?>> handlesTypes) {
        deployment.getValue().addServletContainerInitializer(new ServletContainerInitializerInfo(sciClass, handlesTypes));
    }

    public void addContextParam(RuntimeValue<DeploymentInfo> deployment, String paramName, String paramValue) {
        deployment.getValue().addInitParameter(paramName, paramValue);
    }

    public void setDenyUncoveredHttpMethods(RuntimeValue<DeploymentInfo> deployment, boolean denyUncoveredHttpMethods) {
        deployment.getValue().setDenyUncoveredHttpMethods(denyUncoveredHttpMethods);
    }

    public void addSecurityConstraint(RuntimeValue<DeploymentInfo> deployment, SecurityConstraint securityConstraint) {
        deployment.getValue().addSecurityConstraint(securityConstraint);
    }

    public void addSecurityConstraint(RuntimeValue<DeploymentInfo> deployment, SecurityInfo.EmptyRoleSemantic emptyRoleSemantic,
            TransportGuaranteeType transportGuaranteeType,
            Set<String> rolesAllowed, Set<WebResourceCollection> webResourceCollections) {

        SecurityConstraint securityConstraint = new SecurityConstraint()
                .setEmptyRoleSemantic(emptyRoleSemantic)
                .addRolesAllowed(rolesAllowed)
                .setTransportGuaranteeType(transportGuaranteeType)
                .addWebResourceCollections(webResourceCollections.toArray(new WebResourceCollection[0]));
        deployment.getValue().addSecurityConstraint(securityConstraint);

    }

    public void setSessionTimeout(RuntimeValue<DeploymentInfo> deployment, int sessionTimeout) {
        deployment.getValue().setDefaultSessionTimeout(sessionTimeout * 60);
    }

    public ServletSessionConfig sessionConfig(RuntimeValue<DeploymentInfo> deployment) {
        ServletSessionConfig config = new ServletSessionConfig();
        deployment.getValue().setServletSessionConfig(config);
        return config;
    }

    public void setSessionTracking(ServletSessionConfig config, Set<SessionTrackingMode> modes) {
        config.setSessionTrackingModes(modes);
    }

    public void setSessionCookieConfig(ServletSessionConfig config, String name, String path, String comment, String domain,
            Boolean httpOnly, Integer maxAge, Boolean secure) {
        if (name != null) {
            config.setName(name);
        }
        if (path != null) {
            config.setPath(path);
        }
        if (comment != null) {
            config.setComment(comment);
        }
        if (domain != null) {
            config.setDomain(domain);
        }
        if (httpOnly != null) {
            config.setHttpOnly(httpOnly);
        }
        if (maxAge != null) {
            config.setMaxAge(maxAge);
        }
        if (secure != null) {
            config.setSecure(secure);
        }
    }

    public void addErrorPage(RuntimeValue<DeploymentInfo> deployment, String location, int errorCode) {
        deployment.getValue().addErrorPage(new ErrorPage(location, errorCode));

    }

    public void addErrorPage(RuntimeValue<DeploymentInfo> deployment, String location,
            Class<? extends Throwable> exceptionType) {
        deployment.getValue().addErrorPage(new ErrorPage(location, exceptionType));
    }

    private boolean decorateStacktrace(LaunchMode launchMode, boolean decorateStacktrace) {
        return decorateStacktrace && launchMode.equals(LaunchMode.DEVELOPMENT);
    }

    /**
     * we can't have SecureRandom in the native image heap, so we need to lazy init
     */
    private static class QuarkusSessionIdGenerator implements SessionIdGenerator {

        private volatile SecureRandom random;

        private volatile int length = 30;

        private static final char[] SESSION_ID_ALPHABET;

        private static final String ALPHABET_PROPERTY = "io.undertow.server.session.SecureRandomSessionIdGenerator.ALPHABET";

        static {
            String alphabet = System.getProperty(ALPHABET_PROPERTY,
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_");
            if (alphabet.length() != 64) {
                throw new RuntimeException(
                        "io.undertow.server.session.SecureRandomSessionIdGenerator must be exactly 64 characters long");
            }
            SESSION_ID_ALPHABET = alphabet.toCharArray();
        }

        @Override
        public String createSessionId() {
            if (random == null) {
                random = new SecureRandom();
            }
            final byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            return new String(encode(bytes));
        }

        public int getLength() {
            return length;
        }

        public void setLength(final int length) {
            this.length = length;
        }

        /**
         * Encode the bytes into a String with a slightly modified Base64-algorithm
         * This code was written by Kevin Kelley <kelley@ruralnet.net>
         * and adapted by Thomas Peuss <jboss@peuss.de>
         *
         * @param data The bytes you want to encode
         * @return the encoded String
         */
        private char[] encode(byte[] data) {
            char[] out = new char[((data.length + 2) / 3) * 4];
            char[] alphabet = SESSION_ID_ALPHABET;
            //
            // 3 bytes encode to 4 chars.  Output is always an even
            // multiple of 4 characters.
            //
            for (int i = 0, index = 0; i < data.length; i += 3, index += 4) {
                boolean quad = false;
                boolean trip = false;

                int val = (0xFF & (int) data[i]);
                val <<= 8;
                if ((i + 1) < data.length) {
                    val |= (0xFF & (int) data[i + 1]);
                    trip = true;
                }
                val <<= 8;
                if ((i + 2) < data.length) {
                    val |= (0xFF & (int) data[i + 2]);
                    quad = true;
                }
                out[index + 3] = alphabet[(quad ? (val & 0x3F) : 63)];
                val >>= 6;
                out[index + 2] = alphabet[(trip ? (val & 0x3F) : 63)];
                val >>= 6;
                out[index + 1] = alphabet[val & 0x3F];
                val >>= 6;
                out[index] = alphabet[val & 0x3F];
            }
            return out;
        }
    }

    public static class ServletContextSupplier implements Supplier<ServletContext> {

        @Override
        public ServletContext get() {
            return servletContext;
        }
    }

    private static class UndertowBufferAllocator implements BufferAllocator {

        private final boolean defaultDirectBuffers;
        private final int defaultBufferSize;

        private UndertowBufferAllocator(boolean defaultDirectBuffers, int defaultBufferSize) {
            this.defaultDirectBuffers = defaultDirectBuffers;
            this.defaultBufferSize = defaultBufferSize;
        }

        @Override
        public ByteBuf allocateBuffer() {
            return allocateBuffer(defaultDirectBuffers);
        }

        @Override
        public ByteBuf allocateBuffer(boolean direct) {
            if (direct) {
                return ByteBufAllocator.DEFAULT.directBuffer(defaultBufferSize);
            } else {
                return ByteBufAllocator.DEFAULT.heapBuffer(defaultBufferSize);
            }
        }

        @Override
        public ByteBuf allocateBuffer(int bufferSize) {
            return allocateBuffer(defaultDirectBuffers, bufferSize);
        }

        @Override
        public ByteBuf allocateBuffer(boolean direct, int bufferSize) {
            if (direct) {
                return ByteBufAllocator.DEFAULT.directBuffer(bufferSize);
            } else {
                return ByteBufAllocator.DEFAULT.heapBuffer(bufferSize);
            }
        }

        @Override
        public int getBufferSize() {
            return defaultBufferSize;
        }
    }
}
