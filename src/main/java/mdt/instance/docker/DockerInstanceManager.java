package mdt.instance.docker;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ListContainersFilterParam;
import org.mandas.docker.client.DockerClient.ListContainersParam;
import org.mandas.docker.client.DockerClient.RemoveContainerParam;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.mandas.docker.client.exceptions.ContainerNotFoundException;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.Container;
import org.mandas.docker.client.messages.ContainerInfo;
import org.mandas.docker.client.messages.PortBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;

import utils.InternalException;
import utils.func.Try;
import utils.func.Tuple;
import utils.io.FileUtils;

import mdt.MDTConfiguration;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.InstanceDescriptorManager;
import mdt.instance.JpaInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.ModelValidationException;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerInstanceManager extends AbstractInstanceManager<JpaInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(DockerInstanceManager.class);
	private static final String LABEL_NAME_MDT_TWIN_ID = "mdt-twin-id";

	private final DockerConfiguration m_dockerConf;
	private final HarborConfiguration m_harborConf;
	
	public DockerInstanceManager(MDTConfiguration conf) {
		super(conf);
		setLogger(s_logger);
		
		m_dockerConf = conf.getDockerConfiguration();
		Preconditions.checkNotNull(m_dockerConf.getDockerEndpoint());
		
		m_harborConf = conf.getHarborConfiguration();
	}

	@Override
	public void initialize(InstanceDescriptorManager instDescManager) throws MDTInstanceManagerException {
	}

	Tuple<MDTInstanceStatus,String> getInstanceState(DockerClient docker, String containerId) {
		try {
			ContainerInfo info = docker.inspectContainer(containerId);
			if ( info.state().running() ) {
				return Tuple.of(MDTInstanceStatus.RUNNING, toServiceEndpoint(getRepositoryPort(info)));
			}
			else if ( info.state().error().length() > 0 ) {
				return Tuple.of(MDTInstanceStatus.FAILED, null);
			}
			else {
				return Tuple.of(MDTInstanceStatus.STOPPED, null);
			}
		}
		catch ( ContainerNotFoundException e ) {
			return Tuple.of(MDTInstanceStatus.STOPPED, null);
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}

	@Override
	public MDTInstanceStatus getInstanceStatus(String id) {
		try ( DockerClient docker = newDockerClient() ) {
			Container container = findContainerByInstanceId(docker, id);
			return getInstanceState(docker, container.id())._1;
		}
		catch ( ContainerNotFoundException e ) {
			return MDTInstanceStatus.STOPPED;
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}

	@Override
	public String getInstanceServiceEndpoint(String id) {
		try ( DockerClient docker = newDockerClient() ) {
			Container container = findContainerByInstanceId(docker, id);
			return getInstanceState(docker, container.id())._2;
		}
		catch ( ContainerNotFoundException e ) {
			return null;
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}

	@Override
	public InstanceDescriptor addInstance(String id, int faaastPort, File bundleDir)
		throws ModelValidationException, IOException {
		String repoName = deployInstanceDockerImage(id, bundleDir, m_dockerConf.getDockerEndpoint(),
													m_harborConf);
		
		DockerExecutionArguments args = new DockerExecutionArguments(repoName, faaastPort);
		try {
			File modelFile = FileUtils.path(bundleDir, MODEL_FILE_NAME);
			String arguments = m_mapper.writeValueAsString(args);
			
			JpaInstanceDescriptor desc = addInstanceDescriptor(id, modelFile, arguments);
			if ( s_logger.isInfoEnabled() ) {
				s_logger.info("Added JpaInstanceDescriptor=" + desc);
			}
			
			return desc;
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException(e);
		}
		finally {
	    	// bundle directory는 docker 이미지를 생성하고나서는 필요가 없기 때문에 제거한다.
	    	Try.accept(bundleDir, FileUtils::deleteDirectory);
		}
	}

	@Override
	public void removeAllInstances() throws MDTInstanceManagerException {
		try ( DockerClient docker = newDockerClient() ) {
			Try.run(() -> super.removeAllInstances());
			
			// Remove all dangling MDTInstance docker containers
			for ( Container container: docker.listContainers(ListContainersParam.allContainers()) ) {
				if ( container.labels().containsKey(LABEL_NAME_MDT_TWIN_ID) ) {
					Try.run(() -> docker.removeContainer(container.id(), RemoveContainerParam.forceKill()));
				}
			}
		}
		catch ( Exception ignored ) { }
	}
	
	@Override
	public String toString() {
		return String.format("%s[dockerHost=%s]", getClass().getSimpleName(), m_dockerConf.getDockerEndpoint());
	}
	
	public DockerExecutionArguments getExecutionArguments(JpaInstanceDescriptor desc) {
		try {
			return m_mapper.readValue(desc.getArguments(), DockerExecutionArguments.class);
		}
		catch ( Exception e ) {
			String msg = String.format("Failed to read DockerExecutionArguments from JpaInstanceDescriptor: "
										+ "args=%s, cause=%s", desc.getArguments(), e);
			throw new InternalException(msg);
		}
	}

	@Override
	protected DockerInstance toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException {
		descriptor.setStatus(getInstanceStatus(descriptor.getId()));
		descriptor.setBaseEndpoint(getInstanceServiceEndpoint(descriptor.getId()));
		return new DockerInstance(this, descriptor);
	}

	private Container findContainerByInstanceId(DockerClient docker, String instanceId)
		throws DockerException, InterruptedException, ContainerNotFoundException {
		List<Container> containers = docker.listContainers(ListContainersParam.allContainers(),
															ListContainersFilterParam.filter("name", instanceId));
		if ( containers.size() == 0 ) {
			throw new ContainerNotFoundException("container-id=" + instanceId);
		}
		else if ( containers.size() == 1 ) {
			return containers.get(0);
		}
		else {
			throw new InternalException("Duplicate DockerInstances: id=" + instanceId);
		}
	}

//	@SuppressWarnings("unused")
//	private DockerInstance toInstance(Container container) throws MDTInstanceManagerException {
//		String id = container.names().get(0).substring(1);
//		
//		Map<String,String> labels = container.labels();
//		String aasId = labels.get("mdt-aas-id");
//		String aasIdShort = labels.get("mdt-aas-idshort");
//		String assetId = labels.get("mdt-asset-id");
//		String assetType = labels.get("mdt-asset-type");
//		
//		String modelFilePath = new File(new File(getWorkspaceDir(), id), MDTInstanceManager.CANONICAL_MODEL_FILE).getAbsolutePath();
//		DockerExecutionArguments args = DockerExecutionArguments.builder()
//													.imageId(container.image())
//													.modelFile(modelFilePath)
//													.build();
//		try {
//			String argsJson = m_mapper.writeValueAsString(args);
//			JpaInstanceDescriptor desc = new JpaInstanceDescriptor(id, aasId, aasIdShort, assetId, assetType,
//															argsJson, null, Lists.newArrayList());
//			
//			return new DockerInstance(this, desc, container);
//		}
//		catch ( JsonProcessingException e ) {
//			throw new MDTInstanceManagerException("" + e);
//		}
//	}
	
	private int getRepositoryPort(ContainerInfo info) {
		List<PortBinding> hostPorts = info.networkSettings().ports().get("443/tcp");
		if ( hostPorts == null || hostPorts.size() == 0 ) {
			throw new InternalException("Cannot find external port");
		}
		return Integer.parseInt(hostPorts.get(0).hostPort());
	}
	
	/**
	 * RESTful 인터페이스 기반 Docker 접속을 위한 client를 생성한다.
	 * 
	 * @return	{@link DockerClient} 객체.
	 */
	DockerClient newDockerClient() {
		return new JerseyDockerClientBuilder().uri(m_dockerConf.getDockerEndpoint()).build();
	}
	
	MDTHarborClient newHarborClient() {
		return new MDTHarborClient(m_harborConf);
	}
}
