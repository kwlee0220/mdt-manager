package mdt.instance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.NoArgsConstructor;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "mqtt")
@NoArgsConstructor
public class MqttConfiguration {
	private String m_endpoint;
	private int m_qos = 0;
	
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
}
