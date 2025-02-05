package mdt.instance.external;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ExternalConfiguration {
	@JsonProperty("checkInterval") private Duration m_checkInterval;
	@JsonProperty("connectionTimeout") private Duration m_connectionTimeout;
	
	public Duration getCheckInterval() {
		return m_checkInterval;
	}
	
	public void setCheckInterval(Duration interval) {
		m_checkInterval = interval;
	}
	
	public Duration getConnectionTimeout() {
		return m_connectionTimeout;
	}
	
	public void setConnectionTimeout(Duration to) {
		m_connectionTimeout = to;
	}
}
