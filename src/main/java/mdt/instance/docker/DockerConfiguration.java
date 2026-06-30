package mdt.instance.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.NoArgsConstructor;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "docker")
@NoArgsConstructor
public class DockerConfiguration {
	private String m_dockerEndpoint;
	private String m_imageName;
	
	public String getDockerEndpoint() {
		return m_dockerEndpoint;
	}
	
	public void setDockerEndpoint(String dockerEndpoint) {
		m_dockerEndpoint = dockerEndpoint;
	}
	
	public String getImageName() {
		return m_imageName;
	}
	
	public void setImageName(String imageName) {
		m_imageName = imageName;
	}
}
