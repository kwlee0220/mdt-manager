package mdt.exector.jar;

import mdt.model.instance.MDTInstanceStatus;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JarExecutionListener {
	public void statusChanged(String id, MDTInstanceStatus status, String endpoint);
	public void timeoutExpired();
}
