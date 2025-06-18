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
	
	@JsonCreator
	public DockerExecutionArguments(@JsonProperty("imageRepoName") String imageRepoName) {
		this.m_imageRepoName = imageRepoName;
	}
	
	public String getImageRepoName() {
		return m_imageRepoName;
	}
	
	@Override
	public String toString() {
		return String.format("dockerImage=%s", m_imageRepoName);
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
		return Objects.equal(m_imageRepoName, other.m_imageRepoName);
	}
}
