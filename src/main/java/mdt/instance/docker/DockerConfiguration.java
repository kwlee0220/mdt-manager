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
@ConfigurationProperties(prefix = "docker")
@Getter @Setter
@NoArgsConstructor
@Accessors(prefix = "m_")
public class DockerConfiguration {
	private String m_dockerEndpoint;
	private String m_imageName;
}
