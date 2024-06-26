/*
   Copyright (c) 2023 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.d2.xds.balancer;

import com.linkedin.common.callback.Callback;
import com.linkedin.common.util.None;
import com.linkedin.d2.balancer.clusterfailout.FailoutConfigProviderFactory;
import com.linkedin.d2.balancer.properties.ClusterProperties;
import com.linkedin.d2.balancer.properties.ClusterPropertiesJsonSerializer;
import com.linkedin.d2.balancer.properties.ServiceProperties;
import com.linkedin.d2.balancer.properties.ServicePropertiesJsonSerializer;
import com.linkedin.d2.balancer.properties.UriProperties;
import com.linkedin.d2.balancer.properties.UriPropertiesJsonSerializer;
import com.linkedin.d2.balancer.simple.SimpleLoadBalancer;
import com.linkedin.d2.balancer.simple.SimpleLoadBalancerState;
import com.linkedin.d2.balancer.simple.SslSessionValidatorFactory;
import com.linkedin.d2.balancer.strategies.LoadBalancerStrategy;
import com.linkedin.d2.balancer.strategies.LoadBalancerStrategyFactory;
import com.linkedin.d2.balancer.subsetting.DeterministicSubsettingMetadataProvider;
import com.linkedin.d2.balancer.util.FileSystemDirectory;
import com.linkedin.d2.balancer.util.TogglingLoadBalancer;
import com.linkedin.d2.balancer.util.canary.CanaryDistributionProvider;
import com.linkedin.d2.balancer.util.partitions.PartitionAccessorRegistry;
import com.linkedin.d2.discovery.PropertySerializer;
import com.linkedin.d2.discovery.event.PropertyEventBus;
import com.linkedin.d2.discovery.event.PropertyEventBusImpl;
import com.linkedin.d2.discovery.stores.file.FileStore;
import com.linkedin.d2.discovery.stores.toggling.TogglingPublisher;
import com.linkedin.d2.jmx.D2ClientJmxManager;
import com.linkedin.d2.xds.XdsToClusterPropertiesPublisher;
import com.linkedin.d2.xds.XdsToD2PropertiesAdaptor;
import com.linkedin.d2.xds.XdsToServicePropertiesPublisher;
import com.linkedin.d2.xds.XdsToUriPropertiesPublisher;
import com.linkedin.r2.transport.common.TransportClientFactory;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A factory that creates a load balancer with xDS and file store toggles for service discovery
 */
public class XdsFsTogglingLoadBalancerFactory
{
  private static final Logger _log = LoggerFactory.getLogger(XdsFsTogglingLoadBalancerFactory.class);

  private final long _lbTimeout;
  private final TimeUnit _lbTimeoutUnit;
  private final String _fsIndisDirPath;
  private final String _d2ServicePath;
  private final D2ClientJmxManager _d2ClientJmxManager;
  private final Map<String, TransportClientFactory> _clientFactories;
  private final Map<String, LoadBalancerStrategyFactory<? extends LoadBalancerStrategy>> _loadBalancerStrategyFactories;

  private final SSLContext _sslContext;
  private final SSLParameters _sslParameters;
  private final boolean _isSSLEnabled;
  private final Map<String, Map<String, Object>> _clientServicesConfig;
  private final PartitionAccessorRegistry _partitionAccessorRegistry;
  private final SslSessionValidatorFactory _sslSessionValidatorFactory;
  private final DeterministicSubsettingMetadataProvider _deterministicSubsettingMetadataProvider;
  private final CanaryDistributionProvider _canaryDistributionProvider;
  private final FailoutConfigProviderFactory _failoutConfigProviderFactory;
  private final boolean _loadBalanceStreamException;

  @Deprecated
  public XdsFsTogglingLoadBalancerFactory(long timeout, TimeUnit timeoutUnit, String fsBasePath,
      Map<String, TransportClientFactory> clientFactories,
      Map<String, LoadBalancerStrategyFactory<? extends LoadBalancerStrategy>> loadBalancerStrategyFactories,
      String d2ServicePath, SSLContext sslContext, SSLParameters sslParameters, boolean isSSLEnabled,
      Map<String, Map<String, Object>> clientServicesConfig, PartitionAccessorRegistry partitionAccessorRegistry,
      SslSessionValidatorFactory sslSessionValidatorFactory, D2ClientJmxManager d2ClientJmxManager,
      DeterministicSubsettingMetadataProvider deterministicSubsettingMetadataProvider,
      FailoutConfigProviderFactory failoutConfigProviderFactory, CanaryDistributionProvider canaryDistributionProvider)
  {
    this(timeout, timeoutUnit, fsBasePath, clientFactories, loadBalancerStrategyFactories, d2ServicePath, sslContext,
        sslParameters, isSSLEnabled, clientServicesConfig, partitionAccessorRegistry, sslSessionValidatorFactory,
        d2ClientJmxManager, deterministicSubsettingMetadataProvider, failoutConfigProviderFactory,
        canaryDistributionProvider, false);
  }

  public XdsFsTogglingLoadBalancerFactory(long timeout, TimeUnit timeoutUnit, String fsBasePath,
      Map<String, TransportClientFactory> clientFactories,
      Map<String, LoadBalancerStrategyFactory<? extends LoadBalancerStrategy>> loadBalancerStrategyFactories,
      String d2ServicePath, SSLContext sslContext, SSLParameters sslParameters, boolean isSSLEnabled,
      Map<String, Map<String, Object>> clientServicesConfig, PartitionAccessorRegistry partitionAccessorRegistry,
      SslSessionValidatorFactory sslSessionValidatorFactory, D2ClientJmxManager d2ClientJmxManager,
      DeterministicSubsettingMetadataProvider deterministicSubsettingMetadataProvider,
      FailoutConfigProviderFactory failoutConfigProviderFactory, CanaryDistributionProvider canaryDistributionProvider,
      boolean loadBalanceStreamException)
  {
    _lbTimeout = timeout;
    _lbTimeoutUnit = timeoutUnit;
    _fsIndisDirPath = fsBasePath;
    _clientFactories = clientFactories;
    _loadBalancerStrategyFactories = loadBalancerStrategyFactories;
    _d2ServicePath = d2ServicePath;
    _sslContext = sslContext;
    _sslParameters = sslParameters;
    _isSSLEnabled = isSSLEnabled;
    _clientServicesConfig = clientServicesConfig;
    _partitionAccessorRegistry = partitionAccessorRegistry;
    _sslSessionValidatorFactory = sslSessionValidatorFactory;
    _d2ClientJmxManager = d2ClientJmxManager;
    _deterministicSubsettingMetadataProvider = deterministicSubsettingMetadataProvider;
    _failoutConfigProviderFactory = failoutConfigProviderFactory;
    _canaryDistributionProvider = canaryDistributionProvider;
    _loadBalanceStreamException = loadBalanceStreamException;
  }

  public TogglingLoadBalancer create(ScheduledExecutorService executorService, XdsToD2PropertiesAdaptor xdsAdaptor)
  {
    PropertyEventBus<ClusterProperties> clusterBus = new PropertyEventBusImpl<>(executorService);
    PropertyEventBus<ServiceProperties> serviceBus = new PropertyEventBusImpl<>(executorService);
    PropertyEventBus<UriProperties> uriBus = new PropertyEventBusImpl<>(executorService);

    FileStore<ClusterProperties> fsClusterStore =
        createFileStore(FileSystemDirectory.getClusterDirectory(_fsIndisDirPath), new ClusterPropertiesJsonSerializer());
    _d2ClientJmxManager.setFsClusterStore(fsClusterStore);

    FileStore<ServiceProperties> fsServiceStore =
        createFileStore(FileSystemDirectory.getServiceDirectory(_fsIndisDirPath, _d2ServicePath),
            new ServicePropertiesJsonSerializer());
    _d2ClientJmxManager.setFsServiceStore(fsServiceStore);

    FileStore<UriProperties> fsUriStore =
        createFileStore(_fsIndisDirPath + File.separator + "uris", new UriPropertiesJsonSerializer());
    _d2ClientJmxManager.setFsUriStore(fsUriStore);

    // This ensures the filesystem store receives the events from the event bus so that
    // it can keep a local backup.
    clusterBus.register(fsClusterStore);
    serviceBus.register(fsServiceStore);
    uriBus.register(fsUriStore);

    XdsToClusterPropertiesPublisher clusterPropertiesPublisher = new XdsToClusterPropertiesPublisher(xdsAdaptor);
    XdsToServicePropertiesPublisher servicePropertiesPublisher = new XdsToServicePropertiesPublisher(xdsAdaptor);
    XdsToUriPropertiesPublisher uriPropertiesPublisher = new XdsToUriPropertiesPublisher(xdsAdaptor);

    TogglingPublisher<ClusterProperties> clusterToggle =
        new TogglingPublisher<>(clusterPropertiesPublisher, fsClusterStore, clusterBus);
    TogglingPublisher<ServiceProperties> serviceToggle =
        new TogglingPublisher<>(servicePropertiesPublisher, fsServiceStore, serviceBus);
    TogglingPublisher<UriProperties> uriToggle = new TogglingPublisher<>(uriPropertiesPublisher, fsUriStore, uriBus);

    SimpleLoadBalancerState state =
        new SimpleLoadBalancerState(executorService, uriBus, clusterBus, serviceBus, _clientFactories,
            _loadBalancerStrategyFactories, _sslContext, _sslParameters, _isSSLEnabled, _partitionAccessorRegistry,
            _sslSessionValidatorFactory, _deterministicSubsettingMetadataProvider, _canaryDistributionProvider,
            _loadBalanceStreamException);
    _d2ClientJmxManager.setSimpleLoadBalancerState(state);

    SimpleLoadBalancer balancer =
        new SimpleLoadBalancer(state, _lbTimeout, _lbTimeoutUnit, executorService, _failoutConfigProviderFactory);
    _d2ClientJmxManager.setSimpleLoadBalancer(balancer);

    TogglingLoadBalancer togLB = new TogglingLoadBalancer(balancer, clusterToggle, serviceToggle, uriToggle);
    togLB.start(new Callback<None>()
    {

      @Override
      public void onError(Throwable e)
      {
        _log.warn("Failed to run start on the TogglingLoadBalancer, may not have registered "
            + "SimpleLoadBalancer and State with JMX.");
      }

      @Override
      public void onSuccess(None result)
      {
        _log.info("Registered SimpleLoadBalancer and State with JMX.");
      }
    });
    return togLB;
  }

  private <T> FileStore<T> createFileStore(String path, PropertySerializer<T> serializer)
  {
    return new FileStore<>(path, FileSystemDirectory.FILE_STORE_EXTENSION, serializer);
  }
}
