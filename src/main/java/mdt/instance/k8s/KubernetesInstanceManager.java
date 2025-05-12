package mdt.instance.k8s;

import java.io.File;
import java.io.IOException;

import javax.annotation.Nullable;

import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;

import utils.InternalException;
import utils.io.FileUtils;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import mdt.MDTConfiguration;
import mdt.controller.DockerCommandUtils;
import mdt.controller.DockerCommandUtils.StandardOutputHandler;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.docker.DockerConfiguration;
import mdt.instance.docker.DockerUtils;
import mdt.instance.docker.HarborConfiguration;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.ModelValidationException;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class KubernetesInstanceManager extends AbstractJpaInstanceManager<KubernetesInstance> {
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
	
	public HarborConfiguration getHarborConfiguration() {
		return m_harborConf;
	}

//	@Override
//	public MDTInstanceStatus getInstanceStatus(String id) {
//		KubernetesRemote kube = newKubernetesRemote();
//		Pod pod = kube.getPod(NAMESPACE, id);
//		if ( pod == null ) {
//			return MDTInstanceStatus.STOPPED;
//		}
//		
//		String phase = pod.getStatus().getPhase();
//		switch ( phase ) {
//			case "Pending":
//				return MDTInstanceStatus.STARTING;
//			case "Running":
//				return MDTInstanceStatus.RUNNING;
//			case "Succeeded":
//				return MDTInstanceStatus.STOPPED;
//			case "Failed":
//				return MDTInstanceStatus.FAILED;
//			case "Unknown":
//				return MDTInstanceStatus.FAILED;
//			default:
//				throw new AssertionError();
//		}
//	}
//
//	@Override
//	public String getInstanceServiceEndpoint(String id) {
//		KubernetesRemote kube = newKubernetesRemote();
//		Service service = kube.getService(NAMESPACE, id);
//		if ( service != null ) {
//			int port = service.getSpec().getPorts().get(0).getNodePort();
//			return toServiceEndpoint(port);
//		}
//		else {
//			return null;
//		}
//	}
	
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
	protected void updateInstanceDescriptor(JpaInstanceDescriptor desc) {
		String id = desc.getId();
		KubernetesRemote kube = newKubernetesRemote();
		Pod pod = kube.getPod(NAMESPACE, id);
		if ( pod == null ) {
			desc.setStatus(MDTInstanceStatus.STOPPED);
			desc.setBaseEndpoint(null);
			return;
		}

		String phase = pod.getStatus().getPhase();
		switch (phase) {
			case "Pending":
				desc.setStatus(MDTInstanceStatus.STARTING);
				desc.setBaseEndpoint(null);
				return;
			case "Running":
				String endpoint = null;
				Service service = kube.getService(NAMESPACE, id);
				if ( service != null ) {
					int port = service.getSpec().getPorts().get(0).getNodePort();
					endpoint = toServiceEndpoint(port);
				}
				desc.setStatus(MDTInstanceStatus.RUNNING);
				desc.setBaseEndpoint(endpoint);
				return;
			case "Succeeded":
				desc.setStatus(MDTInstanceStatus.STOPPED);
				desc.setBaseEndpoint(null);
				return;
			case "Failed":
			case "Unknown":
				desc.setStatus(MDTInstanceStatus.FAILED);
				desc.setBaseEndpoint(null);
				return;
			default:
				throw new AssertionError();
		}
	}

	@Override
	public MDTInstance addInstance(String id, int faaastPort, File bundleDir)
		throws ModelValidationException, IOException {
		String repoName = deployInstanceDockerImage(id, bundleDir, m_dockerEndpoint, getHarborConfiguration());
		
		KubernetesExecutionArguments args = KubernetesExecutionArguments.builder()
																		.imageRepoName(repoName)
																		.build();
		try {
			File modelFile = FileUtils.path(bundleDir, MODEL_FILE_NAME);
			Environment env = AASUtils.readEnvironment(modelFile);
			String arguments = m_mapper.writeValueAsString(args);
			
			JpaInstanceDescriptor desc = addInstanceDescriptor(id, env, arguments);
			return toInstance(desc);
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException("Failed to serialize JarExecutionArguments, cause=" + e);
		}
	}
	
	@Override
	public String toString() {
		return String.format("%s", getClass().getSimpleName());
	}

	KubernetesRemote newKubernetesRemote() {
		return KubernetesRemote.connect();
	}
	
	private String deployInstanceDockerImage(String id, File bundleDir, String dockerEndpoint,
													@Nullable HarborConfiguration harborConf) {
		try ( DockerClient docker = new JerseyDockerClientBuilder().uri(dockerEndpoint).build() ) {
			// 동일 image id의 docker image가 존재할 수 있기 때문에 이를 먼저 삭제한다.
			DockerUtils.removeInstanceImage(docker, id);
			
			// bundleDir에 포함된 데이터를 이용하여 docker image를 생성한다.
			if ( getLogger().isInfoEnabled() ) {
				getLogger().info("Start building docker image: instance=" + id + ", bundleDir=" + bundleDir);
			}
			
			StandardOutputHandler outputHandler = new DockerCommandUtils.RedirectOutput(
																			new File(bundleDir, "stdout.log"),
																			new File(bundleDir, "stderr.log"));
			String repoName = DockerCommandUtils.buildDockerImage(id, bundleDir, outputHandler);
			if ( s_logger.isInfoEnabled() ) {
				s_logger.info("Done: docker image: repo=" + repoName);
			}
	    	
			if ( harborConf != null && harborConf.getEndpoint() != null ) {  	
				// Harbor로 push하기 위한 tag를 부여한다.
				Image image = DockerUtils.getInstanceImage(docker, id).getUnchecked();
				String harborRepoName = DockerUtils.tagImageForHarbor(docker, image, harborConf, "latest");

				if ( getLogger().isInfoEnabled() ) {
					getLogger().info("Pusing docker image to Harbor: instance={}, repo={}", id, harborRepoName);
				}
				DockerUtils.pushImage(docker, harborRepoName, harborConf);
				if ( s_logger.isInfoEnabled() ) {
					s_logger.info("Done: push to Harbor: repo=" + harborRepoName);
				}
				
		    	// Harbor로 push하고 나서는 tag되기 이전 image를 삭제한다.
				docker.removeImage(repoName);
				
				// harbor로 push된 경우에는 harbor의 docker image를 사용한다.
				repoName = harborRepoName;
			}
			
			return repoName;
		}
		catch ( DockerException e ) {
			throw new MDTInstanceManagerException("Failed to add a DockerInstance: id=" + id, e);
		}
		catch ( InterruptedException e ) {
			throw new MDTInstanceManagerException("MDTInstance addition has been interrupted");
		}
	}
}
