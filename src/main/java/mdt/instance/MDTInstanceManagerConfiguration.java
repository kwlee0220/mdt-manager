package mdt.instance;

import java.io.File;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.io.FileUtils;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "instance-manager")
@Accessors(prefix = "m_")
@Getter @Setter
public class MDTInstanceManagerConfiguration {
	private String m_type;						// "jar", "docker", "kubernetes", "external"
	private String m_endpoint;                	// MDTInstanceManager 접속을 위한 URL
	
	private File m_homeDir;						// MDTInstanceManager home 디렉토리.
	private File m_bundlesDir;   				// MDTInstanceManager bundle 디렉토리.
	private File m_instancesDir;				// MDTInstanceManager instance 디렉토리.
	private File m_globalConfigFile;			// MDTInstance들이 공유하는 전역 설정 파일 경로
//	private File m_shareDir;					// MDT Instance들의 기본 설정 정보 디렉토리.
	
	private String m_instanceEndpointFormat;	// MDTInstance 접속을 위한 URL 포맷
	private boolean m_autoStart = false;	// MDTInstanceManager 기동시 등록된 MDTInstance들을 자동으로 시작할지 여부
	
	public MDTInstanceManagerConfiguration() {
		m_homeDir = FileUtils.getCurrentWorkingDirectory();
		m_instancesDir = new File(m_homeDir, "instances");
		m_bundlesDir = new File(m_homeDir, "bundles");
		m_globalConfigFile = new File(m_homeDir, "mdt_global_config.json");
	}
}
