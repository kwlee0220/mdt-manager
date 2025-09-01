package mdt.instance.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "harbor")
@Getter @Setter
@NoArgsConstructor
@Accessors(prefix = "m_")
public class HarborConfiguration {
	private String m_host;
	private String m_endpoint;
	private String m_project;
	private String m_user;
	private String m_password;
}
