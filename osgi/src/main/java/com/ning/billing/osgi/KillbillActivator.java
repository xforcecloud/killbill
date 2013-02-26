/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.osgi;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Observable;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.Servlet;
import javax.sql.DataSource;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.billing.osgi.api.OSGIKillbill;
import com.ning.billing.osgi.api.OSGIPluginProperties;
import com.ning.billing.osgi.api.OSGIServiceDescriptor;
import com.ning.billing.osgi.api.OSGIServiceRegistration;
import com.ning.billing.osgi.glue.DefaultOSGIModule;
import com.ning.billing.payment.plugin.api.PaymentPluginApi;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillRegistrar;

import com.google.common.collect.ImmutableList;

public class KillbillActivator implements BundleActivator, ServiceListener {

    // TODO : Is that ok for system bundle to use Killbill Logger or do we need to LoggerService like we do for any other bundle
    private final static Logger logger = LoggerFactory.getLogger(KillbillActivator.class);

    private final OSGIKillbill osgiKillbill;
    private final HttpService defaultHttpService;
    private final DataSource dataSource;
    private final KillbillEventObservable observable;
    private final OSGIKillbillRegistrar registrar;


    private final List<OSGIServiceRegistration> allRegistrationHandlers;


    private BundleContext context = null;

    @Inject
    public KillbillActivator(@Named(DefaultOSGIModule.OSGI_NAMED) final DataSource dataSource,
                             final OSGIKillbill osgiKillbill,
                             final HttpService defaultHttpService,
                             final KillbillEventObservable observable,
                             final OSGIServiceRegistration<Servlet> servletRouter,
                             final OSGIServiceRegistration<PaymentPluginApi> paymentProviderPluginRegistry) {
        this.osgiKillbill = osgiKillbill;
        this.defaultHttpService = defaultHttpService;
        this.dataSource = dataSource;
        this.observable = observable;
        this.registrar = new OSGIKillbillRegistrar();
        this.allRegistrationHandlers = ImmutableList.<OSGIServiceRegistration>of(servletRouter, paymentProviderPluginRegistry);

    }

    @Override
    public void start(final BundleContext context) throws Exception {

        this.context = context;
        final Dictionary props = new Hashtable();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, "killbill");

        observable.register();

        registrar.registerService(context, OSGIKillbill.class, osgiKillbill, props);
        registrar.registerService(context, HttpService.class, defaultHttpService, props);
        registrar.registerService(context, Observable.class, observable, props);
        registrar.registerService(context, DataSource.class, dataSource, props);

        context.addServiceListener(this);
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        this.context = null;
        context.removeServiceListener(this);
        observable.unregister();
        registrar.unregisterAll();
    }

    @Override
    public void serviceChanged(final ServiceEvent event) {
        if (context == null || (event.getType() != ServiceEvent.REGISTERED && event.getType() != ServiceEvent.UNREGISTERING)) {
            // We are not initialized or uninterested
            return;
        }

        final ServiceReference serviceReference = event.getServiceReference();
        boolean processedServiceChange = false;
        for (OSGIServiceRegistration cur : allRegistrationHandlers) {
            if (listenForServiceType(serviceReference, event.getType(), cur.getServiceType(), cur)) {
                processedServiceChange = true;
                break;
            }
        }
        if (!processedServiceChange) {
            logger.warn("Did not process ServiceEvent for {} ", serviceReference.getBundle().getSymbolicName());
        }
    }

    private <T> boolean listenForServiceType(final ServiceReference serviceReference, final int eventType, final Class<T> claz, final OSGIServiceRegistration<T> registration) {
        // Make sure we can retrieve the plugin name
        final String serviceName = (String) serviceReference.getProperty(OSGIPluginProperties.PLUGIN_NAME_PROP);
        if (serviceName == null) {
            logger.warn("Ignoring registered OSGI service {} with no {} property", claz.getName(), OSGIPluginProperties.PLUGIN_NAME_PROP);
            return true;
        }

        final T theService = (T) context.getService(serviceReference);
        // Is that for us? We look for a subclass here for greater flexibility (e.g. HttpServlet for a Servlet service)
        if (theService == null || !claz.isAssignableFrom(theService.getClass())) {
            return false;
        }

        final String serviceInfo = (String) serviceReference.getProperty(OSGIPluginProperties.PLUGIN_SERVICE_INFO);
        final OSGIServiceDescriptor desc =  new DefaultOSGIServiceDescriptor(serviceReference.getBundle().getSymbolicName(), serviceName, serviceInfo, claz.getName());
        switch (eventType) {
            case ServiceEvent.REGISTERED:
                registration.registerService(desc, theService);
                break;
            case ServiceEvent.UNREGISTERING:
                registration.unregisterService(desc.getServiceName());
                break;
            default:
                break;
        }
        return true;
    }
}
