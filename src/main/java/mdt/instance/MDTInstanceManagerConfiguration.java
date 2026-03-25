package mdt.instance;

import java.io.File;

import org.springframework.boot.context.properties.ConfigurationProperties;

import utils.io.FileUtils;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "instance-manager")
public class MDTInstanceManagerConfiguration {
	private String m_type;						// "jar", "docker", "kubernetes", "external"
	private String m_mdtUrl;                	// MDTPlatform 접속을 위한 URL
	
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
	
	public String getType() {
		return m_type;
	}

	public void setType(String type) {
		m_type = type;
	}
	
	public String getMdtUrl() {
		return m_mdtUrl;
	}

	public void setMdtUrl(String url) {
		m_mdtUrl = url;
	}
	
	public File getHomeDir() {
		return m_homeDir;
	}
	
	public void setHomeDir(File homeDir) {
		m_homeDir = homeDir;
	}
	
	public File getBundlesDir() {
		return m_bundlesDir;
	}
	
	public void setBundlesDir(File bundlesDir) {
		m_bundlesDir = bundlesDir;
	}
	
	public File getInstancesDir() {
		return m_instancesDir;
	}
	
	public void setInstancesDir(File instancesDir) {
		m_instancesDir = instancesDir;
	}
	
	public File getGlobalConfigFile() {
		return m_globalConfigFile;
	}
	
	public void setGlobalConfigFile(File globalConfigFile) {
		m_globalConfigFile = globalConfigFile;
	}
	
	public String getInstanceEndpointFormat() {
		return m_instanceEndpointFormat;
	}
	
	public void setInstanceEndpointFormat(String instanceEndpointFormat) {
		m_instanceEndpointFormat = instanceEndpointFormat;
	}
	
	public boolean isAutoStart() {
		return m_autoStart;
	}
	
	public void setAutoStart(boolean autoStart) {
		m_autoStart = autoStart;
	}
}
