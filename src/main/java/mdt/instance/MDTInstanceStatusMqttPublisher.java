package mdt.instance;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.Subscribe;

import mdt.client.support.AutoReconnectingMqttClient;
import mdt.model.instance.InstanceStatusChangeEvent;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTInstanceStatusMqttPublisher extends AutoReconnectingMqttClient {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTInstanceStatusMqttPublisher.class);
	private static final String TOPIC_STATUS_CHANGES_FORMAT = "/mdt/manager/instances/%s";
	private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
	
	private final int m_qos;
	
	public MDTInstanceStatusMqttPublisher(Builder builder) {
		super(builder.m_mqttServerUri, builder.m_clientId, builder.m_reconnectInterval);
		Preconditions.checkArgument(builder.m_qos >= 0 && builder.m_qos <= 2);
		
		m_qos = builder.m_qos;
		setLogger(s_logger);
	}
	
	@Subscribe
	public void publishStatusChangeEvent(InstanceStatusChangeEvent ev) {
		MqttClient client = pollMqttClient();
		if ( client != null ) {
			try {
				String topic = String.format(TOPIC_STATUS_CHANGES_FORMAT, ev.getInstanceId());
				
				String jsonStr = MAPPER.writeValueAsString(ev);
				MqttMessage message = new MqttMessage(jsonStr.getBytes(StandardCharsets.UTF_8));
				message.setQos(m_qos);
				client.publish(topic, message);
			}
			catch ( Exception e ) {
				getLogger().warn("Failed to publish event, cause=" + e);
			}
		}
	}
	@Override public void messageArrived(String topic, MqttMessage msg) throws Exception { }
	@Override public void deliveryComplete(IMqttDeliveryToken token) { }
	
	@Override protected void mqttBrokerConnected(MqttClient client) throws Exception { }
	@Override protected void mqttBrokerDisconnected() { }
	
	public static Builder builder() {
		return new Builder();
	}
	public static class Builder {
		private String m_mqttServerUri;
		private String m_clientId;
		private int m_qos = 0;
		private Duration m_reconnectInterval = Duration.ofSeconds(10);
		
		public MDTInstanceStatusMqttPublisher build() {
			return new MDTInstanceStatusMqttPublisher(this);
		}
		
		public Builder mqttServerUri(String uri) {
			m_mqttServerUri = uri;
			return this;
		}
		
		public Builder clientId(String id) {
			m_clientId = id;
			return this;
		}
		
		public Builder qos(int qos) {
			m_qos = qos;
			return this;
		}
		
		public Builder reconnectInterval(Duration interval) {
			m_reconnectInterval = interval;
			return this;
		}
	}
}
