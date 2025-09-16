package mdt.instance;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.Subscribe;

import utils.LoggerSettable;
import utils.func.FOption;
import utils.func.Try;
import utils.func.Unchecked;
import utils.io.FileUtils;
import utils.jpa.JpaProcessor;
import utils.stream.FStream;

import mdt.Globals;
import mdt.client.HttpServiceFactory;
import mdt.instance.docker.DockerInstanceManager;
import mdt.instance.external.ExternalInstanceManager;
import mdt.instance.jar.JarInstanceManager;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaMDTOperationDescriptor;
import mdt.instance.jpa.JpaMDTParameterDescriptor;
import mdt.instance.jpa.JpaMDTSubmodelDescriptor;
import mdt.instance.k8s.KubernetesInstanceManager;
import mdt.model.AASUtils;
import mdt.model.InvalidResourceStatusException;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;
import mdt.model.ServiceFactory;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.instance.MDTOperationDescriptor;
import mdt.model.instance.MDTParameterDescriptor;
import mdt.model.instance.MDTSubmodelDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor;
import mdt.repository.Repositories;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractJpaInstanceManager<T extends JpaInstance>
										implements MDTInstanceManagerProvider, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractJpaInstanceManager.class);

	protected final MDTInstanceManagerConfiguration m_conf;

	private final MqttConfiguration m_mqttConf;
	private final MDTInstanceStatusMqttPublisher m_mqttEventPublisher;
	
    protected final Repositories m_repos;
	
    private final ServiceFactory m_serviceFact;
	
	protected final JsonMapper m_mapper = MDTModelSerDe.getJsonMapper();
	private Logger m_logger = s_logger;

	protected abstract void adaptInstanceDescriptor(JpaInstanceDescriptor desc);
	protected abstract T toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException;
	
	protected AbstractJpaInstanceManager(MDTInstanceManagerConfiguration conf, Repositories repos,
										MqttConfiguration mqttConf) throws IOException {
		m_conf = conf;
		m_repos = repos;
		m_mqttConf = mqttConf;
		
		Globals.EVENT_BUS.register(this);
		if ( m_mqttConf.getEndpoint() != null ) {
			m_mqttEventPublisher = MDTInstanceStatusMqttPublisher.builder()
																.mqttServerUri(m_mqttConf.getEndpoint())
																.clientId(m_mqttConf.getClientId())
																.qos(m_mqttConf.getQos())
																.reconnectInterval(m_mqttConf.getReconnectInterval())
																.build();
			m_mqttEventPublisher.startAsync();
			Globals.EVENT_BUS.register(m_mqttEventPublisher);
		}
		else {
			m_mqttEventPublisher = null;
		}
		
		if ( !getInstancesDir().exists() ) {
			FileUtils.createDirectory(getInstancesDir());
		}
		if ( !getBundlesDir().exists() ) {
			FileUtils.createDirectory(getBundlesDir());
		}
		try {
			m_serviceFact = new HttpServiceFactory();
		}
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("Failed to create HttpServiceFactory: " + e.getMessage(), e);
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
	
	public File getShareDir() {
		return FOption.getOrElse(m_conf.getShareDir(), () -> FileUtils.path(getHomeDir(), "share"));
	}
	
	public File getInstanceHomeDir(String id) {
		return FileUtils.path(getInstancesDir(), id);
	}
	
	public JpaInstanceDescriptor getInstanceDescriptor(String instanceId) throws ResourceNotFoundException {
		Preconditions.checkArgument(instanceId != null, "MDTInstance id is null");
		
		JpaInstanceDescriptor descriptor = m_repos.instances()
													.findByInstanceId(instanceId)
													.orElseThrow(() -> newInstanceNotFoundException(instanceId));
		adaptInstanceDescriptor(descriptor);
		return descriptor;
	}

	@Override
	public T getInstance(String id) throws ResourceNotFoundException {
		return toInstance(getInstanceDescriptor(id));
	}

	@Override
	public List<T> getInstanceAll() throws MDTInstanceManagerException {
		return FStream.from(m_repos.instances().findAll())
						.map(desc -> toInstance(desc))
						.toList();
	}

	@Override
	public List<T> getInstanceAllByFilter(String filterExpr) {
		Preconditions.checkArgument(filterExpr != null, "filterExpr is null");
		
		JpaProcessor processor = new JpaProcessor(m_repos.entityManagerFactory());
		return processor.get(em -> {
			boolean containsSubmodelExpr = filterExpr.toLowerCase().contains("submodel.");
			String sql = (containsSubmodelExpr)
						? "select distinct instance from JpaInstanceDescriptor instance "
							+ "join fetch instance.submodels as submodel where " + filterExpr
						: "select instance from JpaInstanceDescriptor instance where " + filterExpr;
			TypedQuery<JpaInstanceDescriptor> query = em.createQuery(sql, JpaInstanceDescriptor.class);
			return FStream.from(query.getResultList())
					.map(desc -> toInstance(desc))
					.toList();
		});
	}

	@Override
	public long countInstances() {
		return m_repos.instances().count();
	}
	
	@Transactional
	@Override
	public void removeInstance(String id) throws ResourceNotFoundException, InvalidResourceStatusException {
		Preconditions.checkArgument(id != null, "MDTInstance id is null");
		
		JpaInstanceDescriptor desc = m_repos.instances()
											.findByInstanceId(id)
											.orElseThrow(() -> newInstanceNotFoundException(id));
		
		MDTInstanceStatus status = desc.getStatus();
		switch ( status ) {
			case STARTING:
			case RUNNING:
				throw new InvalidResourceStatusException("MDTInstance", "id=" + id, status);
			default: break;
		}
		m_repos.instances().delete(desc);
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
		JpaInstanceDescriptor desc = JpaInstanceDescriptor.build(id, aas, env.getSubmodels());
		desc.setStatus(MDTInstanceStatus.STOPPED);
		desc.setBaseEndpoint(null); // MDTInstance가 시작되면 엔드포인트가 결정되므로 지금은 {@code null}로 설정한다.
		desc.setArguments(arguments);

		// 생성된 JpaInstanceDescriptor를 데이터베이스에 추가한다.
		try {
			m_repos.instances().save(desc);
		}
		catch ( EntityExistsException | ConstraintViolationException e ) {
			throw new ResourceAlreadyExistsException("MDTInstance", "id=" + desc.getId());
		}
		catch ( DataIntegrityViolationException e ) {
			Throwable cause = e.getMostSpecificCause();
			if ( cause instanceof SQLException sqlError ) {
				String sqlState = sqlError.getSQLState();
				// "23505"는 PostgreSQL에서 고유 제약 조건 위반을 나타내는 SQL 상태 코드이다.
				if ( "23505".equals(sqlState) ) {
					throw new ResourceAlreadyExistsException("MDTInstance", "id=" + desc.getId());
				}
			}
			throw e;
		}
		
		return desc;
	}
	
	@Transactional
	public void updateInstanceDescriptor(String id, Consumer<JpaInstanceDescriptor> update)
		throws ResourceNotFoundException {
		Preconditions.checkArgument(id != null, "MDTInstance id is null");
		Preconditions.checkArgument(update != null, "JpaInstanceDescriptor updater is null");
		
		JpaInstanceDescriptor descriptor = m_repos.instances()
													.findByInstanceId(id)
													.orElseThrow(() -> newInstanceNotFoundException(id));
		update.accept(descriptor);
		m_repos.instances().save(descriptor);
	}
	
	/**
	 * 주어진 id에 해당하는 JpaInstanceDescriptor를 데이터베이스에서 제거한다.
	 *
	 * @param id	제거할 MDTInstance의 식별자.
	 */
	protected void removeInstanceDescriptor(String id) {
		Preconditions.checkArgument(id != null, "MDTInstance id is null");
		
		m_repos.instances().deleteByInstanceId(id);
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
	}
	
	protected JpaInstanceDescriptor getJpaInstanceDescriptor(String instId) {
		return m_repos.instances()
						.findByInstanceId(instId)
						.orElseThrow(() -> newInstanceNotFoundException(instId));
	}
	
	AssetAdministrationShellDescriptor getAssetAdministrationShellDescriptor(String instId) {
		JpaInstanceDescriptor jpaDesc = m_repos.instances()
												.findByInstanceId(instId)
												.orElseThrow(() -> newInstanceNotFoundException(instId));
		return jpaDesc.getAssetAdministrationShellDescriptor();
	}

	List<SubmodelDescriptor> getAASSubmodelDescriptorAll(String instId) {
		return FStream.from(m_repos.submodels().findAllByInstanceId(instId))
						.map(JpaMDTSubmodelDescriptor::getAASSubmodelDescriptor)
						.toList();
	}

	public List<MDTSubmodelDescriptor> getMDTSubmodelDescriptorAll(String instId) {
		JpaInstanceDescriptor instDesc = getInstanceDescriptor(instId);
		String endpointPrefix = instDesc.getBaseEndpoint();
		
		List<JpaMDTSubmodelDescriptor> jpaDescList = m_repos.submodels().findAllByInstanceId(instId);
		return FStream.from(jpaDescList)
						.map(smDesc -> {
							MDTSubmodelDescriptor desc = smDesc.toMDTSubmodelDescriptor();
							String endpoint = (endpointPrefix != null)
											? endpointPrefix + "/submodels/" + AASUtils.encodeBase64UrlSafe(desc.getId()) 
											: null;
							desc.setEndpoint(endpoint);
							
							return desc;
						})
						.toList();
	}
	
	public List<MDTParameterDescriptor> getMDTParameterDescriptorAll(String instId) {
		InstanceDescriptor instDesc = getInstanceDescriptor(instId).toInstanceDescriptor();
		JpaMDTSubmodelDescriptor dataSmDesc = getMDTSubmodelDescriptor(instId, "Data");
		// Data 서브모델이 존재하지 않는 경우, 파라미터도 존재하지 않는 것으로 간주한다.
		if ( dataSmDesc == null ) {
			return List.of();
		}
		
		String smEndpoint = (instDesc.getBaseEndpoint() != null)
							? instDesc.getBaseEndpoint() + "/submodels/" + AASUtils.encodeBase64UrlSafe(dataSmDesc.getId())
							: null;
		
		List<JpaMDTParameterDescriptor> jpaDescList = m_repos.parameters().findAllByInstanceId(instId);
		return FStream.from(jpaDescList)
						.map(param -> param.toMDTParameterDescriptor())
						.zipWithIndex()
						.map(idxed -> {
							String paramEndpoint = (smEndpoint != null)
												? instDesc.getParameterEndpoint(idxed.index(), smEndpoint) : null;
							idxed.value().setEndpoint(paramEndpoint);
							return idxed.value();
						})
						.toList();
	}
	private JpaMDTSubmodelDescriptor getMDTSubmodelDescriptor(String instId, String submodelIdShort) {
		return m_repos.submodels()
						.findByInstanceIdAndSubmodelIdShort(instId, submodelIdShort)
						.orElse(null);
//						.orElseThrow(() -> new ResourceNotFoundException("MDTSubmodelDescriptor",
//																	"instanceId=" + instId + ", submodelIdShort=" + submodelIdShort));
	}

	public List<MDTOperationDescriptor> getMDTOperationDescriptorAll(String instId) {
		List<JpaMDTOperationDescriptor> jpaDescList = m_repos.operations().findAllByInstanceId(instId);
		return FStream.from(jpaDescList)
						.map(op -> op.toMDTOperationDescriptor())
						.toList();
	}
	
	public MDTTwinCompositionDescriptor getTwinCompositionDescriptor(String instId) {
		return m_repos.instances()
						.findByInstanceId(instId)
						.map(JpaInstanceDescriptor::getTwinComposition)
						.orElseThrow(() -> newInstanceNotFoundException(instId));
	}
	
	@Override
	public Logger getLogger() {
		return FOption.getOrElse(m_logger, s_logger);
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = logger;
	}
	
	@Transactional
	protected void updateInstanceDescriptor(String id, MDTInstanceStatus status, String endpoint) {
		updateInstanceDescriptor(id, desc -> {
			desc.setStatus(status);
			desc.setBaseEndpoint(endpoint);
		});
	}
	
	private static ResourceNotFoundException newInstanceNotFoundException(String instanceId) {
		return new ResourceNotFoundException("MDTInstance", "id=" + instanceId);
	}
}
