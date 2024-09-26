package mdt.instance.docker;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ListContainersFilterParam;
import org.mandas.docker.client.DockerClient.ListContainersParam;
import org.mandas.docker.client.DockerClient.RemoveContainerParam;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.mandas.docker.client.exceptions.ConflictException;
import org.mandas.docker.client.exceptions.ContainerNotFoundException;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.Container;
import org.mandas.docker.client.messages.ContainerConfig;
import org.mandas.docker.client.messages.ContainerInfo;
import org.mandas.docker.client.messages.HostConfig;
import org.mandas.docker.client.messages.HostConfig.Bind;
import org.mandas.docker.client.messages.PortBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

import utils.InternalException;
import utils.func.Try;

import mdt.MDTConfiguration;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.instance.DockerExecutionArguments;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerInstanceManager extends AbstractInstanceManager<DockerInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(DockerInstanceManager.class);

	private String m_dockerHost;
	private final String m_mountPrefix;
	private final String m_dockerImageId;
	
	public DockerInstanceManager(MDTConfiguration conf) {
		super(conf);
		setLogger(s_logger);
		
		DockerConfiguration dockerConf = conf.getDockerConfiguration();
		Preconditions.checkNotNull(dockerConf.getDockerHost());
		Preconditions.checkNotNull(dockerConf.getDockerImageName());
		
		m_dockerHost = dockerConf.getDockerHost();
		m_mountPrefix = (dockerConf.getMountPrefix() != null) ? dockerConf.getMountPrefix()
																: getInstancesDir().getAbsolutePath();
		m_dockerImageId = dockerConf.getDockerImageName();
	}
	
	/**
	 * RESTful 인터페이스 기반 Docker 접속을 위한 client를 생성한다.
	 * 
	 * @return	{@link DockerClient} 객체.
	 */
	DockerClient newDockerClient() {
		return new JerseyDockerClientBuilder().uri(m_dockerHost).build();
	}
	
	public String getDefaultDockerImageId() {
		return m_dockerImageId;
	}

	@Override
	public MDTInstanceStatus getInstanceStatus(String id) {
		try ( DockerClient docker = newDockerClient() ) {
			Container container = findContainerByInstanceId(docker, id);
			container.state();
			ContainerInfo info = docker.inspectContainer(container.id());
			if ( info.state().running() ) {
				return MDTInstanceStatus.RUNNING;
			}
			else if ( info.state().error().length() > 0 ) {
				return MDTInstanceStatus.FAILED;
			}
			else {
				return MDTInstanceStatus.STOPPED;
			}
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
			ContainerInfo info = docker.inspectContainer(container.id());
			if ( info.state().running() ) {
				int repoPort = getRepositoryPort(info);
				return toServiceEndpoint(repoPort);
			}
			else {
				return null;
			}
		}
		catch ( ContainerNotFoundException e ) {
			return null;
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}
	
	public DockerExecutionArguments parseExecutionArguments(String argsJson) {
		try {
			return m_mapper.readValue(argsJson, DockerExecutionArguments.class);
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException("Failed to parse DockerExecutionArguments string, cause=" + e);
		}
	}
	
	public String toExecutionArgumentsString(DockerExecutionArguments args) {
		try {
			return m_mapper.writeValueAsString(args);
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException("Failed to write DockerExecutionArguments string, cause=" + e);
		}
	}

	@Override
	protected DockerInstance toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException {
		try ( DockerClient docker = newDockerClient() ) {
			Container container = findContainerByInstanceId(docker, descriptor.getId());
			return new DockerInstance(this, descriptor, container);
		}
		catch ( ContainerNotFoundException e ) {
			throw new MDTInstanceManagerException("Cannot find Docker container: id=" + descriptor.getId());
		}
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("Failed to get DockerInstance: id=" + descriptor.getId());
		}
	}
	
	private String getHostMountPath(String instanceId, String path) {
		return String.format("%s/%s/%s", m_mountPrefix, instanceId, path);
	}
	
	private String getHostMountPath(String instanceId) {
		return String.format("%s/%s", m_mountPrefix, instanceId);
	}

	@Override
	protected JpaInstanceDescriptor initializeInstance(JpaInstanceDescriptor desc) {
		File instanceWorkspaceDir = getInstanceHomeDir(desc.getId());
		
		try ( DockerClient docker = newDockerClient() ) {
			DockerExecutionArguments args = parseExecutionArguments(desc.getArguments());
			
//			Bind dirBinding = Bind.builder()
//									.from(getHostMountPath(desc.getId()))
//									.to("/configs")
//									.readOnly(true)
//									.build();
			
			// model 파일 복사 후 root 디렉토리로 bind mount 생성
			File modelFile = new File(instanceWorkspaceDir, MODEL_FILE_NAME);
			copyFileIfNotSame(new File(args.getModelFile()), modelFile);
			args.setModelFile(MDTInstanceManager.MODEL_FILE_NAME);
			Bind modelBinding = Bind.builder()
									.from(getHostMountPath(desc.getId(), MODEL_FILE_NAME))
									.to("/" + MODEL_FILE_NAME)
									.readOnly(true).build();

			// configuration 파일 복사 후 root 디렉토리로 bind mount 생성
			File confFile = new File(instanceWorkspaceDir, CONF_FILE_NAME);
			copyFileIfNotSame(new File(args.getConfigFile()), confFile);
			args.setConfigFile(CONF_FILE_NAME);
			Bind confBinding = Bind.builder()
									.from(getHostMountPath(desc.getId(), CONF_FILE_NAME))
									.to("/" + CONF_FILE_NAME)
									.readOnly(true).build();

			// 443 port binding
			Map<String,List<PortBinding>> portBindings = Maps.newHashMap();
			portBindings.put("443/tcp", Arrays.asList(PortBinding.randomPort("0.0.0.0")));
			
			HostConfig hostConf = HostConfig.builder()
											.portBindings(portBindings)
											.binds(modelBinding)
											.binds(confBinding)
											.build();
			Map<String,String> labels = Maps.newHashMap();
			labels.put("mdt-id", desc.getId());
			labels.put("mdt-aas-id", desc.getAasId());
			if ( desc.getAasIdShort() != null ) {
				labels.put("mdt-aas-idshort", desc.getAasIdShort());
			}
			if ( desc.getGlobalAssetId() != null ) {
				labels.put("mdt-asset-id", desc.getGlobalAssetId());
			}
			if ( desc.getAssetType() != null ) {
				labels.put("mdt-asset-type", desc.getAssetType());
			}
			ContainerConfig containerConf = ContainerConfig.builder()
															.hostConfig(hostConf)
															.image(args.getImageId())
															.labels(labels)
															.build();
			docker.createContainer(containerConf, desc.getId());
			
			return desc;
		}
		catch ( ConflictException e ) {
			throw new ResourceAlreadyExistsException("DockerContainer", desc.getId());
		}
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("Failed to create an MDTInstance: id=" + desc.getId()
													+ ", cause=" + e);
		}
	}

	@Override
	public void removeAllInstances() throws MDTInstanceManagerException {
		try ( DockerClient docker = newDockerClient() ) {
			Try.run(() -> super.removeAllInstances());
			
			// Remove all dangling MDTInstance docker containers
			for ( Container container: docker.listContainers(ListContainersParam.allContainers()) ) {
				if ( container.labels().containsKey("mdt-id") ) {
					Try.run(() -> docker.removeContainer(container.id(), RemoveContainerParam.forceKill()));
				}
			}
		}
		catch ( Exception ignored ) { }
	}

	private Container findContainerByInstanceId(DockerClient docker, String instanceId)
		throws DockerException, InterruptedException, ContainerNotFoundException {
		List<Container> containers = docker.listContainers(ListContainersParam.allContainers(),
												ListContainersFilterParam.filter("name", instanceId));
		if ( containers.size() == 0 ) {
			throw new ContainerNotFoundException(instanceId);
		}
		else if ( containers.size() == 1 ) {
			return containers.get(0);
		}
		else {
			throw new MDTInstanceManagerException("Duplicate DockerInstances: id=" + instanceId);
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
	
	private void copyFileIfNotSame(File src, File dest) throws IOException {
		if ( !src.getAbsolutePath().equals(dest.getAbsolutePath()) ) {
			Files.copy(src, dest);
		}
	}
	
	private int getRepositoryPort(ContainerInfo info) {
		List<PortBinding> hostPorts = info.networkSettings().ports().get("443/tcp");
		if ( hostPorts == null || hostPorts.size() == 0 ) {
			throw new InternalException("Cannot find external port");
		}
		return Integer.parseInt(hostPorts.get(0).hostPort());
	}
}
