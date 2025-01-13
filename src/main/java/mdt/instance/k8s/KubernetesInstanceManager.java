package mdt.instance.k8s;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;

import utils.InternalException;
import utils.func.Try;
import utils.io.FileUtils;

import mdt.MDTConfiguration;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.InstanceDescriptorManager;
import mdt.instance.docker.DockerConfiguration;
import mdt.instance.docker.HarborConfiguration;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.ModelValidationException;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class KubernetesInstanceManager extends AbstractInstanceManager<KubernetesInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(KubernetesInstanceManager.class);
	public static final String NAMESPACE = "mdt-instance";
	
	private final String m_dockerEndpoint;
	private final HarborConfiguration m_harborConf;
	
	public KubernetesInstanceManager(MDTConfiguration conf) {
		super(conf);
		
		DockerConfiguration dockerConf = conf.getDockerConfiguration();
		Preconditions.checkNotNull(dockerConf.getDockerEndpoint());

		m_dockerEndpoint = dockerConf.getDockerEndpoint();
		m_harborConf = conf.getHarborConfiguration();
		Preconditions.checkNotNull(m_harborConf);
		
		setLogger(s_logger);
	}

	@Override
	public void initialize(InstanceDescriptorManager instDescManager) throws MDTInstanceManagerException {
	}
	
	public HarborConfiguration getHarborConfiguration() {
		return m_harborConf;
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
	
	@Override
	protected KubernetesInstance toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException {
		return new KubernetesInstance(this, descriptor);
	}

	@Override
	public InstanceDescriptor addInstance(String id, int faaastPort, File bundleDir) throws ModelValidationException, IOException {
		String repoName = deployInstanceDockerImage(id, bundleDir, m_dockerEndpoint, getHarborConfiguration());
		
		KubernetesExecutionArguments args = KubernetesExecutionArguments.builder()
																		.imageRepoName(repoName)
																		.build();
		try {
			File modelFile = FileUtils.path(bundleDir, MODEL_FILE_NAME);
			String arguments = m_mapper.writeValueAsString(args);
			
			return addInstanceDescriptor(id, modelFile, arguments);
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException("Failed to serialize JarExecutionArguments, cause=" + e);
		}
		finally {
	    	// bundle directory는 docker 이미지를 생성하고나서는 필요가 없기 때문에 제거한다.
	    	Try.accept(bundleDir, FileUtils::deleteDirectory);
		}
	}
	
	@Override
	public String toString() {
		return String.format("%s", getClass().getSimpleName());
	}

	KubernetesRemote newKubernetesRemote() {
		return KubernetesRemote.connect();
	}
}
