package mdt.instance.docker;

import com.fasterxml.jackson.annotation.JsonProperty;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerConfiguration {
	@JsonProperty("dockerEndpoint")  private String m_dockerEndpoint;
	@JsonProperty("imageName") private String m_imageName;
	
	public String getDockerEndpoint() {
		return m_dockerEndpoint;
	}
	
	public void setDockerEndpoint(String endpoint) {
		m_dockerEndpoint = endpoint;
	}
	
	public String getImageName() {
		return m_imageName;
	}
	
	public void setImageName(String name) {
		m_imageName = name;
	}
}
