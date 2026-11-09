package mdt.instance.external;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.Service;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import utils.Throwables;
import utils.func.Unchecked;
import utils.io.FileUtils;
import utils.jpa.JpaProcessor;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.MqttConfiguration;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.ModelValidationException;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.repository.Repositories;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Component
@ConditionalOnProperty(prefix="instance-manager", name = "type", havingValue = "external")
public class ExternalInstanceManager extends AbstractJpaInstanceManager<ExternalInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(ExternalInstanceManager.class);
	
	private final ExternalConfiguration m_extConfig;

	public ExternalInstanceManager(MDTInstanceManagerConfiguration mgrConf,
									ExternalConfiguration extConfig,
									Repositories repos,
									MqttConfiguration mqttConf) throws Exception {
		super(mgrConf, repos, mqttConf);
		setLogger(s_logger);
		
		// 설정이 잘못되었으면 health check 스레드 안에서 NPE·IAE로 조용히 멈추지 않도록 기동 시 실패시킨다.
		// 2026-11-05 이전에는 이 검사가 정의만 되어 있고 호출되지 않았다.
		checkExternalConfigurationValidity(extConfig);
		m_extConfig = extConfig;
		
		// 등록된 모든 InstanceDescriptor의 상태를 STOPPED로 변경
		repos.instances().resetAll();
		
		m_healthCheckService.startAsync();
	}

	/**
	 * health check 서비스를 멈춘 뒤 기본 자원을 정리한다.
	 */
	@Override
	public void shutdown() {
		try {
			m_healthCheckService.stopAsync().awaitTerminated(SHUTDOWN_TIMEOUT);
		}
		catch ( Exception e ) {
			getLogger().warn("failed to stop the health-check service: cause={}", "" + e);
		}
		super.shutdown();
	}

	@Override
	public MDTInstance addInstance(String id, int port, File bundleDir)
		throws ModelValidationException, IOException, MDTInstanceManagerException {
		checkNotRegistered(id);
		ExternalInstanceArguments args = new ExternalInstanceArguments();
		try {
			File modelFile = FileUtils.path(bundleDir, MODEL_FILE_NAME);
			Environment env = AASUtils.readEnvironment(modelFile);
			String arguments = m_mapper.writeValueAsString(args);
			
			JpaInstanceDescriptor desc = addInstanceDescriptor(id, env, arguments);
			if ( getLogger().isInfoEnabled() ) {
				getLogger().info("added ExternalInstance: id={}", desc.getInstanceId());
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
	
	/**
	 * 외부 인스턴스를 등록한다(상태 RUNNING, 주어진 endpoint, 마지막 갱신 시각 기록).
	 * <p>
	 * {@link #unregister(String)}와 같이 {@code updateInstanceDescriptor}로 명시적으로 저장한다. 2026-11-05 이전에는
	 * Spring 프록시의 {@code @Transactional}에만 기대어, 프록시를 거치지 않으면 저장되지 않았다.
	 *
	 * @param id				인스턴스 id.
	 * @param serviceEndpoint	인스턴스의 서비스 endpoint.
	 * @return	등록된 인스턴스.
	 * @throws ResourceNotFoundException	해당 인스턴스가 없는 경우.
	 */
	public ExternalInstance register(String id, String serviceEndpoint) throws ResourceNotFoundException {
		updateInstanceDescriptor(id, desc -> {
			try {
				// update the last modified time
				ExternalInstanceArguments args = m_mapper.readValue(desc.getArguments(),
																	ExternalInstanceArguments.class);
				args.setLastModified(System.currentTimeMillis());
				desc.setArguments(m_mapper.writeValueAsString(args));
			}
			catch ( Exception e ) {
				// 실행 인자가 없거나 손상되었으면 새로 만든다. 그러지 않으면 마지막 갱신 시각이 남지 않아 다음
				// health check에서 곧바로 STOPPED가 된다(2026-11-05 이전에는 빈 catch로 삼켰다).
				getLogger().warn("rebuild the broken execution arguments: id={}, cause={}", id, "" + e);
				ExternalInstanceArguments args = new ExternalInstanceArguments();
				args.setLastModified(System.currentTimeMillis());
				desc.setArguments(Unchecked.getOrThrowSneakily(() -> m_mapper.writeValueAsString(args)));
			}
			desc.setStatus(MDTInstanceStatus.RUNNING);
			desc.setBaseEndpoint(serviceEndpoint);
		});
		postStatusChanged(id, MDTInstanceStatus.RUNNING, serviceEndpoint);
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("Registered MDTInstance: id={}, endpoint={}", id, serviceEndpoint);
		}
		
		return toInstance(getInstanceDescriptor(id));
	}
	
	/**
	 * 외부 인스턴스의 등록을 해제한다(상태 STOPPED, endpoint 없음).
	 * <p>
	 * 2026-11-05 이전에는 트랜잭션 밖에서 조회한 엔티티의 필드만 바꾸고 저장하지 않아, DB에 반영되지 않았다
	 * ({@code DELETE /registry/{id}}가 아무것도 하지 않았다).
	 *
	 * @param id	인스턴스 id.
	 * @throws ResourceNotFoundException	해당 인스턴스가 없는 경우.
	 */
	public void unregister(String id) throws ResourceNotFoundException {
		transitionStatus(id, MDTInstanceStatus.STOPPED, null);
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("Unregistered MDTInstance: id={}", id);
		}
	}

	@Override
	protected void adaptInstanceDescriptor(JpaInstanceDescriptor desc) { }

	@Override
	protected ExternalInstance toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException {
		return new ExternalInstance(this, descriptor);
	}
	
	static void checkExternalConfigurationValidity(ExternalConfiguration config) {
		Preconditions.checkState(config.getConnectionTimeout() != null,
								"external.connectionTimeout is missing");
		if ( config.getConnectionTimeout().toMillis() <= 1000 ) {
			throw new IllegalArgumentException("external.connectionTimeout must be greater than 1 sec: "
												+ config.getConnectionTimeout());
        }
		
		Preconditions.checkState(config.getCheckInterval() != null, "external.checkInterval is missing");
		if ( config.getCheckInterval().isNegative() || config.getCheckInterval().isZero() ) {
			throw new IllegalArgumentException("external.checkInterval must be positive: " + config.getCheckInterval());
		}
	}
	
	private final Service m_healthCheckService = new AbstractScheduledService() {
		// 예외를 밖으로 던지면 Guava 서비스가 FAILED가 되어 health check가 영구히 멈추므로, 한 번의 실패는 로그만
		// 남기고 다음 주기에 다시 한다. 2026-11-05 이전에는 예외가 그대로 나가 로그 없이 멈췄다. 트랜잭션은
		// JpaProcessor가 연다(내부 클래스 메소드의 @Transactional은 Spring 프록시를 거치지 않아 효과가 없어 지웠다).
		@Override
		protected void runOneIteration() {
			try {
				JpaProcessor proc = new JpaProcessor(m_repos.entityManagerFactory());
				proc.run(em -> purgeInactiveInstance(em));
			}
			catch ( Exception e ) {
				getLogger().error("health check of external MDTInstances failed; retry at the next interval", e);
			}
		}

		// 밀리초 단위로 스케줄한다(2026-11-05 이전에는 초 단위로 잘라 1초 미만이면 0이 되어 실패했다).
		@Override
		protected Scheduler scheduler() {
			long intervalMillis = m_extConfig.getCheckInterval().toMillis();
			return Scheduler.newFixedRateSchedule(0, intervalMillis, TimeUnit.MILLISECONDS);
		}
		
		private void purgeInactiveInstance(EntityManager em) {
			if ( getLogger().isDebugEnabled() ) {
				getLogger().debug("Starting MDTInstances' health check, timeout={}", m_extConfig.getConnectionTimeout());
			}
			
			final long now = System.currentTimeMillis();
			final Duration timeout = m_extConfig.getConnectionTimeout();
			
			List<JpaInstanceDescriptor> runningInstances
						= em.createQuery("select desc from JpaInstanceDescriptor desc where desc.status = 'RUNNING'",
											JpaInstanceDescriptor.class).getResultList();
			for ( JpaInstanceDescriptor desc: runningInstances ) {
				try {
					ExternalInstanceArguments args = m_mapper.readValue(desc.getArguments(),
																		ExternalInstanceArguments.class);
					Duration idleDuration = Duration.ofMillis(now - args.getLastModified());
					if ( idleDuration.compareTo(timeout) >= 0 ) {
						desc.setStatus(MDTInstanceStatus.STOPPED);
						desc.setBaseEndpoint(null);
						postStatusChanged(desc.getInstanceId(), MDTInstanceStatus.STOPPED, null);
						if ( getLogger().isInfoEnabled() ) {
							getLogger().info("MDTInstance is stopped due to inactivity: id={}", desc.getInstanceId());
						}
					}
				}
				catch ( Exception e ) {
					getLogger().error("Failed to check health of MDTInstance: id=" + desc.getInstanceId(), e);
				}
			}
		}
	};
}
