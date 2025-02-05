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
import utils.func.FOption;
import utils.io.FileUtils;
import utils.stream.FStream;

import mdt.MDTConfiguration;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.InstanceDescriptorManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.instance.jpa.JpaProcessor;
import mdt.model.AASUtils;
import mdt.model.ModelValidationException;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.InstanceDescriptor;
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
	private JpaProcessor m_jpaProcessor;
	
	public ExternalInstanceManager(MDTConfiguration conf) {
		super(conf);
		setLogger(s_logger);
		
		m_extConfig = conf.getExternalConfiguration();
	}

	@Override
	public void initialize(InstanceDescriptorManager instDescManager, JpaProcessor jpaProcessor)
		throws MDTInstanceManagerException {
		Preconditions.checkArgument(instDescManager instanceof JpaInstanceDescriptorManager,
									"JpaInstanceDescriptorManager required");
		JpaInstanceDescriptorManager jpaInstDescManager = (JpaInstanceDescriptorManager)instDescManager;
		Preconditions.checkArgument(jpaInstDescManager.getEntityManager() != null, "EntityManager is not assigned");
		checkExternalConfigurationValidity(m_extConfig);
		
		super.initialize(instDescManager, jpaProcessor);
		
		for ( JpaInstanceDescriptor desc: instDescManager.getInstanceDescriptorAll() ) {
			desc.setStatus(MDTInstanceStatus.STOPPED);
			desc.setBaseEndpoint(null);
		}
		
		m_jpaProcessor = jpaProcessor;
		m_healthCheckService.startAsync();
	}

	@Override
	public MDTInstance addInstance(String id, int faaastPort, File bundleDir)
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
		JpaInstanceDescriptorManager instDescMgr = getInstanceDescriptorManager();
		JpaInstanceDescriptor desc = instDescMgr.getInstanceDescriptor(id);
		if ( desc == null ) {
			throw new ResourceNotFoundException("MDTInstance", "id=" + id);
		}

		try {
			ExternalInstanceArguments args = m_mapper.readValue(desc.getArguments(), ExternalInstanceArguments.class);
			args.setLastModified(System.currentTimeMillis());
			desc.setArguments(m_mapper.writeValueAsString(args));
		}
		catch ( Exception e ) { }
		
		if ( desc.getStatus() != MDTInstanceStatus.RUNNING ) {
			desc.setBaseEndpoint(serviceEndpoint);
			desc.setStatus(MDTInstanceStatus.RUNNING);
			
			if ( getLogger().isInfoEnabled() ) {
				getLogger().info("Registered MDTInstance: id={}, endpoint={}", id, serviceEndpoint);
			}
		}
		instDescMgr.updateInstanceDescriptor(desc);
		
		return toInstance(desc);
	}
	
	public void unregister(String id) {
		JpaInstanceDescriptorManager instDescMgr = getInstanceDescriptorManager();
		JpaInstanceDescriptor desc = instDescMgr.getInstanceDescriptor(id);
		if ( desc == null ) {
			throw new ResourceNotFoundException("MDTInstance", "id=" + id);
		}
		
		unregister(instDescMgr, desc);
	}

	@Override
	public MDTInstanceStatus getInstanceStatus(String id) {
		return FOption.mapOrElse(getInstanceDescriptorManager().getInstanceDescriptor(id),
								InstanceDescriptor::getStatus, MDTInstanceStatus.REMOVED);
	}

	@Override
	public String getInstanceServiceEndpoint(String id) {
		return FOption.map(getInstanceDescriptorManager().getInstanceDescriptor(id),
							InstanceDescriptor::getBaseEndpoint);
	}

	@Override
	protected ExternalInstance toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException {
		return new ExternalInstance(this, descriptor);
	}
	
	private void unregister(JpaInstanceDescriptorManager instDescMgr, JpaInstanceDescriptor desc) {
		if ( desc.getStatus() != MDTInstanceStatus.STOPPED ) {
			desc.setBaseEndpoint(null);
			desc.setStatus(MDTInstanceStatus.STOPPED);
			instDescMgr.updateInstanceDescriptor(desc);

			if ( getLogger().isInfoEnabled() ) {
				getLogger().info("Unregistered MDTInstance: id={}", desc.getId());
			}
		}
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
			JpaInstanceDescriptorManager instDescMgr = getInstanceDescriptorManager();
			m_jpaProcessor.run(instDescMgr, () -> {
				FStream.from(instDescMgr.getInstanceDescriptorAll())
						.filter(desc -> desc.getStatus() == MDTInstanceStatus.RUNNING)
						.forEach(desc -> {
							try {
								ExternalInstanceArguments args = m_mapper.readValue(desc.getArguments(),
																					ExternalInstanceArguments.class);
								Duration idleDuration = Duration.ofMillis(now - args.getLastModified());
								if ( idleDuration.compareTo(timeout) >= 0 ) {
									unregister(instDescMgr, desc);
								}
							}
							catch ( Exception e ) {
								getLogger().error("Failed to check health of MDTInstance: id=" + desc.getId(), e);
							}
						});
			});
		}

		@Override
		protected Scheduler scheduler() {
			long intervalSecs = m_extConfig.getCheckInterval().toSeconds();
			return Scheduler.newFixedRateSchedule(0, intervalSecs, TimeUnit.SECONDS);
		}
	};
}
