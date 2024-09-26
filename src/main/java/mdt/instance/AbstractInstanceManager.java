package mdt.instance;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;

import utils.LoggerSettable;
import utils.func.FOption;
import utils.func.Try;
import utils.stream.FStream;

import mdt.Globals;
import mdt.MDTConfiguration;
import mdt.MDTConfiguration.MDTInstanceManagerConfiguration;
import mdt.MDTConfiguration.MqttConfiguration;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.instance.jpa.JpaInstanceDescriptorManager.InstanceDescriptorTransform;
import mdt.instance.jpa.JpaInstanceDescriptorManager.SearchCondition;
import mdt.instance.jpa.JpaModule;
import mdt.instance.jpa.JpaProcessor;
import mdt.model.AASUtils;
import mdt.model.InvalidResourceStatusException;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;
import mdt.model.ServiceFactory;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractInstanceManager<T extends AbstractInstance>
										implements MDTInstanceManagerProvider, JpaModule, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractInstanceManager.class);
	
	private ServiceFactory m_serviceFact;		// wired
	private JpaProcessor m_processor;

	private final ThreadLocal<EntityManager> m_emLocal = new ThreadLocal<>();
	private final MDTInstanceManagerConfiguration m_conf;
	private final String m_repositoryEndpointFormat;
//	private final File m_workspaceDir;
//	private final File m_homeDir;
//	private final File m_instancesDir;
	private final MqttClient m_mqttClient;
	
	protected final JsonMapper m_mapper = JsonMapper.builder().build();
	private Logger m_logger;
	
	abstract protected JpaInstanceDescriptor initializeInstance(JpaInstanceDescriptor desc);
	abstract protected T toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException;
	
	protected AbstractInstanceManager(MDTConfiguration conf) throws MDTInstanceManagerException {
		setLogger(s_logger);
		
		m_conf = conf.getMDTInstanceManagerConfiguration();
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("use MDTInstances root dir={}", m_conf.getInstancesDir());
		}
		
		String epFormat = m_conf.getRepositoryEndpointFormat();
		if ( epFormat == null ) {
			try {
				String host = InetAddress.getLocalHost().getHostAddress();
				epFormat = "https:" + host + ":%d/api/v3.0";
			}
			catch ( UnknownHostException e ) {
				throw new MDTInstanceManagerException("" + e);
			}
		}
		m_repositoryEndpointFormat = epFormat;

		Globals.EVENT_BUS.register(this);
		MqttConfiguration mqttConf = conf.getMqttConfiguration();
		if ( mqttConf.getEndpoint() != null && mqttConf.getClientId() != null ) {
			m_mqttClient = newConnectedMqttClient(mqttConf);
		}
		else {
			m_mqttClient = null;
		}
	}
	
	public void shutdown() { }
//	public void shutdown() {
//		if ( getLogger().isInfoEnabled() ) {
//			getLogger().info("Shutting down MDTInstanceManager...");
//		}
//		
//		List<AbstractInstance> stoppings = Lists.newArrayList();
//		for ( MDTInstance inst: getAllInstances() ) {
//			if ( inst.getStatus() == MDTInstanceStatus.RUNNING ) {
//				if ( getLogger().isInfoEnabled() ) {
//					getLogger().info("stopping: {}", inst);
//				}
//				stoppings.add((AbstractInstance)inst);
//				Try.run(() -> inst.stop(null, null));
//			}
//		}
//		Predicate<MDTInstanceStatus> whileStopping = status -> status == MDTInstanceStatus.STOPPING;
//		for ( AbstractInstance inst: stoppings ) {
//			try {
//				AbstractInstance stopping = inst.reload();
//				if ( stopping.getStatus() == MDTInstanceStatus.STOPPING ) {
//					StateChangePoller.pollWhile(() -> whileStopping.test(stopping.reload().getStatus()))
//									.interval(Duration.ofMillis(100))
//									.timeout(Duration.ofSeconds(2))
//									.build()
//									.run();
//				}
//			}
//			catch ( Exception e ) { }
//		}
//		if ( getLogger().isInfoEnabled() ) {
//			getLogger().info("stopped all MDtInstances");
//		}
//	}

	public ServiceFactory getServiceFactory() {
		return m_serviceFact;
	}
	@Autowired
	public void setServiceFactory(ServiceFactory fact) {
		m_serviceFact = fact;
	}

	@Autowired
	public void setEntityManagerFactory(EntityManagerFactory fact) {
		m_processor = new JpaProcessor(fact);
	}
	
	@Override
	public EntityManager getEntityManager() {
		return m_emLocal.get();
	}
	
	public void setEntityManager(EntityManager em) {
		m_emLocal.set(em);
	}
	
	public File getHomeDir() {
		return m_conf.getHomeDir();
	}
	
	public File getInstancesDir() {
		return m_conf.getInstancesDir();
	}
	
	public File getInstanceHomeDir(String id) {
		return new File(m_conf.getInstancesDir(), id);
	}

	@Override
	public T getInstance(String id) throws ResourceNotFoundException {
		Preconditions.checkNotNull(id);
		
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(m_emLocal.get());
		JpaInstanceDescriptor descriptor = instDescMgr.getInstanceDescriptor(id);
		if ( descriptor != null ) {
			return toInstance(descriptor);
		}
		else {
			throw new ResourceNotFoundException("MDTInstance", "id=" + id);
		}
	}

	@Override
	public long countInstances() {
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(m_emLocal.get());
		return instDescMgr.count();
	}

	@Override
	public List<MDTInstance> getAllInstances() throws MDTInstanceManagerException {
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(m_emLocal.get());
		return FStream.from(instDescMgr.getInstanceDescriptorAll())
						.map(desc -> (MDTInstance)toInstance(desc))
						.toList();
	}

	@Override
	public List<MDTInstance> getAllInstancesByFilter(String filterExpr) {
		Preconditions.checkNotNull(filterExpr);

		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(m_emLocal.get());
		return FStream.from(instDescMgr.findInstanceDescriptorAll(filterExpr))
						.map(desc -> (MDTInstance)toInstance(desc))
						.toList();
	}
	
	public <S> List<S> query(SearchCondition cond, InstanceDescriptorTransform<S> transform) {
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(m_emLocal.get());
		return instDescMgr.query(cond, transform);
	}
	
	@Override
	public T addInstance(String id, Environment env, String arguments)
		throws MDTInstanceManagerException {
		JpaInstanceDescriptor desc = null;
		
		try {
			AssetAdministrationShell aas = env.getAssetAdministrationShells().get(0);
			desc = JpaInstanceDescriptor.from(id, aas, env.getSubmodels());
			desc.setStatus(MDTInstanceStatus.STOPPED);
			desc.setBaseEndpoint(null);
			desc.setArguments(arguments);
			desc = initializeInstance(desc);
		}
		catch ( MDTInstanceManagerException e ) {
			throw e;
		}
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("" + e);
		}

		EntityManager em = m_emLocal.get();
		Preconditions.checkState(em != null);
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(em);
		instDescMgr.addInstanceDescriptor(desc);
		T instance = toInstance(desc);
		
		Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADDED(id));
		
		return instance;
	}

	@Override
	public T addInstance(String id, File aasFile, String arguments) throws MDTInstanceManagerException {
		try {
			Environment env = readEnvironment(aasFile);
			return addInstance(id, env, arguments);
		}
		catch ( ConstraintViolationException e ) {
			throw new ResourceAlreadyExistsException("MDTInstance", "id=" + id);
		}
		catch ( MDTInstanceManagerException e ) {
			throw e;
		}
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}

	@Override
	public void removeInstance(String id) throws ResourceNotFoundException, InvalidResourceStatusException {
		AbstractInstance inst = getInstance(id);
		
		MDTInstanceStatus status = inst.getStatus();
		switch ( status ) {
			case STARTING:
			case RUNNING:
				throw new InvalidResourceStatusException("MDTInstance", "id=" + id, status);
			default: break;
		}

		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(m_emLocal.get());
		instDescMgr.removeInstanceDescriptor(id);
		
		Try.run(inst::uninitialize);
		
		Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADDED(id));
	}

	@Override
	public void removeAllInstances() throws MDTInstanceManagerException {
		for ( MDTInstance inst: getAllInstances() ) {
			Try.run(() -> this.removeInstance(inst.getId()));
		}
	}
	
	JpaInstanceDescriptor reload(Long rowId) {
		return m_emLocal.get().find(JpaInstanceDescriptor.class, rowId);
	}
	JpaInstanceDescriptor update(Long rowId, Consumer<JpaInstanceDescriptor> updater) {
		JpaInstanceDescriptor desc = m_emLocal.get().find(JpaInstanceDescriptor.class, rowId);
		updater.accept(desc);
		
		return desc;
	}
	
	
	public String toServiceEndpoint(int repoPort) {
		return String.format(m_repositoryEndpointFormat, repoPort);
	}
	
	public String getRepositoryEndpointFormat() {
		return m_repositoryEndpointFormat;
	}
	
	/**
	 * Globals.EVENT_BUS
	 * 
	 * @param ev
	 */
	@Subscribe
	public void instanceStatusChanged(InstanceStatusChangeEvent ev) {
		if ( getLogger().isDebugEnabled() ) {
			getLogger().debug("receiving InstanceStatusChangeEvent {}", ev);
		}
		
		m_processor.run(em -> {
			JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(em);
			JpaInstanceDescriptor desc = instDescMgr.getInstanceDescriptor(ev.getId());
			if ( desc != null ) {
				switch ( ev.getStatus() ) {
					case RUNNING:
						desc.setStatus(ev.getStatus());
						desc.setBaseEndpoint(ev.getServiceEndpoint());
						
						if ( getLogger().isDebugEnabled() ) {
							getLogger().debug("set Endpoint of the MDTInstance: id={}, endpoint={}",
												desc.getId(), ev.getServiceEndpoint());
						}
						break;
					case STOPPED:
					case FAILED:
						desc.setStatus(ev.getStatus());
						desc.setBaseEndpoint(null);
						
						if ( getLogger().isDebugEnabled() ) {
							getLogger().debug("remove Endpoint of the MDTInstance: id={}", desc.getId());
						}
						break;
					default: break;
				}
				desc.setStatus(ev.getStatus());
			}
		});
	}
	
	private static final String TOPIC_STATUS_CHANGES = "mdt/manager";
	private static final int MQTT_QOS = 2;
	private static final MqttConnectOptions MQTT_OPTIONS;
	static {
		MQTT_OPTIONS = new MqttConnectOptions();
		MQTT_OPTIONS.setCleanSession(true);
	}
	
	@Subscribe
	public void publishStatusChangeEvent(InstanceStatusChangeEvent ev) {
		if ( m_mqttClient != null ) {
			try {
				String jsonStr = m_mapper.writeValueAsString(new JsonEvent<>(ev));
				MqttMessage message = new MqttMessage(jsonStr.getBytes());
				message.setQos(MQTT_QOS);
				m_mqttClient.publish(TOPIC_STATUS_CHANGES, message);
			}
			catch ( Exception e ) {
				s_logger.error("Failed to publish event, cause=" + e);
			}
		}
	}
	
	@Override
	public Logger getLogger() {
		return m_logger;
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = (logger != null) ? logger : s_logger;
	}
	
	private Environment readEnvironment(File aasEnvFile)
		throws IOException, ResourceAlreadyExistsException, ResourceNotFoundException {
		JsonDeserializer deser = AASUtils.getJsonDeserializer();
		
		try ( FileInputStream fis = new FileInputStream(aasEnvFile) ) {
			Environment env = deser.read(fis, Environment.class);
			if ( env.getAssetAdministrationShells().size() > 1
				|| env.getAssetAdministrationShells().size() == 0 ) {
				throw new MDTInstanceManagerException("Not supported: Multiple AAS descriptors in the Environment");
			}
			
			Set<String> submodelIds = Sets.newHashSet();
			for ( Submodel submodel: env.getSubmodels() ) {
				if ( submodelIds.contains(submodel.getId()) ) {
					throw new ResourceAlreadyExistsException("Submodel", submodel.getId());
				}
				submodelIds.add(submodel.getId());
			}
			
			AssetAdministrationShell aas = env.getAssetAdministrationShells().get(0);
			for ( Reference ref: aas.getSubmodels() ) {
				String refId = ref.getKeys().get(0).getValue();
				if ( !submodelIds.contains(refId) ) {
					throw new ResourceNotFoundException("Submodel", refId);
				}
			}
			
			return env;
		}
		catch ( DeserializationException e ) {
			throw new MDTInstanceManagerException("failed to parse Environment: file=" + aasEnvFile);
		}
	}
	
	private MqttClient newConnectedMqttClient(MqttConfiguration conf) {
		try {
			MqttClientPersistence persist = new MemoryPersistence();
			MqttClient mqttClient = new MqttClient(conf.getEndpoint(),
													FOption.ofNullable(conf.getClientId()).getOrNull(),
													persist);
			mqttClient.connect();
			
			return mqttClient;
		}
		catch ( MqttException e ) {
			throw new MDTInstanceManagerException("Failed to initialize MQTT client, cause=" + e);
		}
	}
}
