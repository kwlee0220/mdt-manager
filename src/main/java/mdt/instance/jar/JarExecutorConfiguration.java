package mdt.instance.jar;

import java.io.File;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.io.FileUtils;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "executor")
@Getter @Setter
@Accessors(prefix = "m_")
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
}
