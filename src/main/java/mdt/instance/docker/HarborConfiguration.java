package mdt.instance.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.NoArgsConstructor;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "harbor")
@NoArgsConstructor
public class HarborConfiguration {
	private String m_host;
	private String m_endpoint;
	private String m_project;
	private String m_user;
	private String m_password;
	
	public String getHost() {
		return m_host;
	}
	
	public void setHost(String host) {
		m_host = host;
	}
	
	public String getEndpoint() {
		return m_endpoint;
	}
	
	public void setEndpoint(String endpoint) {
		m_endpoint = endpoint;
	}
	
	public String getProject() {
		return m_project;
	}
	
	public void setProject(String project) {
		m_project = project;
	}
	
	public String getUser() {
		return m_user;
	}
	
	public void setUser(String user) {
		m_user = user;
	}
	
	public String getPassword() {
		return m_password;
	}
	
	public void setPassword(String password) {
		m_password = password;
	}
}
