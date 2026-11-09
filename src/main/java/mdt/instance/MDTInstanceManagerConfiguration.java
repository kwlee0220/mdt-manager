package mdt.instance;

import java.io.File;

import org.springframework.boot.context.properties.ConfigurationProperties;

import utils.io.FileUtils;

/**
 * {@code instance-manager.*} 설정.
 * <p>
 * 경로 설정({@code bundlesDir}, {@code instancesDir}, {@code globalConfigFile})은 {@code homeDir}(기본: 현재 작업
 * 디렉터리)을 기준으로 한다. 지정하지 않으면 각각 {@code <homeDir>/bundles}, {@code <homeDir>/instances},
 * {@code <homeDir>/mdt_global_config.json}이고, 상대 경로로 지정하면 {@code homeDir} 기준으로 해석한다(절대 경로는
 * 그대로). 값은 getter가 부를 때 계산하므로 설정 순서와 상관없다.
 * <p>
 * 2026-11-05 이전에는 생성자에서 현재 작업 디렉터리 기준으로 세 경로를 미리 계산해, {@code homeDir}만 바꾸면 나머지는
 * 계속 작업 디렉터리 아래를 가리켰고, 상대 경로는 작업 디렉터리 기준이었다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "instance-manager")
public class MDTInstanceManagerConfiguration {
	private String m_type;						// "jar", "docker", "external" ("kubernetes"는 2026-11-05 삭제)
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
	}
	
	// path가 null이면 <homeDir>/defaultName, 상대 경로면 <homeDir>/path, 절대 경로면 그대로.
	private File resolve(File path, String defaultName) {
		if ( path == null ) {
			return new File(m_homeDir, defaultName);
		}
		return path.isAbsolute() ? path : new File(m_homeDir, path.getPath());
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
		return resolve(m_bundlesDir, "bundles");
	}
	
	public void setBundlesDir(File bundlesDir) {
		m_bundlesDir = bundlesDir;
	}
	
	public File getInstancesDir() {
		return resolve(m_instancesDir, "instances");
	}
	
	public void setInstancesDir(File instancesDir) {
		m_instancesDir = instancesDir;
	}
	
	public File getGlobalConfigFile() {
		return resolve(m_globalConfigFile, "mdt_global_config.json");
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
