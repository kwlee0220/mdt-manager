package mdt.instance.k8s;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import utils.InternalException;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import mdt.MDTConfiguration;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.KubernetesExecutionArguments;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class KubernetesInstanceManager extends AbstractInstanceManager<KubernetesInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(KubernetesInstanceManager.class);
	public static final String NAMESPACE = "mdt-instance";
	
	public KubernetesInstanceManager(MDTConfiguration conf) {
		super(conf);
		
		setLogger(s_logger);
	}

	@Override
	public MDTInstanceStatus getInstanceStatus(String id) {
		KubernetesRemote kube = newKubernetesRemote();
		Pod pod = kube.getPod(NAMESPACE, id);
		if ( pod == null ) {
			return MDTInstanceStatus.STOPPED;
		}
		
		String phase = pod.getStatus().getPhase();
		switch ( phase ) {
			case "Pending":
				return MDTInstanceStatus.STARTING;
			case "Running":
				return MDTInstanceStatus.RUNNING;
			case "Succeeded":
				return MDTInstanceStatus.STOPPED;
			case "Failed":
				return MDTInstanceStatus.FAILED;
			case "Unknown":
				return MDTInstanceStatus.FAILED;
			default:
				throw new AssertionError();
		}
	}

	@Override
	public String getInstanceServiceEndpoint(String id) {
		KubernetesRemote kube = newKubernetesRemote();
		Service service = kube.getService(NAMESPACE, id);
		if ( service != null ) {
			int port = service.getSpec().getPorts().get(0).getNodePort();
			return toServiceEndpoint(port);
		}
		else {
			return null;
		}
	}
	
	public KubernetesExecutionArguments parseExecutionArguments(String argsJson) {
		try {
			return m_mapper.readValue(argsJson, KubernetesExecutionArguments.class);
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException("Failed to parse KubernetesExecutionArguments string, cause=" + e);
		}
	}
	
	public String toExecutionArgumentsString(KubernetesExecutionArguments args) {
		try {
			return m_mapper.writeValueAsString(args);
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException("Failed to write KubernetesExecutionArguments string, cause=" + e);
		}
	}
	
	@Override
	protected KubernetesInstance toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException {
		return new KubernetesInstance(this, descriptor);
	}
	
	@Override
	protected JpaInstanceDescriptor initializeInstance(JpaInstanceDescriptor desc) {
		return desc;
	}

	KubernetesRemote newKubernetesRemote() {
		return KubernetesRemote.connect();
	}
}
