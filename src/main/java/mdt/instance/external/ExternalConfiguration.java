package mdt.instance.external;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "external")
@Getter @Setter
@NoArgsConstructor
@Accessors(prefix = "m_")
public class ExternalConfiguration {
	/**
	 * Health check interval for the external instance.
	 */
	@JsonProperty("checkInterval") private Duration m_checkInterval;
	/**
	 * Connection timeout for the external instance.
	 */
	@JsonProperty("connectionTimeout") private Duration m_connectionTimeout;
	
	/**
	 * Health 검사 주기를 반환한다.
	 * 
	 * @return	주기
	 */
	public Duration getCheckInterval() {
		return m_checkInterval;
	}
	
	/**
	 * Health 검사 주기를 설정한다.
	 * 
	 * @param interval	주기
	 */
	public void setCheckInterval(Duration interval) {
		m_checkInterval = interval;
	}
	
	/**
	 * 연결 제한시간을 반환한다.
	 * <p>
	 * {@link ExternalInstance}이 연결 제한시간 동안
	 * {@link ExternalInstanceManager#register(String, String)} 호출이
	 * 없는 경우 해당 ExternalInstance가 종료된 것으로 간주한다.
	 * 
	 * @return 제한시간
	 */
	public Duration getConnectionTimeout() {
		return m_connectionTimeout;
	}
	
	/**
	 * 연결 제한시간을 설정한다.
	 * <p>
	 * {@link ExternalInstance}이 연결 제한시간 동안
	 * {@link ExternalInstanceManager#register(String, String)} 호출이
	 * 없는 경우 해당 ExternalInstance가 종료된 것으로 간주한다.
	 * 
	 * @param to 제한시간
	 */
	public void setConnectionTimeout(Duration to) {
		m_connectionTimeout = to;
	}
}
