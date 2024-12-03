package mdt.instance;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.builder.jersey.JerseyDockerClientBuilder;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;

import utils.LoggerSettable;
import utils.async.Guard;
import utils.func.FOption;
import utils.func.Try;
import utils.stream.FStream;

import mdt.Globals;
import mdt.MDTConfiguration;
import mdt.MDTConfiguration.MDTInstanceManagerConfiguration;
import mdt.MDTConfiguration.MqttConfiguration;
import mdt.controller.DockerCommandUtils;
import mdt.controller.DockerCommandUtils.StandardOutputHandler;
import mdt.instance.docker.DockerUtils;
import mdt.instance.docker.HarborConfiguration;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.instance.jpa.JpaInstanceDescriptorManager.InstanceDescriptorTransform;
import mdt.instance.jpa.JpaInstanceDescriptorManager.SearchCondition;
import mdt.instance.jpa.JpaModule;
import mdt.instance.jpa.JpaProcessor;
import mdt.model.InvalidResourceStatusException;
import mdt.model.MDTModelSerDe;
import mdt.model.ModelValidationException;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;
import mdt.model.ServiceFactory;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.service.MDTInstance;
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
	private final File m_defaultMDTInstanceJarFile;
	private final String m_repositoryEndpointFormat;
	private final HarborConfiguration m_harborConf;
	private final MDTInstanceStatusMqttPublisher m_mqttEventPublisher;
	
	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private final Map<String, Thread> m_installers = Maps.newHashMap();
	
	protected final JsonMapper m_mapper = MDTModelSerDe.getJsonMapper();
	private Logger m_logger = s_logger;
	
	public abstract void initialize(InstanceDescriptorManager instDescManager) throws MDTInstanceManagerException;
	protected abstract T toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException;
	
	protected AbstractInstanceManager(MDTConfiguration conf) throws MDTInstanceManagerException {
		m_conf = conf.getMDTInstanceManagerConfiguration();
		m_defaultMDTInstanceJarFile = m_conf.getDefaultMDTInstanceJarFile();
		m_harborConf = conf.getHarborConfiguration();
		
		String epFormat = m_conf.getRepositoryEndpointFormat();
		if ( epFormat == null ) {
			try {
//				List<String> addrList = NetUtils.getLocalHostAddresses();
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

		Globals.EVENT_BUS.register(this);
		MqttConfiguration mqttConf = conf.getMqttConfiguration();
		if ( mqttConf.getEndpoint() != null ) {
			m_mqttEventPublisher = MDTInstanceStatusMqttPublisher.builder()
															.mqttServerUri(mqttConf.getEndpoint())
															.clientId(mqttConf.getClientId())
															.qos(mqttConf.getQos())
															.reconnectInterval(mqttConf.getReconnectInterval())
															.build();
			m_mqttEventPublisher.startAsync();
			Globals.EVENT_BUS.register(m_mqttEventPublisher);
		}
		else {
			m_mqttEventPublisher = null;
		}
	}
	
	public void shutdown() {
	}
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
	
	public File getDefaultMDTInstanceJarFile() {
		return m_defaultMDTInstanceJarFile;
	}
	
	public File getBundleHomeDir() {
		return m_conf.getBundlesDir();
	}
	
	public HarborConfiguration getHarborConfiguration() {
		return m_harborConf;
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
	
	protected JpaInstanceDescriptor addInstanceDescriptor(String id, File modelFile, String arguments)
		throws ModelValidationException, IOException {
		Environment env = readEnvironment(modelFile);
		
		AssetAdministrationShell aas = env.getAssetAdministrationShells().get(0);
		
		JpaInstanceDescriptor desc = JpaInstanceDescriptor.from(id, aas, env.getSubmodels());
		desc.setStatus(MDTInstanceStatus.STOPPED);
		desc.setBaseEndpoint(null);	// endpoint을 MDTInstance가 시작되면 결정되기 때문에 지금을 null로 채운다.
		desc.setArguments(arguments);

		EntityManager em = m_emLocal.get();
		Preconditions.checkState(em != null);
		JpaInstanceDescriptorManager instDescMgr = new JpaInstanceDescriptorManager(em);
		instDescMgr.addInstanceDescriptor(desc);
		
		return desc;
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
		
		Globals.EVENT_BUS.post(InstanceStatusChangeEvent.REMOVED(id));
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
	protected JpaInstanceDescriptor update(Long rowId, Consumer<JpaInstanceDescriptor> updater) {
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
			JpaInstanceDescriptor desc = instDescMgr.getInstanceDescriptor(ev.getInstanceId());
			if ( desc != null ) {
				switch ( ev.getStatusChange() ) {
					case "RUNNING":
						desc.setStatus(ev.getInstanceStatus());
						desc.setBaseEndpoint(ev.getServiceEndpoint());
						
						if ( getLogger().isDebugEnabled() ) {
							getLogger().debug("set Endpoint of the MDTInstance: id={}, endpoint={}",
												desc.getId(), ev.getServiceEndpoint());
						}
						break;
					case "STOPPED":
					case "FAILED":
						desc.setStatus(ev.getInstanceStatus());
						desc.setBaseEndpoint(null);
						
						if ( getLogger().isDebugEnabled() ) {
							getLogger().debug("remove Endpoint of the MDTInstance: id={}", desc.getId());
						}
						break;
					default: break;
				}
				switch ( ev.getStatusChange() ) {
					case "ADDING":
					case "ADD_FAILED":
						break;
					default:
						desc.setStatus(ev.getInstanceStatus());
				};
			}
			else if ( ev.getStatusChange().equals("ADDING") ) {
				// Todo: 나중에 adding 단계에서 descriptor를 추가할 필요가 있을까?
			}
		});
	}
	
	protected String deployInstanceDockerImage(String id, File bundleDir, String dockerEndpoint,
													@Nullable HarborConfiguration harborConf) {
		try ( DockerClient docker = new JerseyDockerClientBuilder().uri(dockerEndpoint).build() ) {
			// 동일 image id의 docker image가 존재할 수 있기 때문에 이를 먼저 삭제한다.
			DockerUtils.removeInstanceImage(docker, id);
			
			// bundleDir에 포함된 데이터를 이용하여 docker image를 생성한다.
			if ( getLogger().isInfoEnabled() ) {
				getLogger().info("Start building docker image: instance=" + id + ", bundleDir=" + bundleDir);
			}
			
			StandardOutputHandler outputHandler = new DockerCommandUtils.RedirectOutput(
																			new File(bundleDir, "stdout.log"),
																			new File(bundleDir, "stderr.log"));
			String repoName = DockerCommandUtils.buildDockerImage(id, bundleDir, outputHandler);
			if ( s_logger.isInfoEnabled() ) {
				s_logger.info("Done: docker image: repo=" + repoName);
			}
	    	
			if ( harborConf != null && harborConf.getEndpoint() != null ) {  	
				// Harbor로 push하기 위한 tag를 부여한다.
				Image image = DockerUtils.getInstanceImage(docker, id).getUnchecked();
				String harborRepoName = DockerUtils.tagImageForHarbor(docker, image, harborConf, "latest");

				if ( getLogger().isInfoEnabled() ) {
					getLogger().info("Pusing docker image to Harbor: instance={}, repo={}", id, harborRepoName);
				}
				DockerUtils.pushImage(docker, harborRepoName, harborConf);
				if ( s_logger.isInfoEnabled() ) {
					s_logger.info("Done: push to Harbor: repo=" + harborRepoName);
				}
				
		    	// Harbor로 push하고 나서는 tag되기 이전 image를 삭제한다.
				docker.removeImage(repoName);
				
				// harbor로 push된 경우에는 harbor의 docker image를 사용한다.
				repoName = harborRepoName;
			}
			
			return repoName;
		}
		catch ( DockerException e ) {
			throw new MDTInstanceManagerException("Failed to add a DockerInstance: id=" + id, e);
		}
		catch ( InterruptedException e ) {
			throw new MDTInstanceManagerException("MDTInstance addition has been interrupted");
		}
	}
	
	@Override
	public Logger getLogger() {
		return FOption.getOrElse(m_logger, s_logger);
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = logger;
	}
	
	private Environment readEnvironment(File aasEnvFile) throws IOException, ModelValidationException {
		JsonDeserializer deser = MDTModelSerDe.getJsonDeserializer();
		
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
					String msg = String.format("Submodel not found: id=%s", refId);
					throw new ModelValidationException(msg);
				}
			}
			
			return env;
		}
		catch ( DeserializationException e ) {
			throw new MDTInstanceManagerException("failed to parse Environment: file=" + aasEnvFile);
		}
		catch ( Throwable e ) {
			e.printStackTrace();
			throw new AssertionError();
		}
	}
}
