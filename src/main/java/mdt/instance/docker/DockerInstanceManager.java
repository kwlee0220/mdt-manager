package mdt.instance.docker;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.mandas.docker.client.DockerClient;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;

import utils.InternalException;
import utils.Throwables;
import utils.io.FileUtils;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.MqttConfiguration;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.MDTModelSerDe;
import mdt.model.ModelValidationException;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.repository.Repositories;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Component
@ConditionalOnProperty(prefix="instance-manager", name = "type", havingValue = "docker")
public class DockerInstanceManager extends AbstractJpaInstanceManager<DockerInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(DockerInstanceManager.class);

	private final DockerConfiguration m_dockerConf;
	private final String m_repositoryEndpointFormat;
	// 요청 스레드와 컨테이너 시작 대기 스레드가 함께 쓰므로 ConcurrentHashMap을 쓴다.
	private final Map<String,MDTInstanceStatus> m_instanceStatus = new ConcurrentHashMap<>();

	public DockerInstanceManager(MDTInstanceManagerConfiguration mgrConf,
								DockerConfiguration dockerConf,
								Repositories repos,
								MqttConfiguration mqttConf) throws Exception {
		super(mgrConf, repos, mqttConf);
		setLogger(s_logger);
		
		m_dockerConf = dockerConf;
		Preconditions.checkNotNull(m_dockerConf.getDockerEndpoint(), "docker.docker-endpoint is missing");
		Preconditions.checkNotNull(m_dockerConf.getImageName(), "docker.image-name is missing");
		
		String epFormat = m_conf.getInstanceEndpointFormat();
		if ( epFormat == null ) {
			try {
				String host = InetAddress.getLocalHost().getHostAddress();
				epFormat = "https://" + host + ":%d/api/v3.0";
			}
			catch ( Exception e ) {
				throw new MDTInstanceManagerException("Failed to determine the default instance endpoint format", e);
			}
		}
		m_repositoryEndpointFormat = epFormat;
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("use MDTInstance endpoint format: {}", m_repositoryEndpointFormat);
		}
	}

	@Override
	public MDTInstance addInstance(String id, int port, File bundleDir)
		throws ModelValidationException, IOException, MDTInstanceManagerException {
		// 컨테이너는 docker.image-name의 image와 port 인자의 호스트 포트로 만든다.
		// 기존 인스턴스의 디렉터리를 지우기 전에 확인한다.
		checkNotRegistered(id);
		DockerExecutionArguments args = new DockerExecutionArguments(m_dockerConf.getImageName(), port);
		try {
			// bundle directory 전체가 해당 instance의 workspace가 되기 때문에
			// instances 디렉토리로 이동시킨다.
			File instDir = getInstanceHomeDir(id);
			FileUtils.deleteDirectory(instDir);
			FileUtils.moveDirectory(bundleDir, instDir);

			// Global 설정 파일이 없는 경우에는 default 설정 파일을 사용한다.
			File globalConfFile = new File(instDir, MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
			if ( !globalConfFile.exists() ) {
				File defaultGlobalConfFile = new File(getHomeDir(), MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
				if ( !defaultGlobalConfFile.exists()) {
					throw new IllegalStateException("No default global configuration file exists: path="
														+ defaultGlobalConfFile);
				}
				FileUtils.copyFile(defaultGlobalConfFile, globalConfFile);
			}
			
			// Certificate 파일이 없는 instDir default 파일을 사용한다.
			File certFile = new File(instDir, MDTInstanceManager.CERT_FILE_NAME);
			if ( !certFile.exists() ) {
				File defaultCertFile = new File(getHomeDir(), MDTInstanceManager.CERT_FILE_NAME);
				if ( !defaultCertFile.exists() ) {
					throw new IllegalStateException("No default certificate file exists: path=" + defaultCertFile);
				}
				FileUtils.copyFile(defaultCertFile, certFile);
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
									desc.getInstanceId(), instDir);
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
		try {
			super.removeInstanceAll();
		}
		catch ( Exception e ) {
			getLogger().warn("failed to remove MDTInstances: cause={}", "" + e);
		}
		
		// Remove all dangling MDTInstance docker containers
		try ( DockerClient docker = newDockerClient() ) {
			for ( Container container: docker.listContainers(ListContainersParam.allContainers()) ) {
				if ( container.labels().containsKey(DockerUtils.LABEL_NAME_MDT_TWIN_ID) ) {
					try {
						docker.removeContainer(container.id(), RemoveContainerParam.forceKill());
					}
					catch ( DockerException e ) {
						getLogger().warn("failed to remove a dangling container: id={}, cause={}",
										container.id(), "" + e);
					}
				}
			}
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			getLogger().warn("interrupted while removing dangling containers");
		}
		catch ( Exception e ) {
			getLogger().warn("failed to remove dangling containers: cause={}", "" + e);
		}
	}
	
	@Override
	public String toString() {
		return String.format("%s[dockerHost=%s]", getClass().getSimpleName(), m_dockerConf.getDockerEndpoint());
	}
	
	public DockerConfiguration getDockerConfiguration() {
		return m_dockerConf;
	}
	
	public String toServiceEndpoint(int repoPort) {
		return String.format(m_repositoryEndpointFormat, repoPort);
	}
	
	public DockerExecutionArguments getExecutionArguments(String instanceId) {
		JpaInstanceDescriptor jpaDesc = getJpaInstanceDescriptor(instanceId);
		try {
			return MDTModelSerDe.getJsonMapper().readValue(jpaDesc.getArguments(), DockerExecutionArguments.class);
		}
		catch ( Exception e ) {
			String msg = String.format("Failed to read DockerExecutionArguments from JpaInstanceDescriptor: "
										+ "args=%s, cause=%s", jpaDesc.getArguments(), e);
			throw new InternalException(msg);
		}
	}

	@Override
	protected void adaptInstanceDescriptor(JpaInstanceDescriptor desc) {
		String id = desc.getInstanceId();
		try ( DockerClient docker = newDockerClient() ) {
			ContainerInfo info = docker.inspectContainer(id);
			if ( info.state().running() ) {
				// 매니저가 모르는(재시작 전부터 돌던) 컨테이너가 실행 중이면 RUNNING으로 본다. map과
				// desc에 같은 상태를 넣는다.
				MDTInstanceStatus status = getContainerStatus(id);
				if ( status == MDTInstanceStatus.STOPPED || status == MDTInstanceStatus.FAILED ) {
					status = MDTInstanceStatus.RUNNING;
					putContainerStatus(id, status);
				}
				
				desc.setStatus(status);
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
			if ( getContainerStatus(id) != MDTInstanceStatus.STARTING ) {
				putContainerStatus(id, MDTInstanceStatus.STOPPED);
			}
			desc.setStatus(getContainerStatus(id));
			desc.setBaseEndpoint(null);
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("Failed to inspect the container of MDTInstance: id=" + id, e);
		}
	}

	@Override
	protected DockerInstance toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException {
		return new DockerInstance(this, descriptor);
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
}
