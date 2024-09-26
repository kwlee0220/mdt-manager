package mdt.exector.jar;

import java.io.File;
import java.time.Duration;

import lombok.Data;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Data
public class JarExecutionConfiguration {
	private File workspaceDir;
	private Duration sampleInterval;
	private Duration startTimeout;
}
