/*
   Copyright (c) 2017 LinkedIn Corp.

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

package com.linkedin.d2.balancer.simple;

import com.linkedin.d2.balancer.LoadBalancerState;
import com.linkedin.d2.balancer.LoadBalancerStateItem;
import com.linkedin.d2.balancer.clients.TrackerClient;
import com.linkedin.d2.balancer.properties.PartitionData;
import com.linkedin.d2.balancer.properties.UriProperties;
import com.linkedin.d2.discovery.event.PropertyEventBus;
import com.linkedin.util.RateLimitedLogger;
import com.linkedin.util.clock.SystemClock;
import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linkedin.d2.discovery.util.LogUtil.*;


/**
 * Subscriber to the uri data to update the SimpleLoadBalancerState
 */
class UriLoadBalancerSubscriber extends AbstractLoadBalancerSubscriber<UriProperties>
{
  private static final Logger _log = LoggerFactory.getLogger(UriLoadBalancerSubscriber.class);
  private static final RateLimitedLogger RATE_LIMITED_LOGGER =
      new RateLimitedLogger(_log, TimeUnit.MINUTES.toMillis(10), SystemClock.instance());

  private SimpleLoadBalancerState _simpleLoadBalancerState;

  public UriLoadBalancerSubscriber(PropertyEventBus<UriProperties> uPropertyEventBus,
                                   SimpleLoadBalancerState simpleLoadBalancerState)
  {
    super(LoadBalancerState.LoadBalancerStateListenerCallback.CLUSTER, uPropertyEventBus);

    _simpleLoadBalancerState = simpleLoadBalancerState;
  }

  @Override
  protected void handlePut(final String cluster, final UriProperties uriProperties)
  {
    // add tracker clients for uris that we aren't already tracking
    if (uriProperties != null)
    {
      String clusterName = uriProperties.getClusterName();

      Optional<UriProperties> currentUriProperties = Optional.ofNullable(
          _simpleLoadBalancerState.getUriProperties(clusterName)).map(LoadBalancerStateItem::getProperty);
      if (currentUriProperties.isPresent() && currentUriProperties.get().equals(uriProperties))
      {
        _log.debug("For cluster: {}, received duplicate uri properties: {}", clusterName, uriProperties);
        return;
      }

      _log.debug("For cluster: {}, received new uri properties: {}\nOld properties: {}", clusterName, uriProperties,
          currentUriProperties);

      Set<String> serviceNames = _simpleLoadBalancerState.getServicesPerCluster().get(clusterName);
      //updates all the services that these uris provide
      if (serviceNames != null)
      {
        for (String serviceName : serviceNames)
        {
          Map<URI, TrackerClient> trackerClients = _simpleLoadBalancerState.getTrackerClients().get(serviceName);
          if (trackerClients == null)
          {
            trackerClients = new ConcurrentHashMap<>();
            _simpleLoadBalancerState.getTrackerClients().put(serviceName, trackerClients);
          }

          for (URI uri : uriProperties.Uris())
          {
            Map<Integer, PartitionData> partitionDataMap = uriProperties.getPartitionDataMap(uri);
            TrackerClient client = trackerClients.get(uri);

            Optional<Map<String, Object>> newUriSpecificProperties = Optional.ofNullable(uriProperties.getUriSpecificProperties())
              .map(uriSpecificProperties -> uriSpecificProperties.get(uri));

            Optional<Map<String, Object>> oldUriSpecificProperties = Optional.ofNullable(_simpleLoadBalancerState.getUriProperties(clusterName))
              .map(LoadBalancerStateItem::getProperty)
              .map(UriProperties::getUriSpecificProperties)
              .map(uriSpecificProperties -> uriSpecificProperties.get(uri));

            if (client == null || !client.getPartitionDataMap().equals(partitionDataMap) || !newUriSpecificProperties.equals(oldUriSpecificProperties))
            {
              client = _simpleLoadBalancerState.buildTrackerClient(uri, uriProperties, serviceName);

              if (client != null)
              {
                debug(_log, "adding new tracker client from updated uri properties: ", client);

                // notify listeners of the added client
                for (SimpleLoadBalancerState.SimpleLoadBalancerStateListener listener : _simpleLoadBalancerState.getListeners())
                {
                  listener.onClientAdded(serviceName, client);
                }

                trackerClients.put(uri, client);
              }
            }
          }
        }
      }

    }

    // replace the URI properties
    LoadBalancerStateItem<UriProperties> existingLBItem = _simpleLoadBalancerState.getUriProperties().put(cluster,
      new LoadBalancerStateItem<>(uriProperties,
        _simpleLoadBalancerState.getVersionAccess().incrementAndGet(),
        System.currentTimeMillis()));
    if (existingLBItem == null) {
      info(_log, "getting new UriProperties for cluster ", cluster);
    }

    // now remove URIs that we're tracking, but have been removed from the new uri properties
    if (uriProperties != null)
    {
      Set<String> serviceNames = _simpleLoadBalancerState.getServicesPerCluster().get(uriProperties.getClusterName());
      if (serviceNames != null)
      {
        for (String serviceName : serviceNames)
        {
          Map<URI, TrackerClient> trackerClients = _simpleLoadBalancerState.getTrackerClients().get(serviceName);
          if (trackerClients != null)
          {
            for (Iterator<URI> it = trackerClients.keySet().iterator(); it.hasNext(); )
            {
              URI uri = it.next();

              if (!uriProperties.Uris().contains(uri))
              {
                TrackerClient client = trackerClients.remove(uri);

                debug(_log, "removing dead tracker client: ", client);

                for (SimpleLoadBalancerState.SimpleLoadBalancerStateListener listener : _simpleLoadBalancerState.getListeners())
                {
                  listener.onClientRemoved(serviceName, client);
                }
              }
            }
          }
        }
      }
    }
    else
    {
      // uri properties was null, we'll just log the event and continues.
      // The reasoning is we might receive a null event when there's a problem writing/reading
      // cache file, or we just started listening to a cluster without any uris yet.
      RATE_LIMITED_LOGGER.warn("Received a null uri properties for cluster: {}", cluster);
    }
  }

  @Override
  protected void handleRemove(final String cluster)
  {
    _simpleLoadBalancerState.getUriProperties().remove(cluster);
    warn(RATE_LIMITED_LOGGER, "received a uri properties event remove() for cluster: ", cluster);
    _simpleLoadBalancerState.removeTrackerClients(cluster);
  }
}
