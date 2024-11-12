package mdt.instance.docker;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.ListContainersParam;
import org.mandas.docker.client.DockerClient.RemoveContainerParam;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.Container;
import org.mandas.docker.client.messages.ContainerConfig;
import org.mandas.docker.client.messages.ContainerCreation;
import org.mandas.docker.client.messages.ContainerInfo;
import org.mandas.docker.client.messages.HostConfig;
import org.mandas.docker.client.messages.PortBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import utils.InternalException;
import utils.func.FOption;
import utils.func.Unchecked;
import utils.stream.FStream;

import mdt.Globals;
import mdt.instance.JpaInstance;
import mdt.instance.jar.JarInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.service.MDTInstance;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerInstance extends JpaInstance implements MDTInstance {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstance.class);
	private static final int SECONDS_TO_WAIT_BEFORE_KILLING = 5;
	private static final String LABEL_NAME_MDT_TWIN_ID = "mdt-twin-id";
	
	private final DockerExecutionArguments m_execArgs;
	
	DockerInstance(DockerInstanceManager manager, JpaInstanceDescriptor desc) {
		super(manager, desc);
		
		m_execArgs = manager.getExecutionArguments(desc);
		
		setLogger(s_logger);
	}

	@Override
	protected void uninitialize() {
		DockerInstanceManager instManager = getInstanceManager();
		
		try ( DockerClient docker = instManager.newDockerClient() ) {
			// Docker container가 존재하는 경우 종료시키고 제거한다.
			getInstanceContainer(docker, getId()).ifPresent(cont -> stopAndRemoveContainer(docker, cont.id()));
			
			DockerUtils.removeInstanceImage(docker, getId());
		}
		catch ( Exception e ) {
			s_logger.error("Failed to remove InstanceImage: id={}, cause={}", getId(), e);
		}
		
		// Harbor에 등록된 repository를 삭제한다.
		try ( MDTHarborClient harbor = ((DockerInstanceManager)m_manager).newHarborClient() ) {
			String repoName = String.format("mdt-twin-%s", getId()).toLowerCase();
			harbor.removeInstanceImage(repoName);
		}
	}
	
	@Override
	public MDTInstanceStatus getStatus() {
		return getInstanceManager().getInstanceStatus(getId());
	}

	@Override
	public String getBaseEndpoint() {
		return getInstanceManager().getInstanceServiceEndpoint(getId());
	}

	@Override
	public void startAsync() {
		// TODO: 현재 구현에서는 docker container를 시작시키고 바로 반환하지만
		// 실제로는 container를 시작시킨 후, faaast-service가 구동되어 생성하는 로그를
		// monitoring하여 실제로 시작 작업이 완료될 때까지 대기하여야 함.
		// 'Jar' 기반의 MDTInstance의 경우는 이렇게 구현되었기 때문에 docker instance의 경우도
		// 이렇게 구현하는 것이 필요함.
		
		try ( DockerClient docker = newDockerClient() ) {
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STARTING(getId()));
			
			// 기존 docker container가 존재하는 경우에는 이를 먼저 삭제한다.
			getInstanceContainer(docker, getId())
						.ifPresent(cont -> stopAndRemoveContainer(docker, cont.id()));

			// Docker container를 생성한다.
			String containerId = createInstanceContainer(docker, getId(), m_execArgs.getImageRepoName(),
														m_execArgs.getFaaastPort());
			
			try {
				// 생성한 docker container를 시작시킨다.
				int svcPort = startInstanceContainer(docker, containerId);

				// container가 시작되면 할당된 port을 확인해 MDTInstance의 endpoint를 설정한다.
				String svcEndpoint = getInstanceManager().toServiceEndpoint(svcPort);
				
				Globals.EVENT_BUS.post(InstanceStatusChangeEvent.RUNNING(getId(), svcEndpoint));
			}
			catch ( Exception e ) {
				docker.removeContainer(containerId);
				throw new MDTInstanceManagerException("Failed to start DockerInstance: id=" + getId(), e);
			}
		}
		catch ( MDTInstanceManagerException e ) {
			throw e;
		}
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("Failed to start DockerInstance: id=" + getId(), e);
		}
	}
	
	@Override
	public void stopAsync() {
		try ( DockerClient docker = newDockerClient() ) {
			Container cont = getInstanceContainer(docker, getId())
								.getOrThrow(() -> new MDTInstanceManagerException("Failed to find docker container "
																				+ "for MDTInstance: id=" + getId()));

			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPING(getId()));
			stopAndRemoveContainer(docker, cont.id());
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPED(getId()));
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("Failed to stop the MDTInstance: id=" + getId(), e);
		}
	}
	
	public DockerInstanceManager getInstanceManager() {
		return (DockerInstanceManager)m_manager;
	}
	
	private DockerClient newDockerClient() {
		return getInstanceManager().newDockerClient();
	}
	
	//
	//	Docker management
	//

	private static final ListContainersParam ALL_CONTAINERS = ListContainersParam.allContainers(true);
	private FOption<Container> getInstanceContainer(DockerClient docker, String instId)
		throws DockerException, InterruptedException {
		return FStream.from(docker.listContainers(ALL_CONTAINERS))
						.findFirst(cont -> DockerUtils.hasLabel(cont.labels(), LABEL_NAME_MDT_TWIN_ID, instId));
	}

	private String createInstanceContainer(DockerClient docker, String instanceId, String imageId, int svcPort)
		throws DockerException, InterruptedException {
		// 443 port binding
		Map<String,List<PortBinding>> portBindings = Maps.newHashMap();
		if ( svcPort > 0 ) {
			portBindings.put("443/tcp", List.of(PortBinding.of("0.0.0.0", svcPort)));
		}
		else {
			portBindings.put("443/tcp", Arrays.asList(PortBinding.randomPort("0.0.0.0")));
		}
		
		HostConfig hostConf = HostConfig.builder()
										.portBindings(portBindings)
										.build();
		
		// MDTInstance용 label을 추가한다.
		Map<String,String> labels = Maps.newHashMap();
		labels.put(LABEL_NAME_MDT_TWIN_ID, instanceId);
		
		// 내부적으로 443 포트를 사용하는 MDTInstance용 container를 생성한다.
		ContainerConfig containerConf = ContainerConfig.builder()
														.hostConfig(hostConf)
														.image(imageId)
//														.exposedPorts("443")
														.labels(labels)
														.cmd("java", "-jar", "faaast-starter-all.jar")
														.build();
		ContainerCreation creation = docker.createContainer(containerConf, instanceId);
		return creation.id();
	}
	
	private int startInstanceContainer(DockerClient docker, String containerId)
		throws DockerException, InterruptedException {
		docker.startContainer(containerId);

		// container가 시작되면 할당된 port을 확인해 MDTInstance의 endpoint를 설정한다.
		ContainerInfo info = docker.inspectContainer(containerId);
		return getServicePort(info);
	}
	
	private void stopAndRemoveContainer(DockerClient docker, String contId) {
		// 검색된 container를 중지시킨다.
		Unchecked.runOrIgnore(() -> docker.stopContainer(contId, SECONDS_TO_WAIT_BEFORE_KILLING));
		
		// 검색된 container를 kill 시킨다.
		Unchecked.runOrIgnore(() -> docker.killContainer(contId));
		
		// 검색된 container를 제거한다.
		Unchecked.runOrIgnore(() -> docker.removeContainer(contId, RemoveContainerParam.forceKill()));
	}
	
	private int getServicePort(ContainerInfo contInfo) {
		List<PortBinding> hostPorts = contInfo.networkSettings().ports().get("443/tcp");
		if ( hostPorts == null || hostPorts.size() == 0 ) {
			throw new InternalException("Cannot find external port");
		}
		
		return Integer.parseInt(hostPorts.get(0).hostPort());
	}
}
