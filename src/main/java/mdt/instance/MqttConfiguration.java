package mdt.instance;

import java.time.Duration;

import javax.annotation.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.UnitUtils;
import utils.func.FOption;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "mqtt")
@NoArgsConstructor
@Getter @Setter
@Accessors(prefix = "m_")
public class MqttConfiguration {
	private @Nullable String m_clientId = null;
	private String m_endpoint;
	private int m_qos = 0;
	private Duration m_reconnectInterval = Duration.ofSeconds(5);
	
	public void setReconnectRetryIntervalForJackson(String durStr) {
		m_reconnectInterval = FOption.map(durStr, UnitUtils::parseDuration);
	}
}
