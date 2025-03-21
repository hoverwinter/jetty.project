//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.servlet;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.TimeUnit;
import javax.servlet.GenericServlet;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletSecurityElement;
import javax.servlet.SingleThreadModel;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.RunAsToken;
import org.eclipse.jetty.server.MultiPartCleanerListener;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Servlet Instance and Context Holder.
 * <p>
 * Holds the name, params and some state of a javax.servlet.Servlet
 * instance. It implements the ServletConfig interface.
 * This class will organise the loading of the servlet when needed or
 * requested.
 */
@ManagedObject("Servlet Holder")
public class ServletHolder extends Holder<Servlet> implements UserIdentity.Scope, Comparable<ServletHolder>
{
    private static final Logger LOG = Log.getLogger(ServletHolder.class);
    private int _initOrder = -1;
    private boolean _initOnStartup = false;
    private Map<String, String> _roleMap;
    private String _forcedPath;
    private String _runAsRole;
    private RunAsToken _runAsToken;
    private IdentityService _identityService;
    private ServletRegistration.Dynamic _registration;
    private JspContainer _jspContainer;

    private volatile Servlet _servlet;
    private Config _config;
    private boolean _enabled = true;

    public static final String APACHE_SENTINEL_CLASS = "org.apache.tomcat.InstanceManager";
    public static final String JSP_GENERATED_PACKAGE_NAME = "org.eclipse.jetty.servlet.jspPackagePrefix";

    public enum JspContainer
    {
        APACHE, OTHER
    }

    /**
     * Constructor .
     */
    public ServletHolder()
    {
        this(Source.EMBEDDED);
    }

    /**
     * Constructor .
     *
     * @param creator the holder source
     */
    public ServletHolder(Source creator)
    {
        super(creator);
    }

    /**
     * Constructor for existing servlet.
     *
     * @param servlet the servlet
     */
    public ServletHolder(Servlet servlet)
    {
        this(Source.EMBEDDED);
        setServlet(servlet);
    }

    /**
     * Constructor for servlet class.
     *
     * @param name the name of the servlet
     * @param servlet the servlet class
     */
    public ServletHolder(String name, Class<? extends Servlet> servlet)
    {
        this(Source.EMBEDDED);
        setName(name);
        setHeldClass(servlet);
    }

    /**
     * Constructor for servlet class.
     *
     * @param name the servlet name
     * @param servlet the servlet
     */
    public ServletHolder(String name, Servlet servlet)
    {
        this(Source.EMBEDDED);
        setName(name);
        setServlet(servlet);
    }

    /**
     * Constructor for servlet class.
     *
     * @param servlet the servlet class
     */
    public ServletHolder(Class<? extends Servlet> servlet)
    {
        this(Source.EMBEDDED);
        setHeldClass(servlet);
    }

    /**
     * @return The unavailable exception or null if not unavailable
     */
    public UnavailableException getUnavailableException()
    {
        Servlet servlet = _servlet;
        if (servlet instanceof UnavailableServlet)
            return ((UnavailableServlet)servlet).getUnavailableException();
        return null;
    }

    public synchronized void setServlet(Servlet servlet)
    {
        if (servlet == null || servlet instanceof SingleThreadModel)
            throw new IllegalArgumentException();

        setInstance(servlet);
    }

    @ManagedAttribute(value = "initialization order", readonly = true)
    public int getInitOrder()
    {
        return _initOrder;
    }

    /**
     * Set the initialize order.
     * <p>
     * Holders with order&lt;0, are initialized on use. Those with
     * order&gt;=0 are initialized in increasing order when the handler
     * is started.
     *
     * @param order the servlet init order
     */
    public void setInitOrder(int order)
    {
        _initOnStartup = order >= 0;
        _initOrder = order;
    }

    /**
     * Comparator by init order.
     */
    @Override
    public int compareTo(ServletHolder sh)
    {
        if (sh == this)
            return 0;

        if (sh._initOrder < _initOrder)
            return 1;

        if (sh._initOrder > _initOrder)
            return -1;

        // consider getClassName(), need to position properly when one is configured but not the other
        int c;
        if (getClassName() == null && sh.getClassName() == null)
            c = 0;
        else if (getClassName() == null)
            c = -1;
        else if (sh.getClassName() == null)
            c = 1;
        else
            c = getClassName().compareTo(sh.getClassName());

        // if _initOrder and getClassName() are the same, consider the getName()
        if (c == 0)
            c = getName().compareTo(sh.getName());

        return c;
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof ServletHolder && compareTo((ServletHolder)o) == 0;
    }

    @Override
    public int hashCode()
    {
        return getName() == null ? System.identityHashCode(this) : getName().hashCode();
    }

    /**
     * Link a user role.
     * Translate the role name used by a servlet, to the link name
     * used by the container.
     *
     * @param name The role name as used by the servlet
     * @param link The role name as used by the container.
     */
    public synchronized void setUserRoleLink(String name, String link)
    {
        if (_roleMap == null)
            _roleMap = new HashMap<>();
        _roleMap.put(name, link);
    }

    /**
     * get a user role link.
     *
     * @param name The name of the role
     * @return The name as translated by the link. If no link exists,
     * the name is returned.
     */
    public String getUserRoleLink(String name)
    {
        if (_roleMap == null)
            return name;
        String link = _roleMap.get(name);
        return (link == null) ? name : link;
    }

    /**
     * @return Returns the forcedPath.
     */
    @ManagedAttribute(value = "forced servlet path", readonly = true)
    public String getForcedPath()
    {
        return _forcedPath;
    }

    /**
     * @param forcedPath The forcedPath to set.
     */
    public void setForcedPath(String forcedPath)
    {
        _forcedPath = forcedPath;
    }

    public boolean isEnabled()
    {
        return _enabled;
    }

    public void setEnabled(boolean enabled)
    {
        _enabled = enabled;
    }

    @Override
    public void doStart()
        throws Exception
    {
        if (!_enabled)
            return;

        // Handle JSP file forced paths
        if (_forcedPath != null)
        {
            // Look for a precompiled JSP Servlet
            String precompiled = getClassNameForJsp(_forcedPath);
            if (!StringUtil.isBlank(precompiled))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Checking for precompiled servlet {} for jsp {}", precompiled, _forcedPath);
                ServletHolder jsp = getServletHandler().getServlet(precompiled);
                if (jsp != null && jsp.getClassName() != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("JSP file {} for {} mapped to Servlet {}", _forcedPath, getName(), jsp.getClassName());
                    // set the className for this servlet to the precompiled one
                    setClassName(jsp.getClassName());
                }
                else
                {
                    // Look for normal JSP servlet
                    jsp = getServletHandler().getServlet("jsp");
                    if (jsp != null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("JSP file {} for {} mapped to JspServlet class {}", _forcedPath, getName(), jsp.getClassName());
                        setClassName(jsp.getClassName());
                        //copy jsp init params that don't exist for this servlet
                        for (Map.Entry<String, String> entry : jsp.getInitParameters().entrySet())
                        {
                            if (!getInitParameters().containsKey(entry.getKey()))
                                setInitParameter(entry.getKey(), entry.getValue());
                        }
                        //jsp specific: set up the jsp-file on the JspServlet. If load-on-startup is >=0 and the jsp container supports
                        //precompilation, the jsp will be compiled when this holder is initialized. If not load on startup, or the
                        //container does not support startup precompilation, it will be compiled at runtime when handling a request for this jsp.
                        //See also adaptForcedPathToJspContainer
                        setInitParameter("jspFile", _forcedPath);
                    }
                }
            }
            else
                LOG.warn("Bad jsp-file {} conversion to classname in holder {}", _forcedPath, getName());
        }

        //check servlet has a class (ie is not a preliminary registration). If preliminary, fail startup.
        try
        {
            super.doStart();
        }
        catch (UnavailableException ex)
        {
            makeUnavailable(ex);
            if (getServletHandler().isStartWithUnavailable())
            {
                LOG.ignore(ex);
                return;
            }
            else
                throw ex;
        }

        //servlet is not an instance of javax.servlet.Servlet
        try
        {
            checkServletType();
        }
        catch (UnavailableException ex)
        {
            makeUnavailable(ex);
            if (getServletHandler().isStartWithUnavailable())
            {
                LOG.ignore(ex);
                return;
            }
            else
                throw ex;
        }

        //check if we need to forcibly set load-on-startup
        checkInitOnStartup();

        if (_runAsRole == null)
        {
            _identityService = null;
            _runAsToken = null;
        }
        else
        {
            _identityService = getServletHandler().getIdentityService();
            if (_identityService != null)
                _runAsToken = _identityService.newRunAsToken(_runAsRole);
        }

        _config = new Config();

        synchronized (this)
        {
            if (getHeldClass() != null && javax.servlet.SingleThreadModel.class.isAssignableFrom(getHeldClass()))
                _servlet = new SingleThreadedWrapper();
        }
    }

    @Override
    public void initialize()
        throws Exception
    {
        synchronized (this)
        {
            if (_servlet == null && (_initOnStartup || isInstance()))
            {
                super.initialize();
                initServlet();
            }
        }
    }

    @Override
    public void doStop()
        throws Exception
    {
        synchronized (this)
        {
            Servlet servlet = _servlet;
            if (servlet != null)
            {
                _servlet = null;
                try
                {
                    destroyInstance(servlet);
                }
                catch (Exception e)
                {
                    LOG.warn(e);
                }
            }
            _config = null;
        }
    }

    @Override
    public void destroyInstance(Object o)
        throws Exception
    {
        if (o == null)
            return;
        Servlet servlet = ((Servlet)o);
        getServletHandler().destroyServlet(servlet);
        servlet.destroy();
    }

    /**
     * Get the servlet.
     *
     * @return The servlet
     * @throws ServletException if unable to init the servlet on first use
     */
    public Servlet getServlet()
        throws ServletException
    {
        Servlet servlet = _servlet;
        if (servlet == null)
        {
            synchronized (this)
            {
                servlet = _servlet;
                if (servlet == null && isRunning())
                {
                    if (getHeldClass() != null)
                        initServlet();
                    servlet = _servlet;
                }
            }
        }
        return servlet;
    }

    /**
     * Get the servlet instance (no initialization done).
     *
     * @return The servlet or null
     */
    public Servlet getServletInstance()
    {
        return _servlet;
    }

    /**
     * Check to ensure class of servlet is acceptable.
     *
     * @throws UnavailableException if Servlet class is not of type {@link javax.servlet.Servlet}
     */
    public void checkServletType()
        throws UnavailableException
    {
        if (getHeldClass() == null || !javax.servlet.Servlet.class.isAssignableFrom(getHeldClass()))
        {
            throw new UnavailableException("Servlet " + getHeldClass() + " is not a javax.servlet.Servlet");
        }
    }

    /**
     * @return true if the holder is started and is not unavailable
     */
    public boolean isAvailable()
    {
        return (isStarted() && !(_servlet instanceof UnavailableServlet));
    }

    /**
     * Check if there is a javax.servlet.annotation.ServletSecurity
     * annotation on the servlet class. If there is, then we force
     * it to be loaded on startup, because all of the security
     * constraints must be calculated as the container starts.
     */
    private void checkInitOnStartup()
    {
        if (getHeldClass() == null)
            return;

        if ((getHeldClass().getAnnotation(javax.servlet.annotation.ServletSecurity.class) != null) && !_initOnStartup)
            setInitOrder(Integer.MAX_VALUE);
    }

    private Servlet makeUnavailable(UnavailableException e)
    {
        synchronized (this)
        {
            _servlet = new UnavailableServlet(e, _servlet);
            return _servlet;
        }
    }

    private void makeUnavailable(final Throwable e)
    {
        if (e instanceof UnavailableException)
            makeUnavailable((UnavailableException)e);
        else
        {
            ServletContext ctx = getServletHandler().getServletContext();
            if (ctx == null)
                LOG.info("unavailable", e);
            else
                ctx.log("unavailable", e);
            UnavailableException unavailable = new UnavailableException(String.valueOf(e), -1)
            {
                {
                    initCause(e);
                }
            };
            makeUnavailable(unavailable);
        }
    }

    private synchronized void initServlet()
        throws ServletException
    {
        try
        {
            if (_servlet == null)
                _servlet = getInstance();
            if (_servlet == null)
                _servlet = newInstance();
            if (_config == null)
                _config = new Config();

            // Handle run as
            if (_identityService != null && _runAsToken != null)
                _servlet = new RunAsServlet(_servlet, _identityService, _runAsToken);

            if (!isAsyncSupported())
                _servlet = new NotAsyncServlet(_servlet);

            // Handle configuring servlets that implement org.apache.jasper.servlet.JspServlet
            if (isJspServlet())
            {
                initJspServlet();
                detectJspContainer();
            }
            else if (_forcedPath != null)
                detectJspContainer();

            initMultiPart();

            if (LOG.isDebugEnabled())
                LOG.debug("Servlet.init {} for {}", _servlet, getName());
            _servlet.init(_config);
        }
        catch (UnavailableException e)
        {
            makeUnavailable(e);
            if (getServletHandler().isStartWithUnavailable())
                LOG.warn(e);
            else
                throw e;
        }
        catch (ServletException e)
        {
            makeUnavailable(e.getCause() == null ? e : e.getCause());
            throw e;
        }
        catch (Exception e)
        {
            makeUnavailable(e);
            throw new ServletException(this.toString(), e);
        }
    }

    /**
     * @throws Exception if unable to init the JSP Servlet
     */
    protected void initJspServlet() throws Exception
    {
        ContextHandler ch = ContextHandler.getContextHandler(getServletHandler().getServletContext());

        /* Set the webapp's classpath for Jasper */
        ch.setAttribute("org.apache.catalina.jspgetHeldClass()path", ch.getClassPath());

        /* Set up other classpath attribute */
        if ("?".equals(getInitParameter("classpath")))
        {
            String classpath = ch.getClassPath();
            if (LOG.isDebugEnabled())
                LOG.debug("classpath=" + classpath);
            if (classpath != null)
                setInitParameter("classpath", classpath);
        }

        /* ensure scratch dir */
        File scratch;
        if (getInitParameter("scratchdir") == null)
        {
            File tmp = (File)getServletHandler().getServletContext().getAttribute(ServletContext.TEMPDIR);
            scratch = new File(tmp, "jsp");
            setInitParameter("scratchdir", scratch.getAbsolutePath());
        }

        scratch = new File(getInitParameter("scratchdir"));
        if (!scratch.exists())
            scratch.mkdir();
    }

    /**
     * Register a ServletRequestListener that will ensure tmp multipart
     * files are deleted when the request goes out of scope.
     *
     * @throws Exception if unable to init the multipart
     */
    protected void initMultiPart() throws Exception
    {
        //if this servlet can handle multipart requests, ensure tmp files will be
        //cleaned up correctly
        if (((Registration)getRegistration()).getMultipartConfig() != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("multipart cleanup listener added for {}", this);

            //Register a listener to delete tmp files that are created as a result of this
            //servlet calling Request.getPart() or Request.getParts()
            ContextHandler ch = ContextHandler.getContextHandler(getServletHandler().getServletContext());
            if (!Arrays.asList(ch.getEventListeners()).contains(MultiPartCleanerListener.INSTANCE))
                ch.addEventListener(MultiPartCleanerListener.INSTANCE);
        }
    }

    @Override
    public ContextHandler getContextHandler()
    {
        return ContextHandler.getContextHandler(_config.getServletContext());
    }

    /**
     * @see org.eclipse.jetty.server.UserIdentity.Scope#getContextPath()
     */
    @Override
    public String getContextPath()
    {
        return _config.getServletContext().getContextPath();
    }

    /**
     * @see org.eclipse.jetty.server.UserIdentity.Scope#getRoleRefMap()
     */
    @Override
    public Map<String, String> getRoleRefMap()
    {
        return _roleMap;
    }

    @ManagedAttribute(value = "role to run servlet as", readonly = true)
    public String getRunAsRole()
    {
        return _runAsRole;
    }

    public void setRunAsRole(String role)
    {
        _runAsRole = role;
    }

    /**
     * Prepare to service a request.
     *
     * @param baseRequest the base request
     * @param request the request
     * @param response the response
     * @throws ServletException if unable to prepare the servlet
     * @throws UnavailableException if not available
     */
    protected void prepare(Request baseRequest, ServletRequest request, ServletResponse response)
        throws ServletException, UnavailableException
    {
        getServlet();
        MultipartConfigElement mpce = ((Registration)getRegistration()).getMultipartConfig();
        if (mpce != null)
            baseRequest.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, mpce);
    }

    @Deprecated
    public Servlet ensureInstance()
        throws ServletException
    {
        return getServlet();
    }

    /**
     * Service a request with this servlet.
     *
     * @param baseRequest the base request
     * @param request the request
     * @param response the response
     * @throws ServletException if unable to process the servlet
     * @throws UnavailableException if servlet is unavailable
     * @throws IOException if unable to process the request or response
     */
    public void handle(Request baseRequest,
                       ServletRequest request,
                       ServletResponse response)
        throws ServletException,
        UnavailableException,
        IOException
    {
        try
        {
            Servlet servlet = getServlet();
            if (servlet == null)
                throw new UnavailableException("Servlet Not Initialized");
            servlet.service(request, response);
        }
        catch (UnavailableException e)
        {
            makeUnavailable(e).service(request, response);
        }
    }

    protected boolean isJspServlet()
    {
        Servlet servlet = getServletInstance();
        Class<?> c = servlet == null ? getHeldClass() : servlet.getClass();

        while (c != null)
        {
            if (isJspServlet(c.getName()))
                return true;
            c = c.getSuperclass();
        }
        return false;
    }

    protected boolean isJspServlet(String classname)
    {
        if (classname == null)
            return false;
        return ("org.apache.jasper.servlet.JspServlet".equals(classname));
    }

    private void detectJspContainer()
    {
        if (_jspContainer == null)
        {
            try
            {
                //check for apache
                Loader.loadClass(APACHE_SENTINEL_CLASS);
                if (LOG.isDebugEnabled())
                    LOG.debug("Apache jasper detected");
                _jspContainer = JspContainer.APACHE;
            }
            catch (ClassNotFoundException x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Other jasper detected");
                _jspContainer = JspContainer.OTHER;
            }
        }
    }

    /**
     * @param jsp the jsp-file
     * @return the simple classname of the jsp
     */
    public String getNameOfJspClass(String jsp)
    {
        if (StringUtil.isBlank(jsp))
            return ""; //empty

        jsp = jsp.trim();
        if ("/".equals(jsp))
            return ""; //only slash

        int i = jsp.lastIndexOf('/');
        if (i == jsp.length() - 1)
            return ""; //ends with slash

        jsp = jsp.substring(i + 1);
        try
        {
            Class<?> jspUtil = Loader.loadClass("org.apache.jasper.compiler.JspUtil");
            Method makeJavaIdentifier = jspUtil.getMethod("makeJavaIdentifier", String.class);
            return (String)makeJavaIdentifier.invoke(null, jsp);
        }
        catch (Exception e)
        {
            String tmp = StringUtil.replace(jsp, '.', '_');
            if (LOG.isDebugEnabled())
            {
                LOG.warn("JspUtil.makeJavaIdentifier failed for jsp " + jsp + " using " + tmp + " instead");
                LOG.warn(e);
            }
            return tmp;
        }
    }

    public String getPackageOfJspClass(String jsp)
    {
        if (jsp == null)
            return "";

        int i = jsp.lastIndexOf('/');
        if (i <= 0)
            return "";
        try
        {
            Class<?> jspUtil = Loader.loadClass("org.apache.jasper.compiler.JspUtil");
            Method makeJavaPackage = jspUtil.getMethod("makeJavaPackage", String.class);
            return (String)makeJavaPackage.invoke(null, jsp.substring(0, i));
        }
        catch (Exception e)
        {
            String tmp = jsp;

            //remove any leading slash
            int s = 0;
            if ('/' == (tmp.charAt(0)))
                s = 1;

            //remove the element after last slash, which should be name of jsp
            tmp = tmp.substring(s, i).trim();

            tmp = StringUtil.replace(tmp, '/', '.');
            tmp = (".".equals(tmp) ? "" : tmp);
            if (LOG.isDebugEnabled())
            {
                LOG.warn("JspUtil.makeJavaPackage failed for " + jsp + " using " + tmp + " instead");
                LOG.warn(e);
            }
            return tmp;
        }
    }

    /**
     * @return the package for all jsps
     */
    public String getJspPackagePrefix()
    {
        String jspPackageName = null;

        if (getServletHandler() != null && getServletHandler().getServletContext() != null)
            jspPackageName = getServletHandler().getServletContext().getInitParameter(JSP_GENERATED_PACKAGE_NAME);

        if (jspPackageName == null)
            jspPackageName = "org.apache.jsp";

        return jspPackageName;
    }

    /**
     * @param jsp the jsp-file from web.xml
     * @return the fully qualified classname
     */
    public String getClassNameForJsp(String jsp)
    {
        if (jsp == null)
            return null;

        String name = getNameOfJspClass(jsp);
        if (StringUtil.isBlank(name))
            return null;

        StringBuffer fullName = new StringBuffer();
        appendPath(fullName, getJspPackagePrefix());
        appendPath(fullName, getPackageOfJspClass(jsp));
        appendPath(fullName, name);
        return fullName.toString();
    }

    /**
     * Concatenate an element on to fully qualified classname.
     *
     * @param path the path under construction
     * @param element the element of the name to add
     */
    protected void appendPath(StringBuffer path, String element)
    {
        if (StringUtil.isBlank(element))
            return;
        if (path.length() > 0)
            path.append(".");
        path.append(element);
    }

    protected class Config extends HolderConfig implements ServletConfig
    {

        @Override
        public String getServletName()
        {
            return getName();
        }
    }

    public class Registration extends HolderRegistration implements ServletRegistration.Dynamic
    {
        protected MultipartConfigElement _multipartConfig;

        @Override
        public Set<String> addMapping(String... urlPatterns)
        {
            illegalStateIfContextStarted();
            Set<String> clash = null;
            for (String pattern : urlPatterns)
            {
                ServletMapping mapping = getServletHandler().getServletMapping(pattern);
                if (mapping != null)
                {
                    //if the servlet mapping was from a default descriptor, then allow it to be overridden
                    if (!mapping.isDefault())
                    {
                        if (clash == null)
                            clash = new HashSet<>();
                        clash.add(pattern);
                    }
                }
            }

            //if there were any clashes amongst the urls, return them
            if (clash != null)
                return clash;

            //otherwise apply all of them
            ServletMapping mapping = new ServletMapping(Source.JAVAX_API);
            mapping.setServletName(ServletHolder.this.getName());
            mapping.setPathSpecs(urlPatterns);
            getServletHandler().addServletMapping(mapping);

            return Collections.emptySet();
        }

        @Override
        public Collection<String> getMappings()
        {
            ServletMapping[] mappings = getServletHandler().getServletMappings();
            List<String> patterns = new ArrayList<>();
            if (mappings != null)
            {
                for (ServletMapping mapping : mappings)
                {
                    if (!mapping.getServletName().equals(getName()))
                        continue;
                    String[] specs = mapping.getPathSpecs();
                    if (specs != null && specs.length > 0)
                        patterns.addAll(Arrays.asList(specs));
                }
            }
            return patterns;
        }

        @Override
        public String getRunAsRole()
        {
            return _runAsRole;
        }

        @Override
        public void setLoadOnStartup(int loadOnStartup)
        {
            illegalStateIfContextStarted();
            ServletHolder.this.setInitOrder(loadOnStartup);
        }

        public int getInitOrder()
        {
            return ServletHolder.this.getInitOrder();
        }

        @Override
        public void setMultipartConfig(MultipartConfigElement element)
        {
            _multipartConfig = element;
        }

        public MultipartConfigElement getMultipartConfig()
        {
            return _multipartConfig;
        }

        @Override
        public void setRunAsRole(String role)
        {
            _runAsRole = role;
        }

        @Override
        public Set<String> setServletSecurity(ServletSecurityElement securityElement)
        {
            return getServletHandler().setServletSecurity(this, securityElement);
        }
    }

    public ServletRegistration.Dynamic getRegistration()
    {
        if (_registration == null)
            _registration = new Registration();
        return _registration;
    }

    private class SingleThreadedWrapper implements Servlet
    {
        Stack<Servlet> _stack = new Stack<>();

        @Override
        public void destroy()
        {
            synchronized (this)
            {
                while (_stack.size() > 0)
                {
                    try
                    {
                        (_stack.pop()).destroy();
                    }
                    catch (Exception e)
                    {
                        LOG.warn(e);
                    }
                }
            }
        }

        @Override
        public ServletConfig getServletConfig()
        {
            return _config;
        }

        @Override
        public String getServletInfo()
        {
            return null;
        }

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            synchronized (this)
            {
                if (_stack.size() == 0)
                {
                    try
                    {
                        Servlet s = newInstance();
                        s.init(config);
                        _stack.push(s);
                    }
                    catch (ServletException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        throw new ServletException(e);
                    }
                }
            }
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            Servlet s;
            synchronized (this)
            {
                if (_stack.size() > 0)
                    s = _stack.pop();
                else
                {
                    try
                    {
                        s = newInstance();
                        s.init(_config);
                    }
                    catch (ServletException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        throw new ServletException(e);
                    }
                }
            }

            try
            {
                s.service(req, res);
            }
            finally
            {
                synchronized (this)
                {
                    _stack.push(s);
                }
            }
        }
    }

    /**
     * @return the newly created Servlet instance
     * @throws ServletException if unable to create a new instance
     * @throws IllegalAccessException if not allowed to create a new instance
     * @throws InstantiationException if creating new instance resulted in error
     * @throws NoSuchMethodException if creating new instance resulted in error
     * @throws InvocationTargetException If creating new instance throws an exception
     */
    protected Servlet newInstance() throws ServletException, IllegalAccessException, InstantiationException,
        NoSuchMethodException, InvocationTargetException
    {
        try
        {
            ServletContext ctx = getServletHandler().getServletContext();
            if (ctx != null)
                return ctx.createServlet(getHeldClass());
            return getHeldClass().getDeclaredConstructor().newInstance();

        }
        catch (ServletException ex)
        {
            Throwable cause = ex.getRootCause();
            if (cause instanceof InstantiationException)
                throw (InstantiationException)cause;
            if (cause instanceof IllegalAccessException)
                throw (IllegalAccessException)cause;
            if (cause instanceof NoSuchMethodException)
                throw (NoSuchMethodException)cause;
            if (cause instanceof InvocationTargetException)
                throw (InvocationTargetException)cause;
            throw ex;
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        if (getInitParameters().isEmpty())
            Dumpable.dumpObjects(out, indent, this,
                _servlet == null ? getHeldClass() : _servlet);
        else
            Dumpable.dumpObjects(out, indent, this,
                _servlet == null ? getHeldClass() : _servlet,
                new DumpableCollection("initParams", getInitParameters().entrySet()));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x==%s,jsp=%s,order=%d,inst=%b,async=%b", getName(), hashCode(), getClassName(), _forcedPath, _initOrder, _servlet != null, isAsyncSupported());
    }

    private class UnavailableServlet extends GenericServlet
    {
        final UnavailableException _unavailableException;
        final Servlet _servlet;
        final long _available;

        public UnavailableServlet(UnavailableException unavailableException, Servlet servlet)
        {
            _unavailableException = unavailableException;

            if (unavailableException.isPermanent())
            {
                _servlet = null;
                _available = -1;
                if (servlet != null)
                {
                    try
                    {
                        destroyInstance(servlet);
                    }
                    catch (Throwable th)
                    {
                        if (th != unavailableException)
                            unavailableException.addSuppressed(th);
                    }
                }
            }
            else
            {
                _servlet = servlet;
                _available = System.nanoTime() + TimeUnit.SECONDS.toNanos(unavailableException.getUnavailableSeconds());
            }
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            if (_available == -1)
                ((HttpServletResponse)res).sendError(HttpServletResponse.SC_NOT_FOUND);
            else if (System.nanoTime() < _available)
                ((HttpServletResponse)res).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            else
            {
                synchronized (ServletHolder.this)
                {
                    ServletHolder.this._servlet = this._servlet;
                    _servlet.service(req,res);
                }
            }
        }

        @Override
        public void destroy()
        {
            if (_servlet != null)
            {
                try
                {
                    destroyInstance(_servlet);
                }
                catch (Throwable th)
                {
                    LOG.warn(th);
                }
            }
        }

        public UnavailableException getUnavailableException()
        {
            return _unavailableException;
        }
    }

    private static class WrapperServlet implements Servlet
    {
        final Servlet _servlet;

        public WrapperServlet(Servlet servlet)
        {
            _servlet = servlet;
        }

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            _servlet.init(config);
        }

        @Override
        public ServletConfig getServletConfig()
        {
            return _servlet.getServletConfig();
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            _servlet.service(req, res);
        }

        @Override
        public String getServletInfo()
        {
            return _servlet.getServletInfo();
        }

        @Override
        public void destroy()
        {
            _servlet.destroy();
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s", this.getClass().getSimpleName(), _servlet.toString());
        }
    }

    private static class RunAsServlet extends WrapperServlet
    {
        final IdentityService _identityService;
        final RunAsToken _runAsToken;

        public RunAsServlet(Servlet servlet, IdentityService identityService, RunAsToken runAsToken)
        {
            super(servlet);
            _identityService = identityService;
            _runAsToken = runAsToken;
        }

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            Object oldRunAs = _identityService.setRunAs(_identityService.getSystemUserIdentity(), _runAsToken);
            try
            {
                _servlet.init(config);
            }
            finally
            {
                _identityService.unsetRunAs(oldRunAs);
            }
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            Object oldRunAs = _identityService.setRunAs(_identityService.getSystemUserIdentity(), _runAsToken);
            try
            {
                _servlet.service(req, res);
            }
            finally
            {
                _identityService.unsetRunAs(oldRunAs);
            }
        }

        @Override
        public void destroy()
        {
            Object oldRunAs = _identityService.setRunAs(_identityService.getSystemUserIdentity(), _runAsToken);
            try
            {
                _servlet.destroy();
            }
            finally
            {
                _identityService.unsetRunAs(oldRunAs);
            }
        }
    }

    private static class NotAsyncServlet extends WrapperServlet
    {
        public NotAsyncServlet(Servlet servlet)
        {
            super(servlet);
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            if (req.isAsyncSupported())
            {
                Request baseRequest = Request.getBaseRequest(req);
                try
                {
                    baseRequest.setAsyncSupported(false, this.toString());
                    _servlet.service(req, res);
                }
                finally
                {
                    baseRequest.setAsyncSupported(true, null);
                }
            }
            else
            {
                _servlet.service(req, res);
            }
        }
    }
}
