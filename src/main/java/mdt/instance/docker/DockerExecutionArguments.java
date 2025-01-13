package mdt.instance.docker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DockerExecutionArguments {
	@JsonProperty("imageRepoName") private final String m_imageRepoName;
	@JsonProperty("faaastPort") private final int m_faaastPort;
	
	@JsonCreator
	public DockerExecutionArguments(@JsonProperty("imageRepoName") String imageRepoName,
									@JsonProperty("faaastPort") int faaastPort) {
		this.m_imageRepoName = imageRepoName;
		this.m_faaastPort = faaastPort;
	}
	
	public String getImageRepoName() {
		return m_imageRepoName;
	}
	
	public int getFaaastPort() {
		return m_faaastPort;
	}
}
