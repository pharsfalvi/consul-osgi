package com.dexels.sharedconfigstore.consul.impl;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dexels.sharedconfigstore.consul.ConsulResourceEvent;
import com.dexels.sharedconfigstore.consul.ConsulResourceListener;
import com.dexels.sharedconfigstore.consul.LongPollingScheduler;

@Component(name = "dexels.consul.listener", immediate = true,configurationPolicy=ConfigurationPolicy.REQUIRE)
public class LongPollingHttpListenerImpl implements LongPollingScheduler {

//    private Thread checkThread;
	private String consulServer = null;
	public static final String KVPREFIX = "/v1/kv/";
	public static final String CATALOG = "/v1/catalog/services";
	private String blockIntervalInSeconds = "20";
//	private Integer lastIndex = null;
//	private String lastValue = null;
	private final Map<String,Integer> lastIndexes = new HashMap<>();
	private final Map<String,JsonNode> lastValues = new HashMap<>();
	private final Map<String,LongPollingCallback> currentCallbacks = new HashMap<>();
	
	private final Set<ConsulResourceListener> resourceListeners = new HashSet<>();
	private final static Logger logger = LoggerFactory.getLogger(LongPollingHttpListenerImpl.class);
	private CloseableHttpAsyncClient client;
	private CloseableHttpClient syncClient;
	private ObjectMapper mapper;
	private String servicePrefix;

    
	@Activate
    public void activate(Map<String, Object> settings) {
		mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);;
		this.servicePrefix = (String)settings.get("servicePrefix");
		int timeout = Integer.parseInt(blockIntervalInSeconds) + 10;
		consulServer = (String) settings.get("consulServer");
        RequestConfig config = RequestConfig.custom().setConnectTimeout(timeout * 1000)
                .setConnectionRequestTimeout(timeout * 1000).setSocketTimeout(timeout * 1000).build();
        client = HttpAsyncClients.custom().setDefaultRequestConfig(config)
                .build();
        syncClient = HttpClients.custom().setDefaultRequestConfig(config).build();
        
        client.start();
    }

	public void monitorURL(final String path) {
		Integer blockIndex = lastIndexes.get(path);
		String baseURL = consulServer+path+"?wait="+blockIntervalInSeconds+"s";
		final HttpGet get = (blockIndex!=null)?new HttpGet(baseURL+"&index="+blockIndex):new HttpGet(baseURL);
        try {
//        	System.err.println("sched to: "+get.getURI());
        	LongPollingCallback callback = new LongPollingCallback(get, path, this);
        	currentCallbacks.put(path, callback);
        	client.execute(get,callback);
        } catch (Exception e) {
            logger.error("Got Exception on performing GET: ", e);
        }
        
	}

	@Override
	public JsonNode queryPath(String path) throws IOException {
		final HttpGet get = new HttpGet(consulServer+path);
		CloseableHttpResponse response = syncClient.execute(get);
		if(response.getStatusLine().getStatusCode()>=300) {
			return null;
		}
		JsonNode reply = mapper.readTree(response.getEntity().getContent());
		response.close();
		return reply;
	}
	
	@Override
	public void callFailed(String key, int responseCode) {
        logger.warn("Failed calling: "+key+" with code: "+responseCode+" sleeping to be sure");
        try {
			Thread.sleep(20000);
		} catch (InterruptedException e) {
		}
        monitorURL(key);
	}

	@Override
	public void valueChanged(String key, JsonNode value, Integer index) {
		Integer prev = lastIndexes.get(key);
		JsonNode old = lastValues.get(key);
		
		if(prev!=null && prev.equals(index)) {
			logger.info("No real change");
		} else {
			lastIndexes.put(key, index);
	        lastValues.put(key,value);
	        notifyListeners(key,old,value);
		}
//		System.err.println("Re-schedule");
        monitorURL(key);
	}

	private void notifyListeners(String key, JsonNode oldValue, JsonNode newValue) {
		try {
			ConsulResourceEvent cre = new ConsulResourceEvent(key, oldValue, newValue,servicePrefix);
			for (ConsulResourceListener c : resourceListeners) {
				c.resourceChanged(cre);
			}
		} catch (Exception e) {
			logger.error("Error delivering changes: ", e);
		}
	}

	@Deactivate
	public void deactivate() {
		// cancel all running requests
		// close sync/async connector
	}

	@Override
	public void addConsulResourceListener(ConsulResourceListener listener) {
		resourceListeners.add(listener);
	}
	
	@Override
	public void removeConsulResourceListener(ConsulResourceListener listener) {
		resourceListeners.remove(listener);
		
	}

}
