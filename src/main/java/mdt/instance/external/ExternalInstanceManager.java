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

import utils.Throwables;
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

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;


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
		
		m_extConfig = extConfig;
		
		// 등록된 모든 InstanceDescriptor의 상태를 STOPPED로 변경
		repos.instances().resetAll();
		
		m_healthCheckService.startAsync();
	}

	@Override
	public MDTInstance addInstance(String id, int port, File bundleDir)
		throws ModelValidationException, IOException, MDTInstanceManagerException {
		ExternalInstanceArguments args = new ExternalInstanceArguments();
		try {
			File modelFile = FileUtils.path(bundleDir, MODEL_FILE_NAME);
			Environment env = AASUtils.readEnvironment(modelFile);
			String arguments = m_mapper.writeValueAsString(args);
			
			JpaInstanceDescriptor desc = addInstanceDescriptor(id, env, arguments);
			if ( getLogger().isInfoEnabled() ) {
				getLogger().info("added ExternalInstance: id={}", desc.getId());
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
	
	@Transactional
	public ExternalInstance register(String id, String serviceEndpoint) throws ResourceNotFoundException {
		JpaInstanceDescriptor desc = getInstanceDescriptor(id);

		try {
			// update the last modified time
			ExternalInstanceArguments args = m_mapper.readValue(desc.getArguments(),
																ExternalInstanceArguments.class);
			args.setLastModified(System.currentTimeMillis());
			desc.setArguments(m_mapper.writeValueAsString(args));
		}
		catch ( Exception e ) { }

		desc.setStatus(MDTInstanceStatus.RUNNING);
		desc.setBaseEndpoint(serviceEndpoint);
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("Registered MDTInstance: id={}, endpoint={}", id, serviceEndpoint);
		}
		
		return toInstance(desc);
	}
	
	public void unregister(String id) {
		JpaInstanceDescriptor desc = getInstanceDescriptor(id);
		
		desc.setStatus(MDTInstanceStatus.STOPPED);
		desc.setBaseEndpoint(null);
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
	
	private void checkExternalConfigurationValidity(ExternalConfiguration config) {
		Preconditions.checkState(config.getConnectionTimeout() != null,
								"ExternalConfiguration.ConnectionTimeout is missing");
		if ( config.getConnectionTimeout().toMillis() <= 1000 ) {
			throw new IllegalArgumentException("ExternalConfiguration.ConnectionTimeout must be greater than 1 sec");
        }
		
		Preconditions.checkState(config.getCheckInterval() != null, "ExternalConfiguration.checkInterval is missing");
	}
	
	private final Service m_healthCheckService = new AbstractScheduledService() {
		@Override
		@Transactional
		protected void runOneIteration() throws Exception {
			JpaProcessor proc = new JpaProcessor(m_repos.entityManagerFactory());
			proc.run(em -> purgeInactiveInstance(em));
		}

		@Override
		protected Scheduler scheduler() {
			long intervalSecs = m_extConfig.getCheckInterval().toSeconds();
			return Scheduler.newFixedRateSchedule(0, intervalSecs, TimeUnit.SECONDS);
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
						if ( getLogger().isInfoEnabled() ) {
							getLogger().info("MDTInstance is stopped due to inactivity: id={}", desc.getId());
						}
					}
				}
				catch ( Exception e ) {
					getLogger().error("Failed to check health of MDTInstance: id=" + desc.getId(), e);
				}
			}
		}
	};
}
