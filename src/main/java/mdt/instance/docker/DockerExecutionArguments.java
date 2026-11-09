package mdt.instance.docker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DockerExecutionArguments {
	@JsonProperty("imageRepoName") private final String m_imageRepoName;
	/** 컨테이너의 443 포트를 연결할 호스트 포트. {@code null}이거나 0 이하면 docker가 빈 포트를 고른다. */
	@JsonProperty("port") private final Integer m_port;
	
	@JsonCreator
	public DockerExecutionArguments(@JsonProperty("imageRepoName") String imageRepoName,
									@JsonProperty("port") Integer port) {
		this.m_imageRepoName = imageRepoName;
		this.m_port = port;
	}
	
	public String getImageRepoName() {
		return m_imageRepoName;
	}
	
	/**
	 * 호스트 포트를 반환한다.
	 *
	 * @return	지정된 호스트 포트, 지정되지 않았으면 {@code null}(임의 포트).
	 */
	public Integer getPort() {
		return ( m_port != null && m_port > 0 ) ? m_port : null;
	}
	
	@Override
	public String toString() {
		return String.format("dockerImage=%s, port=%s", m_imageRepoName, m_port);
	}
	
	@Override
	public boolean equals(Object obj) {
		if ( this == obj ) {
			return true;
		}
		else if ( obj == null || getClass() != obj.getClass() ) {
			return false;
		}
		
		DockerExecutionArguments other = (DockerExecutionArguments)obj;
		return Objects.equal(m_imageRepoName, other.m_imageRepoName) && Objects.equal(m_port, other.m_port);
	}
	
	@Override
	public int hashCode() {
		return Objects.hashCode(m_imageRepoName, m_port);
	}
}
