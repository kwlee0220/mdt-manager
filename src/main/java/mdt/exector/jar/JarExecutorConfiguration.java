package mdt.exector.jar;

import java.io.File;
import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix = "m_")
public class JarExecutorConfiguration {
	private File m_workspaceDir;
	private Duration m_sampleInterval;
	private Duration m_startTimeout;
	private int m_startConcurrency = 7;
	private String m_heapSize;
}
