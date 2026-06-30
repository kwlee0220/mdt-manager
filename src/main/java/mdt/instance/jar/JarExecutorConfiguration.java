package mdt.instance.jar;

import java.io.File;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import utils.io.FileUtils;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "executor")
public class JarExecutorConfiguration {
	private File m_defaultMDTInstanceJarFile;	// MDTInstance 수행을 위한 기본 jar 파일
	private File m_keyStoreFile;
	private String m_keyStorePassword;
	
	private File m_workspaceDir;
	private Duration m_sampleInterval = Duration.ofSeconds(2);
	private Duration m_startTimeout = Duration.ofMinutes(1);
	private int m_startConcurrency = 5;
	private String m_heapSize = "512m";
	
	public JarExecutorConfiguration() {
		m_workspaceDir = new File(FileUtils.getCurrentWorkingDirectory(), "instances");
		m_defaultMDTInstanceJarFile = new File(FileUtils.getCurrentWorkingDirectory(), "mdt-instance-all.jar");
		m_keyStoreFile = new File(FileUtils.getCurrentWorkingDirectory(), "mdt_cert.p12");
	}
	
	public File getDefaultMDTInstanceJarFile() {
		return m_defaultMDTInstanceJarFile;
	}
	
	public void setDefaultMDTInstanceJarFile(File defaultMDTInstanceJarFile) {
		m_defaultMDTInstanceJarFile = defaultMDTInstanceJarFile;
	}
	
	public File getKeyStoreFile() {
		return m_keyStoreFile;
	}
	
	public void setKeyStoreFile(File keyStoreFile) {
		m_keyStoreFile = keyStoreFile;
	}
	
	public String getKeyStorePassword() {
		return m_keyStorePassword;
	}
	
	public void setKeyStorePassword(String keyStorePassword) {
		m_keyStorePassword = keyStorePassword;
	}
	
	public File getWorkspaceDir() {
		return m_workspaceDir;
	}
	
	public void setWorkspaceDir(File workspaceDir) {
		m_workspaceDir = workspaceDir;
	}
	
	public Duration getSampleInterval() {
		return m_sampleInterval;
	}
	
	public void setSampleInterval(Duration sampleInterval) {
		m_sampleInterval = sampleInterval;
	}
	
	public Duration getStartTimeout() {
		return m_startTimeout;
	}
	
	public void setStartTimeout(Duration startTimeout) {
		m_startTimeout = startTimeout;
	}
	
	public int getStartConcurrency() {
		return m_startConcurrency;
	}
	
	public void setStartConcurrency(int startConcurrency) {
		m_startConcurrency = startConcurrency;
	}
	
	public String getHeapSize() {
		return m_heapSize;
	}
	
	public void setHeapSize(String heapSize) {
		m_heapSize = heapSize;
	}
}
