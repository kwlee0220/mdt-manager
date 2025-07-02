package mdt.instance.docker;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
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
import com.google.common.collect.Maps;

import utils.InternalException;
import utils.Throwables;
import utils.Tuple;
import utils.func.Try;
import utils.io.FileUtils;

import mdt.MDTConfigurations;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.ModelValidationException;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerInstanceManager extends AbstractJpaInstanceManager<DockerInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(DockerInstanceManager.class);
	static final String LABEL_NAME_MDT_TWIN_ID = "mdt-twin-id";

	private final DockerConfiguration m_dockerConf;
	private final HarborConfiguration m_harborConf;
	private final String m_repositoryEndpointFormat;
	private final Map<String,MDTInstanceStatus> m_instanceStatus = Maps.newHashMap();

	public DockerInstanceManager(MDTConfigurations configs) throws Exception {
		super(configs);
		setLogger(s_logger);
		
		m_dockerConf = configs.getDockerConfig();
		Preconditions.checkNotNull(m_dockerConf.getDockerEndpoint());
		
		m_harborConf = configs.getHarborConfig();
		
		String epFormat = m_conf.getInstanceEndpointFormat();
		if ( epFormat == null ) {
			try {
				String host = InetAddress.getLocalHost().getHostAddress();
				epFormat = "https:" + host + ":%d/api/v3.0";
			}
			catch ( Exception e ) {
				throw new MDTInstanceManagerException("" + e);
			}
		}
		m_repositoryEndpointFormat = epFormat;
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("use MDTInstance endpoint format: {}", m_repositoryEndpointFormat);
		}
	}

	Tuple<MDTInstanceStatus,String> getInstanceState(String instanceId, DockerClient docker, Container container) {
		try {
			ContainerInfo info = docker.inspectContainer(container.id());
			if ( info.state().running() ) {
				MDTInstanceStatus lastStatus = getContainerStatus(instanceId);
				if ( lastStatus == MDTInstanceStatus.STOPPED || lastStatus == MDTInstanceStatus.FAILED ) {
					putContainerStatus(instanceId, MDTInstanceStatus.RUNNING);
				}
				return Tuple.of(lastStatus, toServiceEndpoint(getRepositoryPort(info)));
			}
			else if ( info.state().error().length() > 0 ) {
				putContainerStatus(instanceId, MDTInstanceStatus.FAILED);
				return Tuple.of(MDTInstanceStatus.FAILED, null);
			}
			else {
				putContainerStatus(instanceId, MDTInstanceStatus.STOPPED);
				return Tuple.of(MDTInstanceStatus.STOPPED, null);
			}
		}
		catch ( ContainerNotFoundException e ) {
			putContainerStatus(instanceId, MDTInstanceStatus.STOPPED);
			return Tuple.of(MDTInstanceStatus.STOPPED, null);
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}

	@Override
	public MDTInstance addInstance(String id, File bundleDir)
		throws ModelValidationException, IOException, MDTInstanceManagerException {
		String repoName = "kwlee0220/faaast-starter:latest";
		
		DockerExecutionArguments args = new DockerExecutionArguments(repoName);
		try {
			// bundle directory 전체가 해당 instance의 workspace가 되기 때문에
			// instances 디렉토리로 이동시킨다.
			File instDir = getInstanceHomeDir(id);
			FileUtils.deleteDirectory(instDir);
			FileUtils.move(bundleDir, instDir);

			// Global 설정 파일이 없는 경우에는 default 설정 파일을 사용한다.
			File globalConfFile = new File(instDir, MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
			if ( !globalConfFile.exists() ) {
				File defaultGlobalConfFile = new File(getHomeDir(), MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
				if ( !defaultGlobalConfFile.exists()) {
					throw new IllegalStateException("No default global configuration file exists: path="
														+ defaultGlobalConfFile);
				}
				FileUtils.copy(defaultGlobalConfFile, globalConfFile);
			}
			
			// Certificate 파일이 없는 instDir default 파일을 사용한다.
			File certFile = new File(instDir, MDTInstanceManager.CERT_FILE_NAME);
			if ( !certFile.exists() ) {
				File defaultCertFile = new File(getHomeDir(), MDTInstanceManager.CERT_FILE_NAME);
				if ( !defaultCertFile.exists() ) {
					throw new IllegalStateException("No default certificate file exists: path=" + defaultCertFile);
				}
				FileUtils.copy(defaultCertFile, certFile);
			}

			File modelFile = FileUtils.path(instDir, MODEL_AASX_NAME);
			if ( !modelFile.canRead() ) {
				modelFile = FileUtils.path(instDir, MODEL_FILE_NAME);
			}
			Environment env = AASUtils.readEnvironment(modelFile);
			String arguments = m_mapper.writeValueAsString(args);
			
			JpaInstanceDescriptor desc = addInstanceDescriptor(id, env, arguments);
			if ( getLogger().isInfoEnabled() ) {
				getLogger().info("added DockerInstance: id={}, instanceDir={}",
									desc.getId(), instDir);
			}
			
			return toInstance(desc);
		}
		catch ( JsonProcessingException e ) {
			throw new IOException(e);
		}
		catch ( ModelValidationException |  IOException | MDTInstanceManagerException e ) {
			throw e;
		}
		catch ( Throwable e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new MDTInstanceManagerException("fails to add instance: id=" + id, cause);
		}
	}

	@Override
	public void removeInstanceAll() throws MDTInstanceManagerException {
		try ( DockerClient docker = newDockerClient() ) {
			Try.run(() -> super.removeInstanceAll());
			
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
	
	public String toServiceEndpoint(int repoPort) {
		return String.format(m_repositoryEndpointFormat, repoPort);
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
	protected void updateInstanceDescriptor(JpaInstanceDescriptor desc) {
		String id = desc.getId();
		try ( DockerClient docker = newDockerClient() ) {
			ContainerInfo info = docker.inspectContainer(id);
			if ( info.state().running() ) {
				MDTInstanceStatus lastStatus = getContainerStatus(id);
				if ( lastStatus == MDTInstanceStatus.STOPPED || lastStatus == MDTInstanceStatus.FAILED ) {
					putContainerStatus(id, MDTInstanceStatus.RUNNING);
				}
				
				desc.setStatus(lastStatus);
				desc.setBaseEndpoint(toServiceEndpoint(getRepositoryPort(info)));
			}
			else if ( info.state().error().length() > 0 ) {
				putContainerStatus(id, MDTInstanceStatus.FAILED);
				desc.setStatus(MDTInstanceStatus.FAILED);
				desc.setBaseEndpoint(null);
			}
			else {
				putContainerStatus(id, MDTInstanceStatus.STOPPED);
				desc.setStatus(MDTInstanceStatus.STOPPED);
				desc.setBaseEndpoint(null);
			}
		}
		catch ( ContainerNotFoundException e ) {
			desc.setStatus(MDTInstanceStatus.STOPPED);
			desc.setBaseEndpoint(null);
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}

	@Override
	protected DockerInstance toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException {
		return new DockerInstance(this, descriptor);
	}

	Container findContainerByInstanceId(DockerClient docker, String instanceId)
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
	
	MDTInstanceStatus getContainerStatus(String instanceId) {
		return m_instanceStatus.computeIfAbsent(instanceId, id -> MDTInstanceStatus.STOPPED);
	}
	void putContainerStatus(String instanceId, MDTInstanceStatus status) {
		m_instanceStatus.put(instanceId, status);
	}
	
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
