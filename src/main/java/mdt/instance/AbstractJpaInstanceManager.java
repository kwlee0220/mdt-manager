package mdt.instance;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.hibernate.exception.ConstraintViolationException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.Subscribe;

import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import utils.LoggerSettable;
import utils.func.Nullables;
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
import mdt.model.instance.MDTOperationDescriptor.ArgumentDescriptor;
import mdt.model.instance.MDTParameterDescriptor;
import mdt.model.instance.MDTSubmodelDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor;
import mdt.model.timeseries.TimeSeries;
import mdt.repository.Repositories;


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
																.qos(m_mqttConf.getQos())
																.build();
			m_mqttEventPublisher.startAsync();
			Globals.EVENT_BUS.register(m_mqttEventPublisher);
		}
		else {
			m_mqttEventPublisher = null;
		}

		if ( !getBundlesDir().exists() ) {
			FileUtils.createDirectory(getBundlesDir());
		}
		if ( !getInstancesDir().exists() ) {
			FileUtils.createDirectory(getInstancesDir());
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
	
	/**
	 * 매니저가 쓰던 자원을 정리한다: EventBus 구독 해제, MQTT 상태 발행 서비스 중지.
	 * <p>
	 * Spring 컨텍스트가 닫힐 때 {@code @PreDestroy}로 불린다. 하위 클래스가 재정의하면 자기 자원(실행 중인 프로세스,
	 * health check 등)을 먼저 정리한 뒤 {@code super.shutdown()}을 불러야 한다.
	 */
	@PreDestroy
	public void shutdown() {
		getLogger().info("shutting down {}...", getClass().getSimpleName());
		
		Unchecked.runOrIgnore(() -> Globals.EVENT_BUS.unregister(this));
		if ( m_mqttEventPublisher != null ) {
			Unchecked.runOrIgnore(() -> Globals.EVENT_BUS.unregister(m_mqttEventPublisher));
			try {
				m_mqttEventPublisher.stopAsync().awaitTerminated(SHUTDOWN_TIMEOUT);
			}
			catch ( Exception e ) {
				getLogger().warn("failed to stop the MQTT status publisher: cause={}", "" + e);
			}
		}
	}
	
	/** {@link #shutdown()}에서 각 서비스가 끝나기를 기다리는 최대 시간. */
	protected static final java.time.Duration SHUTDOWN_TIMEOUT = java.time.Duration.ofSeconds(5);
	
	public MDTInstanceManagerConfiguration getConfiguration() {
		return m_conf;
	}
	
	public File getHomeDir() {
		return m_conf.getHomeDir();
	}
	
	/**
	 * MDTInstance 식별자 규칙: 영문자·숫자·{@code _}·{@code -}로 된 1~64자.
	 * <p>
	 * 식별자는 {@code <instancesDir>/<id>}, {@code <bundlesDir>/<id>} 디렉터리 이름으로 쓰이므로 경로 구분자나
	 * {@code .}/{@code ..}를 허용하지 않는다. 길이 상한은 DB 컬럼({@code instance_id}, 64자)과 같다.
	 */
	public static final Pattern INSTANCE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	
	/**
	 * 주어진 문자열이 MDTInstance 식별자 규칙({@link #INSTANCE_ID_PATTERN})에 맞는지 확인한다.
	 *
	 * @param id	검사할 식별자.
	 * @return	{@code id}.
	 * @throws IllegalArgumentException	{@code id}가 null이거나 규칙에 맞지 않는 경우.
	 */
	public static String checkInstanceId(String id) {
		Preconditions.checkArgument(id != null, "MDTInstance id is null");
		Preconditions.checkArgument(INSTANCE_ID_PATTERN.matcher(id).matches(),
									"invalid MDTInstance id (allowed: [A-Za-z0-9_-], 1~64 chars): '%s'", id);
		return id;
	}
	
	/**
	 * 주어진 id의 인스턴스가 아직 등록되지 않았는지 확인한다.
	 * <p>
	 * 각 백엔드의 {@code addInstance}는 인스턴스 디렉터리를 지우고 새 번들을 복사한 뒤 DB에 등록한다. 그래서 이 확인 없이
	 * 이미 있는 id로 등록하면, DB 등록은 실패(409)해도 기존 인스턴스의 파일이 새 번들로 바뀐다.
	 *
	 * @param id	인스턴스 id.
	 * @throws ResourceAlreadyExistsException	이미 등록된 경우.
	 */
	protected void checkNotRegistered(String id) throws ResourceAlreadyExistsException {
		if ( m_repos.instances().findByInstanceId(id).isPresent() ) {
			throw new ResourceAlreadyExistsException("MDTInstance", "id=" + id);
		}
	}
	
	/**
	 * MDTInstance의 홈 디렉터리 {@code <instancesDir>/<id>}를 반환한다.
	 * <p>
	 * id를 먼저 검사한다. id가 {@code ""}/{@code "."}이면 instances 디렉터리 자신이 되어 등록·삭제 때 instances
	 * 디렉터리 전체가 지워질 수 있기 때문이다.
	 *
	 * @throws IllegalArgumentException	{@code id}가 식별자 규칙({@link #INSTANCE_ID_PATTERN})에 맞지 않는 경우.
	 */
	public File getInstanceHomeDir(String id) {
		return FileUtils.path(getInstancesDir(), checkInstanceId(id));
	}
	
	public File getInstancesDir() {
		return m_conf.getInstancesDir();
	}
	
	public File getBundlesDir() {
		return m_conf.getBundlesDir();
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
	public List<MDTInstance> getInstanceAll() throws MDTInstanceManagerException {
		return List.copyOf(FStream.from(m_repos.instances().findAll())
									.map(desc -> (MDTInstance)toAdaptedInstance(desc))
									.toList());
	}
	
	/**
	 * 실행 엔진의 실제 상태를 반영(adapt)한 뒤 인스턴스 객체를 만든다.
	 * <p>
	 * 단건 조회({@link #getInstanceDescriptor(String)})와 목록 조회가 같은 상태·endpoint를 보이도록 모든 조회 경로가 이
	 * 메소드를 거친다(목록 조회 {@code getInstanceAll}, {@code getInstanceAllByFilter} 포함).
	 */
	private T toAdaptedInstance(JpaInstanceDescriptor desc) {
		adaptInstanceDescriptor(desc);
		return toInstance(desc);
	}
	
	public List<String> listInstanceIds(String type) {
		if ( type.equalsIgnoreCase("all") ) {
			return FStream.from(m_repos.instances().findAll())
					.map(desc -> desc.getInstanceId())
					.toList();
		}
		else if ( type.equalsIgnoreCase("running") ) {
			return FStream.from(m_repos.instances().findAll())
					        .filter(desc -> desc.getStatus() == MDTInstanceStatus.RUNNING)
							.map(desc -> desc.getInstanceId())
							.toList();
		}
		else if ( type.equalsIgnoreCase("non-running") ) {
			return FStream.from(m_repos.instances().findAll())
					        .filter(desc -> desc.getStatus() != MDTInstanceStatus.RUNNING)
							.map(desc -> desc.getInstanceId())
							.toList();
		}
		else {
			throw new IllegalArgumentException("unknown instance type: " + type);
		}
	}
	
	public List<String> listParameterIds(String instanceId) {
		Preconditions.checkArgument(instanceId != null, "MDTInstance id is null");
		
		return FStream.from(m_repos.parameters().findAllByInstance_InstanceId(instanceId))
						.map(param -> param.getId())
						.toList();
	}
	
	public List<String> listOperationIds(String instanceId) {
		Preconditions.checkArgument(instanceId != null, "MDTInstance id is null");
		
		return FStream.from(m_repos.operations().findAllByInstance_InstanceId(instanceId))
						.map(op -> op.getId())
						.toList();
	}
	
	public List<String> listArgumentIds(String instanceId, String operationId) {
		Preconditions.checkArgument(instanceId != null, "MDTInstance id is null");
		Preconditions.checkArgument(operationId != null, "Operation id is null");
		
		return FStream.from(m_repos.operations().findAllByInstance_InstanceId(instanceId))
						.filter(opDesc -> opDesc.getId().equals(operationId))
						.flatMap(opDesc -> FStream.from(opDesc.getInputArguments())
													.concatWith(opDesc.getOutputArguments()))
						.map(argDesc -> argDesc.getId())
						.toList();
	}
	
	public List<ArgumentDescriptor> listArgumentDescriptors(String instanceId, String operationId) {
		Preconditions.checkArgument(instanceId != null, "MDTInstance id is null");
		Preconditions.checkArgument(operationId != null, "Operation id is null");
		
		return FStream.from(m_repos.operations().findAllByInstance_InstanceId(instanceId))
						.filter(opDesc -> opDesc.getId().equals(operationId))
						.flatMap(opDesc -> FStream.from(opDesc.getInputArguments())
													.concatWith(opDesc.getOutputArguments()))
						.toList();
	}
	
	public List<String> listSubmodelIds(String instanceId) {
		Preconditions.checkArgument(instanceId != null, "MDTInstance id is null");
		
		return FStream.from(m_repos.submodels().findAllByInstance_InstanceId(instanceId))
						.map(sm -> sm.getIdShort())
						.toList();
	}
	
	public List<String> listTimeSeriesIds(String instanceId) {
		Preconditions.checkArgument(instanceId != null, "MDTInstance id is null");
		
		return FStream.from(m_repos.submodels().findAllByInstance_InstanceId(instanceId))
						// semanticId가 없는 submodel도 있다.
						.filter(sm -> TimeSeries.SEMANTIC_ID.equals(sm.getSemanticId()))
						.map(JpaMDTSubmodelDescriptor::getIdShort)
						.toList();
	}

	/**
	 * 등록된 모든 MDTInstance의 Submodel을 (MDTInstance 식별자, Submodel 식별자, idShort)로 반환한다.
	 *
	 * @return	Submodel 키 목록.
	 */
	public List<SubmodelKey> listSubmodelKeys() {
		return FStream.from(m_repos.submodels().findAll())
						.map(sm -> new SubmodelKey(sm.getInstance().getInstanceId(), sm.getId(), sm.getIdShort()))
						.toList();
	}

	/**
	 * Submodel 하나를 가리키는 키.
	 *
	 * @param instanceId	Submodel이 속한 MDTInstance 식별자.
	 * @param submodelId	Submodel 식별자.
	 * @param idShort		Submodel idShort.
	 */
	public record SubmodelKey(String instanceId, String submodelId, String idShort) { }

	@Override
	public List<MDTInstance> getInstanceAllByFilter(String filterExpr) {
		Preconditions.checkArgument(filterExpr != null, "filterExpr is null");
		
		JpaProcessor processor = new JpaProcessor(m_repos.entityManagerFactory());
		return processor.get(em -> {
			String sql = "select instance from JpaInstanceDescriptor instance where " + filterExpr;
			
			boolean containsParameterExpr = filterExpr.toLowerCase().contains("parameter.");
			if ( containsParameterExpr ) {
				sql = "select distinct instance from JpaInstanceDescriptor instance "
					+ "join fetch instance.parameters as parameter where " + filterExpr;
			}
			else {
				boolean containsSubmodelExpr = filterExpr.toLowerCase().contains("submodel.");
				if ( containsSubmodelExpr ) {
					sql = "select distinct instance from JpaInstanceDescriptor instance "
						+ "join fetch instance.submodels as submodel where " + filterExpr;
				}
			}
			TypedQuery<JpaInstanceDescriptor> query = em.createQuery(sql, JpaInstanceDescriptor.class);
			return List.copyOf(FStream.from(query.getResultList())
										.map(desc -> (MDTInstance)toAdaptedInstance(desc))
										.toList());
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
		
		// docker는 DB 상태가 실제와 다를 수 있으므로 실행 엔진의 상태를 반영(adapt)한 뒤 판단한다. 중지 중인
		// 인스턴스도 지우지 않는다.
		adaptInstanceDescriptor(desc);
		MDTInstanceStatus status = desc.getStatus();
		switch ( status ) {
			case STARTING:
			case RUNNING:
			case STOPPING:
				throw new InvalidResourceStatusException("MDTInstance", "id=" + id, status);
			default: break;
		}
		T inst = toInstance(desc);		// 행을 지우기 전에(엔티티가 살아 있을 때) 만든다
		m_repos.instances().delete(desc);
		
		// 외부 자원 정리와 REMOVED 이벤트는 DB 삭제가 커밋된 뒤에 한다. 커밋이 실패하면 행이 남으므로 자원도
		// 남아 있어야 한다. 정리 실패는 WARN으로 남긴다.
		runAfterCommit(() -> {
			try {
				inst.uninitialize();
			}
			catch ( Exception e ) {
				getLogger().warn("failed to clean up the removed MDTInstance: id={}, cause={}", id, "" + e);
			}
			
			// 식별자 규칙에 맞지 않는 id라 홈 디렉터리 경로를 만들 수 없으면 디렉터리 삭제만 건너뛴다.
			if ( INSTANCE_ID_PATTERN.matcher(id).matches() ) {
				File homeDir = getInstanceHomeDir(id);
				try {
					FileUtils.deleteDirectory(homeDir);
				}
				catch ( Exception e ) {
					getLogger().warn("failed to delete the home directory: id={}, dir={}, cause={}", id, homeDir, "" + e);
				}
			}
			else {
				getLogger().warn("skip deleting the home directory of MDTInstance with an invalid id: '{}'", id);
			}
			
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.REMOVED(id));
		});
	}
	
	// 진행 중인 트랜잭션이 있으면 커밋된 뒤에, 없으면 곧바로 task를 실행한다.
	private static void runAfterCommit(Runnable task) {
		if ( TransactionSynchronizationManager.isSynchronizationActive() ) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		}
		else {
			task.run();
		}
	}

	@Override
	/**
	 * 모든 인스턴스를 하나씩 {@link #removeInstance(String)}로 지운다. 한 인스턴스의 삭제가 실패해도(예: 실행 중)
	 * 나머지는 계속 지우고 실패는 WARN으로 남긴다. 각 삭제는 저장소 메소드의 트랜잭션으로 커밋된다(자기 호출이라
	 * {@code removeInstance}의 {@code @Transactional}은 적용되지 않는다).
	 */
	public void removeInstanceAll() throws MDTInstanceManagerException {
		for ( MDTInstance inst: getInstanceAll() ) {
			try {
				removeInstance(inst.getId());
			}
			catch ( Exception e ) {
				getLogger().warn("failed to remove MDTInstance: id={}, cause={}", inst.getId(), "" + e);
			}
		}
	}
	
	/**
	 * JpaInstanceDescriptor를 생성하고 데이터베이스에 추가한다.
	 * <p>
	 * 이 메소드는 각 MDTInstanceManager 구현체 ({@link ExternalInstanceManager}, {@link JarInstanceManager},
	 * {@link DockerInstanceManager})에서 호출된다.
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
			throw new ResourceAlreadyExistsException("MDTInstance", "id=" + desc.getInstanceId());
		}
		catch ( DataIntegrityViolationException e ) {
			Throwable cause = e.getMostSpecificCause();
			if ( cause instanceof SQLException sqlError ) {
				String sqlState = sqlError.getSQLState();
				// "23505"는 PostgreSQL에서 고유 제약 조건 위반을 나타내는 SQL 상태 코드이다(이 매니저는
				// PostgreSQL 전용이다). id 중복은 addInstance가 먼저 확인하므로(checkNotRegistered) 여기서는 대부분
				// 다른 인스턴스와 AAS id가 겹친 경우다. AAS id가 겹치면 aasId로, 아니면 id로
				// 보고한다.
				if ( "23505".equals(sqlState) ) {
					if ( m_repos.instances().findByAasId(desc.getAasId()).isPresent() ) {
						throw new ResourceAlreadyExistsException("MDTInstance", "aasId=" + desc.getAasId());
					}
					throw new ResourceAlreadyExistsException("MDTInstance", "id=" + desc.getInstanceId());
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
		return FStream.from(m_repos.submodels().findAllByInstance_InstanceId(instId))
						.map(JpaMDTSubmodelDescriptor::getAASSubmodelDescriptor)
						.toList();
	}

	public List<MDTSubmodelDescriptor> getMDTSubmodelDescriptorAll(String instId) {
		JpaInstanceDescriptor instDesc = getInstanceDescriptor(instId);
		String endpointPrefix = instDesc.getBaseEndpoint();
		
		List<JpaMDTSubmodelDescriptor> jpaDescList = m_repos.submodels().findAllByInstance_InstanceId(instId);
		return FStream.from(jpaDescList)
						.map(smDesc -> {
							MDTSubmodelDescriptor desc = smDesc.toMDTSubmodelDescriptor();
							String endpoint = AASUtils.toSubmodelServiceEndpointString(endpointPrefix, desc.getId());
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
		
		String smEndpoint = AASUtils.toSubmodelServiceEndpointString(instDesc.getBaseEndpoint(), dataSmDesc.getId());
		
		List<JpaMDTParameterDescriptor> jpaDescList = m_repos.parameters().findAllByInstance_InstanceId(instId);
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
						.findByInstance_InstanceIdAndIdShort(instId, submodelIdShort)
						.orElse(null);
//						.orElseThrow(() -> new ResourceNotFoundException("MDTSubmodelDescriptor",
//																	"instanceId=" + instId + ", submodelIdShort=" + submodelIdShort));
	}

	public List<MDTOperationDescriptor> getMDTOperationDescriptorAll(String instId) {
		List<JpaMDTOperationDescriptor> jpaDescList = m_repos.operations().findAllByInstance_InstanceId(instId);
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
		return Nullables.orElse(m_logger, s_logger);
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = logger;
	}
	
	@Transactional
	/**
	 * 시작할 수 있는 인스턴스(STOPPED/FAILED)를 원자적으로 STARTING으로 바꾼다.
	 * <p>
	 * 같은 인스턴스를 동시에 두 번 시작하면 하나만 성공하고 나머지는 {@link InvalidResourceStatusException}을 받는다.
	 * 상태 교체는 DB의 compare-and-set으로 하므로, 검사와 교체 사이에 다른 요청이 끼어들 수 없다.
	 *
	 * @param id	인스턴스 id.
	 * @return	바꾸기 전 상태(STOPPED 또는 FAILED). 시작 요청이 실패하면 {@link #releaseStartClaim}에 넘긴다.
	 * @throws ResourceNotFoundException		인스턴스가 없는 경우.
	 * @throws InvalidResourceStatusException	시작할 수 없는 상태인 경우.
	 */
	public MDTInstanceStatus claimStart(String id) throws ResourceNotFoundException, InvalidResourceStatusException {
		// 판단은 실행 엔진의 실제 상태(adapt)로, 원자적 교체는 DB에 저장된 값을 기준으로 한다(docker는 둘이 다를 수
		// 있다). 조회한 엔티티는 트랜잭션 밖이라 adapt의 변경은 저장되지 않는다.
		JpaInstanceDescriptor desc = m_repos.instances().findByInstanceId(id)
												.orElseThrow(() -> newInstanceNotFoundException(id));
		MDTInstanceStatus stored = desc.getStatus();
		adaptInstanceDescriptor(desc);
		MDTInstanceStatus current = desc.getStatus();
		if ( (current == MDTInstanceStatus.STOPPED || current == MDTInstanceStatus.FAILED)
			&& m_repos.instances().compareAndSetStatus(id, stored, MDTInstanceStatus.STARTING) == 1 ) {
			return current;
		}
		// 그 사이 다른 요청이 상태를 바꿨을 수 있으므로 다시 읽어 알린다.
		throw new InvalidResourceStatusException("MDTInstance", "id=" + id,
												getInstanceDescriptor(id).getStatus());
	}
	
	/**
	 * {@link #claimStart}로 STARTING으로 바꾼 인스턴스의 시작 요청이 곧바로 실패했을 때 이전 상태로 되돌린다(그 사이
	 * 상태가 바뀌었으면 그대로 둔다).
	 */
	public void releaseStartClaim(String id, MDTInstanceStatus previous) {
		m_repos.instances().compareAndSetStatus(id, MDTInstanceStatus.STARTING, previous);
	}
	
	/**
	 * 인스턴스의 상태 전이를 기록한다: DB의 상태·endpoint를 갱신하고 {@link InstanceStatusChangeEvent}를 발행한다
	 * (MQTT 상태 발행 등 구독자에게 전달).
	 * <p>
	 * 모든 실행 백엔드(jar/docker/external)는 상태가 바뀌면 이 메소드 하나로 알린다. 그래서 DB 갱신과 이벤트 발행이
	 * 함께 일어난다. 인스턴스가 이미 삭제되어 DB 행이 없으면 이벤트만 발행한다.
	 *
	 * @param id		인스턴스 id.
	 * @param status	새 상태.
	 * @param endpoint	새 endpoint(RUNNING이 아니면 보통 {@code null}).
	 */
	public void transitionStatus(String id, MDTInstanceStatus status, @Nullable String endpoint) {
		try {
			updateInstanceDescriptor(id, status, endpoint);
		}
		catch ( ResourceNotFoundException e ) {
			getLogger().debug("no descriptor to update status (already removed?): id={}, status={}", id, status);
		}
		postStatusChanged(id, status, endpoint);
	}
	
	/**
	 * DB는 이미 갱신한 경우에 상태 변화 이벤트만 발행한다.
	 *
	 * @param id		인스턴스 id.
	 * @param status	새 상태.
	 * @param endpoint	새 endpoint.
	 */
	protected void postStatusChanged(String id, MDTInstanceStatus status, @Nullable String endpoint) {
		InstanceStatusChangeEvent ev = switch ( status ) {
			case STARTING -> InstanceStatusChangeEvent.STARTING(id);
			case RUNNING -> InstanceStatusChangeEvent.RUNNING(id, endpoint);
			case STOPPING -> InstanceStatusChangeEvent.STOPPING(id);
			case STOPPED -> InstanceStatusChangeEvent.STOPPED(id);
			case FAILED -> InstanceStatusChangeEvent.FAILED(id);
			case ADDING -> InstanceStatusChangeEvent.ADDING(id);
			case REMOVED -> InstanceStatusChangeEvent.REMOVED(id);
		};
		Globals.EVENT_BUS.post(ev);
	}
	
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
