package mdt.instance.external;

import java.time.Instant;

import mdt.instance.JpaInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.MDTInstance;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ExternalInstance extends JpaInstance implements MDTInstance {
	private Instant m_lastPing;
	
	ExternalInstance(ExternalInstanceManager manager, JpaInstanceDescriptor desc) {
		super(manager, desc);
		
		m_lastPing = Instant.now();
	}
	
	public Instant getLastPing() {
		return m_lastPing;
	}
	
	public void ping() {
		m_lastPing = Instant.now();
	}

	@Override
	public void startAsync() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void stopAsync() {
		throw new UnsupportedOperationException();
	}

	@Override
	protected void uninitialize() throws Throwable { }
}
