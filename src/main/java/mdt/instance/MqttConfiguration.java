package mdt.instance;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.NoArgsConstructor;

import utils.UnitUtils;
import utils.func.FOption;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "mqtt")
@NoArgsConstructor
public class MqttConfiguration {
	private String m_endpoint;
	private int m_qos = 0;
	private Duration m_reconnectInterval = Duration.ofSeconds(5);
	
	public String getEndpoint() {
		return m_endpoint;
	}
	
	public void setEndpoint(String endpoint) {
		m_endpoint = endpoint;
	}
	
	public int getQos() {
		return m_qos;
	}
	
	public void setQos(int qos) {
		m_qos = qos;
	}
	
	public Duration getReconnectRetryInterval() {
		return m_reconnectInterval;
	}
	
	public void setReconnectRetryInterval(Duration dur) {
		m_reconnectInterval = dur;
	}
	
	public void setReconnectRetryIntervalForJackson(String durStr) {
		m_reconnectInterval = FOption.map(durStr, UnitUtils::parseDuration);
	}
}
