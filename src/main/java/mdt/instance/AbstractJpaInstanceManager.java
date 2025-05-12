package mdt.instance;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.Subscribe;

import utils.LoggerSettable;
import utils.func.FOption;
import utils.func.Try;
import utils.func.Unchecked;
import utils.io.FileUtils;
import utils.jpa.JpaContext;
import utils.jpa.JpaSession;
import utils.stream.FStream;

import mdt.Globals;
import mdt.MDTConfiguration;
import mdt.MDTConfiguration.MDTInstanceManagerConfiguration;
import mdt.MDTConfiguration.MqttConfiguration;
import mdt.instance.docker.DockerInstanceManager;
import mdt.instance.external.ExternalInstanceManager;
import mdt.instance.jar.JarInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.instance.jpa.JpaInstanceSubmodelDescriptor;
import mdt.instance.k8s.KubernetesInstanceManager;
import mdt.model.InvalidResourceStatusException;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;
import mdt.model.ServiceFactory;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;

import jakarta.persistence.EntityManagerFactory;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractJpaInstanceManager<T extends JpaInstance>
										implements MDTInstanceManagerProvider, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractJpaInstanceManager.class);
	
	private ServiceFactory m_serviceFact;
	private EntityManagerFactory m_emFact;

	private final MDTInstanceManagerConfiguration m_conf;
	private final String m_repositoryEndpointFormat;
	private final MDTInstanceStatusMqttPublisher m_mqttEventPublisher;
	
	protected final JsonMapper m_mapper = MDTModelSerDe.getJsonMapper();
	private Logger m_logger = s_logger;

	protected abstract void updateInstanceDescriptor(JpaInstanceDescriptor desc);
	protected abstract T toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException;
	
	protected AbstractJpaInstanceManager(MDTConfiguration conf) throws MDTInstanceManagerException {
		m_conf = conf.getMDTInstanceManagerConfiguration();
		
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
	
	public void initialize(ServiceFactory svcFact, EntityManagerFactory fact) throws IOException {
		m_serviceFact = svcFact;
		m_emFact = fact;
		
		if ( !getInstancesDir().exists() ) {
			FileUtils.createDirectory(getInstancesDir());
		}
		if ( !getBundlesDir().exists() ) {
			FileUtils.createDirectory(getBundlesDir());
		}
	}

	@Override
	public ServiceFactory getServiceFactory() {
		return m_serviceFact;
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
	
	public File getHomeDir() {
		return m_conf.getHomeDir();
	}
	
	public File getInstancesDir() {
		return FOption.getOrElse(m_conf.getInstancesDir(), () -> FileUtils.path(getHomeDir(), "instances"));
	}
	
	public File getBundlesDir() {
		return FOption.getOrElse(m_conf.getBundlesDir(), () -> FileUtils.path(getHomeDir(), "bundles"));
	}
	
	public File getInstanceHomeDir(String id) {
		return FileUtils.path(getInstancesDir(), id);
	}
	
	public JpaSession allocateJpaSession() {
		return JpaContext.allocate(m_emFact.createEntityManager());
	}
	
	public JpaInstanceDescriptorManager useInstanceDescriptorManager() {
		JpaContext context = JpaContext.get();
		Preconditions.checkState(context != null, "JpaContext is not allocated");
		
		return new JpaInstanceDescriptorManager(context.top());
	}
	
	public JpaInstanceDescriptor getInstanceDescriptor(String id) {
		Preconditions.checkArgument(id != null, "MDTInstance id is null");
		
		JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
		JpaInstanceDescriptor descriptor = instDescMgr.getInstanceDescriptor(id);
		if ( descriptor != null ) {
			updateInstanceDescriptor(descriptor);
			return descriptor;
		}
		else {
			throw new ResourceNotFoundException("MDTInstance", "id=" + id);
		}
	}

	@Override
	public T getInstance(String id) throws ResourceNotFoundException {
		return toInstance(getInstanceDescriptor(id));
	}

	@Override
	public long countInstances() {
		JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
		return instDescMgr.count();
	}

	@Override
	public List<T> getInstanceAll() throws MDTInstanceManagerException {
		JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
		return FStream.from(instDescMgr.getInstanceDescriptorAll())
						.map(desc -> toInstance(desc))
						.toList();
	}

	@Override
	public List<T> getInstanceAllByFilter(String filterExpr) {
		Preconditions.checkArgument(filterExpr != null, "filterExpr is null");

		JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
		return FStream.from(instDescMgr.findInstanceDescriptorAll(filterExpr))
						.map(desc -> toInstance(desc))
						.toList();
	}
	
	@Override
	public void removeInstance(String id) throws ResourceNotFoundException, InvalidResourceStatusException {
		JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
		JpaInstanceDescriptor desc = instDescMgr.getInstanceDescriptor(id);
		if ( desc == null ) {
			throw new ResourceNotFoundException("MDTInstance", "id=" + id);
		}
		
		MDTInstanceStatus status = desc.getStatus();
		switch ( status ) {
			case STARTING:
			case RUNNING:
				throw new InvalidResourceStatusException("MDTInstance", "id=" + id, status);
			default: break;
		}
		
		instDescMgr.removeInstanceDescriptor(id);
		Unchecked.runOrIgnore(() -> toInstance(desc).uninitialize());
		
		File homeDir = getInstanceHomeDir(id);
		Unchecked.runOrIgnore(() -> FileUtils.deleteDirectory(homeDir));
		
		Globals.EVENT_BUS.post(InstanceStatusChangeEvent.REMOVED(id));
	}

	@Override
	public void removeInstanceAll() throws MDTInstanceManagerException {
		for ( MDTInstance inst: getInstanceAll() ) {
			Try.run(() -> removeInstance(inst.getId()));
		}
	}
	
	/**
	 * JpaInstanceDescriptor를 생성하고 데이터베이스에 추가한다.
	 * <p>
	 * 이 메소드는 각 MDTInstanceManager 구현체 ({@link ExternalInstanceManager}, {@link JarInstanceManager},
	 * {@link DockerInstanceManager}, {@link KubernetesInstanceManager} 등)에서 호출된다.
	 *
	 * @param id        MDTInstance의 식별자
	 * @param env       Asset Administration Shell과 서브모델을 포함한 {@link Environment} 객체.
	 * @param arguments MDTInstance에 대한 추가 인자 정보 문자열.
	 * @return 생성된 JpaInstanceDescriptor
	 * @throws IllegalStateException EntityManager가 설정되지 않은 경우
	 */
	protected JpaInstanceDescriptor addInstanceDescriptor(String id, Environment env, String arguments) {
		AssetAdministrationShell aas = env.getAssetAdministrationShells().get(0);

		// 제공된 id, AAS 및 서브모델을 사용하여 새로운 JpaInstanceDescriptor를 생성한다.
		JpaInstanceDescriptor desc = JpaInstanceDescriptor.from(id, aas, env.getSubmodels());
		desc.setStatus(MDTInstanceStatus.STOPPED);
		desc.setBaseEndpoint(null); // MDTInstance가 시작되면 엔드포인트가 결정되므로 지금은 {@code null}로 설정한다.
		desc.setArguments(arguments);

		// 생성된 JpaInstanceDescriptor를 데이터베이스에 추가한다.
		JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
		instDescMgr.addInstanceDescriptor(desc);
		
		return desc;
	}
	
	/**
	 * 주어진 id에 해당하는 JpaInstanceDescriptor를 데이터베이스에서 제거한다.
	 *
	 * @param id	제거할 MDTInstance의 식별자.
	 */
	protected void removeInstanceDescriptor(String id) {
		Preconditions.checkArgument(id != null, "MDTInstance id is null");

		JpaInstanceDescriptorManager instDescMgr = useInstanceDescriptorManager();
		instDescMgr.removeInstanceDescriptor(id);
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

//		try ( JpaSessions session = allocateJpaSession() ) {
//			JpaInstanceDescriptorManager instDescMgr = JpaSessions.get().getInstanceDescriptorManager();
//			JpaInstanceDescriptor desc = instDescMgr.getInstanceDescriptor(ev.getInstanceId());
//			if ( desc != null ) {
//				switch ( ev.getStatusChange() ) {
//					case "STARTING":
//					case "STOPPING":
//						desc.setStatus(ev.getInstanceStatus());
//						desc.setBaseEndpoint(ev.getServiceEndpoint());	
//						break;
//					case "RUNNING":
//						desc.setStatus(ev.getInstanceStatus());
//						desc.setBaseEndpoint(ev.getServiceEndpoint());	
//						if ( getLogger().isDebugEnabled() ) {
//							getLogger().debug("set Endpoint of the MDTInstance: id={}, endpoint={}",
//												desc.getId(), ev.getServiceEndpoint());
//						}
//						break;
//					case "STOPPED":
//					case "FAILED":
//						desc.setStatus(ev.getInstanceStatus());
//						desc.setBaseEndpoint(null);
//						if ( getLogger().isDebugEnabled() ) {
//							getLogger().debug("remove Endpoint of the MDTInstance: id={}", desc.getId());
//						}
//						break;
//					default: break;
//				}
//			}
//			else if ( ev.getStatusChange().equals("ADDING") ) {
//				// Todo: 나중에 adding 단계에서 descriptor를 추가할 필요가 있을까?
//			}
//		}
	}
	
	public AssetAdministrationShellDescriptor toAssetAdministrationShellDescriptor(JpaInstanceDescriptor ismd) {
		return ismd.toAssetAdministrationShellDescriptor();
	}
    
	public SubmodelDescriptor toSubmodelDescriptor(JpaInstanceSubmodelDescriptor ismd) {
		return ismd.getInstance().toSubmodelDescriptor(ismd);
	}
	
	@Override
	public Logger getLogger() {
		return FOption.getOrElse(m_logger, s_logger);
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = logger;
	}
}
