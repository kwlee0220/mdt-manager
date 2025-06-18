package mdt.exector.jar;

import mdt.model.instance.MDTInstanceStatus;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JarExecutionListener {
	public void stausChanged(String id, MDTInstanceStatus status, String endpoint);
	public void timeoutExpired();
}
