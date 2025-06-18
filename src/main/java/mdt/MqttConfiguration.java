package mdt;

import java.time.Duration;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.UnitUtils;
import utils.func.FOption;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix = "m_")
@JsonInclude(Include.NON_NULL)
public class MqttConfiguration {
	@Nullable private String m_clientId = null;
	private String m_endpoint;
	private int m_qos = 0;
	private Duration m_reconnectInterval = Duration.ofSeconds(5);
	
	@JsonProperty("reconnectInterval")
	public void setReconnectRetryIntervalForJackson(String durStr) {
		m_reconnectInterval = FOption.map(durStr, UnitUtils::parseDuration);
	}
}
