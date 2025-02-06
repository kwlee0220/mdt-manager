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
		String details = String.format("%s cannot be started by client: id=%s", getClass().getSimpleName(), getId());
		throw new UnsupportedOperationException(details);
	}

	@Override
	public void stopAsync() {
		String details = String.format("%s cannot be stoppedby client: id=%s", getClass().getSimpleName(), getId());
		throw new UnsupportedOperationException(details);
	}

	@Override
	protected void uninitialize() throws Throwable { }
}
