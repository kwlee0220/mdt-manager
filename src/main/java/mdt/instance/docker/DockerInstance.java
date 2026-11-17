package mdt.instance.docker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
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
import org.mandas.docker.client.messages.HostConfig.Bind;
import org.mandas.docker.client.messages.PortBinding;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import utils.InternalException;
import utils.KeyValue;
import utils.async.PeriodicPoller;
import utils.async.Executions;
import utils.func.FOption;
import utils.func.Unchecked;
import utils.io.LogTailer;
import utils.stream.FStream;

import mdt.exector.jar.SentinelFinder;
import mdt.instance.JpaInstance;
import mdt.instance.jar.JarInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.MDTModelSerDe;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerInstance extends JpaInstance implements MDTInstance {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstance.class);
	private static final int SECONDS_TO_WAIT_BEFORE_KILLING = 5;
	
	private final DockerExecutionArguments m_execArgs;
	
	DockerInstance(DockerInstanceManager manager, JpaInstanceDescriptor desc) {
		super(manager, desc);

		try {
			m_execArgs = MDTModelSerDe.getJsonMapper().readValue(desc.getArguments(), DockerExecutionArguments.class);
		}
		catch ( Exception e ) {
			String msg = String.format("Failed to read DockerExecutionArguments from JpaInstanceDescriptor: "
										+ "args=%s, cause=%s", desc.getArguments(), e);
			throw new MDTInstanceManagerException(msg);
		}
		
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
	}
	
	@Override
	public void startAsync() throws InterruptedException {
		// TODO: 현재 구현에서는 docker container를 시작시키고 바로 반환하지만
		// 실제로는 container를 시작시킨 후, faaast-service가 구동되어 생성하는 로그를
		// monitoring하여 실제로 시작 작업이 완료될 때까지 대기하여야 함.
		// 'Jar' 기반의 MDTInstance의 경우는 이렇게 구현되었기 때문에 docker instance의 경우도
		// 이렇게 구현하는 것이 필요함.
		DockerInstanceManager instManager = getInstanceManager();
		instManager.putContainerStatus(getId(), MDTInstanceStatus.STARTING);
		
		try ( DockerClient docker = newDockerClient() ) {
			instManager.transitionStatus(getId(), MDTInstanceStatus.STARTING, null);
			
			// 기존 docker container가 존재하는 경우에는 이를 먼저 삭제한다.
			getInstanceContainer(docker, getId())
						.ifPresent(cont -> stopAndRemoveContainer(docker, cont.id()));
			// 이전 실행의 로그에 남은 시작 완료 문구로 곧바로 RUNNING이 되지 않도록 로그를 지운다.
			Files.deleteIfExists(getAppLogFile().toPath());

			// Docker container를 생성한다.
			String containerId = createInstanceContainer(docker, getId(), m_execArgs.getImageRepoName(),
															m_execArgs.getPort());
			
			try {
				// 생성한 docker container를 시작시킨다.
				int svcPort = startInstanceContainer(docker, containerId);
				
				Executions.toExecution(() -> {
					DockerConfiguration conf = instManager.getDockerConfiguration();
					MDTInstanceStatus status = waitUntilStarted(getId(), containerId, Duration.ofSeconds(1),
																conf.getStartTimeout());
					instManager.putContainerStatus(getId(), status);
					
					// 시작에 성공한 경우에만 RUNNING을 알린다.
					if ( status == MDTInstanceStatus.RUNNING ) {
						// container가 시작되면 할당된 port을 확인해 MDTInstance의 endpoint를 설정한다.
						String svcEndpoint = instManager.toServiceEndpoint(svcPort);
						instManager.transitionStatus(getId(), MDTInstanceStatus.RUNNING, svcEndpoint);
					}
					else {
						instManager.transitionStatus(getId(), status, null);
					}
				}).start();
			}
			catch ( Exception e ) {
				String msg = String.format("Failed to start DockerContainer: id=%s, cause=%s", getId(), e);
				getLogger().error(msg);
				
				// 정리 실패가 원래 오류를 가리지 않도록 한다.
				Unchecked.runOrIgnore(() -> docker.removeContainer(containerId));
				markFailed();
				throw new MDTInstanceManagerException(msg, e);
			}
		}
		catch ( MDTInstanceManagerException | InterruptedException e ) {
			markFailed();
			throw e;
		}
		catch ( DockerException | IOException e ) {
			markFailed();
			throw new MDTInstanceManagerException("Failed to start DockerInstance: id=" + getId(), e);
		}
	}
	
	/**
	 * {@inheritDoc}
	 * <p>
	 * 컨테이너가 이미 없으면 STOPPED로 본다. docker 호출이 실패하면 FAILED로 알린다.
	 */
	@Override
	public void stopAsync() {
		DockerInstanceManager instManager = getInstanceManager();
		instManager.putContainerStatus(getId(), MDTInstanceStatus.STOPPING);
		
		try ( DockerClient docker = newDockerClient() ) {
			FOption<Container> cont = getInstanceContainer(docker, getId());
			if ( cont.isPresent() ) {
				instManager.transitionStatus(getId(), MDTInstanceStatus.STOPPING, null);
				stopAndRemoveContainer(docker, cont.get().id());
			}
			else {
				getLogger().info("no docker container to stop; regard as stopped: id={}", getId());
			}
			
			instManager.putContainerStatus(getId(), MDTInstanceStatus.STOPPED);
			instManager.transitionStatus(getId(), MDTInstanceStatus.STOPPED, null);
		}
		catch ( Exception e ) {
			markFailed();
			throw new MDTInstanceManagerException("Failed to stop the MDTInstance: id=" + getId(), e);
		}
	}
	
	private boolean isContainerRunning(String containerId) {
		try ( DockerClient docker = newDockerClient() ) {
			return docker.inspectContainer(containerId).state().running();
		}
		catch ( Exception e ) {
			// 확인할 수 없으면 계속 기다린다(제한 시간이 끝을 정한다).
			return true;
		}
	}
	
	private void markFailed() {
		getInstanceManager().putContainerStatus(getId(), MDTInstanceStatus.FAILED);
		getInstanceManager().transitionStatus(getId(), MDTInstanceStatus.FAILED, null);
	}
	
	private File getAppLogFile() {
		return utils.io.FileUtils.path(getInstanceManager().getInstanceHomeDir(getId()), "logs", "app.log");
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
						.findFirst(cont ->
							DockerUtils.hasLabel(cont.labels(), DockerUtils.LABEL_NAME_MDT_TWIN_ID, instId)
						);
	}

	// hostPort가 null이면 docker가 빈 호스트 포트를 고른다(컨테이너 안은 443).
	private String createInstanceContainer(DockerClient docker, String instanceId, String imageId,
											@Nullable Integer hostPort)
		throws DockerException, InterruptedException {
		// 443 port binding
		Map<String,List<PortBinding>> portBindings = Maps.newHashMap();
		if ( hostPort != null ) {
			portBindings.put("443/tcp", List.of(PortBinding.of("0.0.0.0", hostPort)));
		}
		else {
			portBindings.put("443/tcp", Arrays.asList(PortBinding.randomPort("0.0.0.0")));
		}
		
		// Directory binding
		Bind modelBind = Bind.builder()
							.from(getInstanceManager().getInstanceHomeDir(getId()).getAbsolutePath())
							.to("/faaast/model")
							.build();
		
		HostConfig hostConf = HostConfig.builder()
										.portBindings(portBindings)
										.binds(modelBind)
										.build();
		
		// MDTInstance용 label을 추가한다.
		Map<String,String> labels = Maps.newHashMap();
		labels.put(DockerUtils.LABEL_NAME_MDT_TWIN_ID, instanceId);
		
		// 내부적으로 443 포트를 사용하는 MDTInstance용 container를 생성한다.
		ContainerConfig containerConf = ContainerConfig.builder()
												.hostConfig(hostConf)
												.image(imageId)
//														.exposedPorts("443")
												.labels(labels)
												.cmd("java", "-jar", "/faaast/faaast-starter-all.jar", "-v")
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
	
	private int getServicePort(ContainerInfo contInfo) {
		List<PortBinding> hostPorts = contInfo.networkSettings().ports().get("443/tcp");
		if ( hostPorts == null || hostPorts.size() == 0 ) {
			throw new InternalException("Cannot find external port");
		}
		
		return Integer.parseInt(hostPorts.get(0).hostPort());
	}
	
	private void stopAndRemoveContainer(DockerClient docker, String contId) {
		// 검색된 container를 중지시킨다.
		Unchecked.runOrIgnore(() -> docker.stopContainer(contId, SECONDS_TO_WAIT_BEFORE_KILLING));
		
		// 검색된 container를 kill 시킨다.
		Unchecked.runOrIgnore(() -> docker.killContainer(contId));
		
		// 검색된 container를 제거한다.
		Unchecked.runOrIgnore(() -> docker.removeContainer(contId, RemoveContainerParam.forceKill()));
	}
	
	private MDTInstanceStatus waitUntilStarted(String instId, String containerId, Duration sampleInterval,
												Duration timeout) {
		DockerInstanceManager instManager = getInstanceManager();
		
		File logFile = getAppLogFile();
		
		// 시작 완료 문구만 찾고, 컨테이너가 끝나면 곧바로 실패로 본다. "ERROR" 같은 실패 마커는
		// 쓰지 않는다(무해한 ERROR 로그도 있다).
		List<String> sentinels = Arrays.asList("HTTP endpoint available on port");
		SentinelFinder finder = new SentinelFinder(sentinels, () -> !isContainerRunning(containerId));
		
		LogTailer tailer = LogTailer.builder()
									.file(logFile)
									.startAtBeginning(true)
									.sampleInterval(sampleInterval)
									.timeout(timeout)
									.listener(finder)
									.build();
		
		// 로그 파일이 생성될 때까지 대기한다.
		PeriodicPoller<Boolean> poller = PeriodicPoller.pollUntil(() -> logFile.exists())
												                .fixedRate(Duration.ofMillis(300))
										                        .timeout(instManager.getDockerConfiguration().getLogFileTimeout())
										                        .build();
		
		try {
			poller.run();
			tailer.run();
			
			final KeyValue<Integer,String> sentinel = finder.getSentinel();
			if ( sentinel != null ) {
				return MDTInstanceStatus.RUNNING;
			}
			else {
				// 컨테이너가 시작 완료 문구 없이 끝났다.
		    	s_logger.info("failed to start an MDTInstance (container exited): {}", instId);
				return MDTInstanceStatus.FAILED;
			}
		}
		catch ( Exception e ) {
			instManager.putContainerStatus(instId, MDTInstanceStatus.FAILED);
			
	    	if ( s_logger.isInfoEnabled() ) {
	    		s_logger.info("failed to start an MDTInstance: {}, cause={}", instId, e);
	    	}
	    	
			return MDTInstanceStatus.FAILED;
		}
	}
}
