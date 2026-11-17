package mdt.instance.docker;

import java.time.Duration;

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
	/** 인스턴스 컨테이너를 만들 image 이름({@code docker.image-name}, 필수). */
	private String m_imageName;
	/** 컨테이너 시작 후 인스턴스가 준비될 때까지 기다리는 최대 시간({@code docker.start-timeout}). */
	private Duration m_startTimeout = Duration.ofMinutes(1);
	/** 컨테이너 시작 후 로그 파일({@code logs/app.log})이 생길 때까지 기다리는 최대 시간({@code docker.log-file-timeout}). */
	private Duration m_logFileTimeout = Duration.ofSeconds(30);
	
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
	
	public Duration getStartTimeout() {
		return m_startTimeout;
	}
	
	public void setStartTimeout(Duration startTimeout) {
		m_startTimeout = startTimeout;
	}
	
	public Duration getLogFileTimeout() {
		return m_logFileTimeout;
	}
	
	public void setLogFileTimeout(Duration logFileTimeout) {
		m_logFileTimeout = logFileTimeout;
	}
}
