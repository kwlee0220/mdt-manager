package mdt.instance.external;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.Service;

import utils.Throwables;
import utils.io.FileUtils;
import utils.jpa.JpaSession;
import utils.stream.FStream;

import mdt.MDTConfigurations;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.model.AASUtils;
import mdt.model.ModelValidationException;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ExternalInstanceManager extends AbstractJpaInstanceManager<ExternalInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(ExternalInstanceManager.class);
	
	private final ExternalConfiguration m_extConfig;

	public ExternalInstanceManager(MDTConfigurations configs) throws Exception {
		super(configs);
		setLogger(s_logger);
		
		m_extConfig = configs.getExternalConfig();
		
		// 등록된 모든 InstanceDescriptor의 상태를 STOPPED로 변경
    	try ( JpaSession session = allocateJpaSession() ) {
			JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
			instDescMgr.getInstanceDescriptorAll()
						.forEach(desc -> {
							desc.setStatus(MDTInstanceStatus.STOPPED);
							desc.setBaseEndpoint(null);
						});
    	}
		
		m_healthCheckService.startAsync();
	}

	@Override
	public MDTInstance addInstance(String id, File bundleDir)
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
	
	public ExternalInstance register(String id, String serviceEndpoint) throws ResourceNotFoundException {
		JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
		JpaInstanceDescriptor desc = instDescMgr.getInstanceDescriptor(id);
		if ( desc == null ) {
			throw new ResourceNotFoundException("MDTInstance", "id=" + id);
		}

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
		JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
		JpaInstanceDescriptor desc = instDescMgr.getInstanceDescriptor(id);
		if ( desc == null ) {
            throw new ResourceNotFoundException("MDTInstance", "id=" + id);
        }
		
		desc.setStatus(MDTInstanceStatus.STOPPED);
		desc.setBaseEndpoint(null);
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("Unregistered MDTInstance: id={}", id);
		}
	}

	@Override
	protected void updateInstanceDescriptor(JpaInstanceDescriptor desc) { }

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
		protected void runOneIteration() throws Exception {
			if ( getLogger().isDebugEnabled() ) {
				getLogger().debug("Starting MDTInstances' health check, timeout={}", m_extConfig.getConnectionTimeout());
			}
			
			final long now = System.currentTimeMillis();
			final Duration timeout = m_extConfig.getConnectionTimeout();
			
			try ( JpaSession session = allocateJpaSession() ) {
				JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
				FStream.from(instDescMgr.getInstanceDescriptorAll())
						.filter(desc -> desc.getStatus() == MDTInstanceStatus.RUNNING)
						.forEach(desc -> {
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
						});
			}
		}

		@Override
		protected Scheduler scheduler() {
			long intervalSecs = m_extConfig.getCheckInterval().toSeconds();
			return Scheduler.newFixedRateSchedule(0, intervalSecs, TimeUnit.SECONDS);
		}
	};
}
