package mdt.instance;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import utils.LoggerSettable;
import utils.async.PeriodicPoller;
import utils.func.FOption;
import utils.func.Nullables;
import utils.func.Funcs;
import utils.stream.FStream;

import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.AssetAdministrationShellService;
import mdt.model.DescriptorUtils;
import mdt.model.InvalidResourceStatusException;
import mdt.model.ResourceNotFoundException;
import mdt.model.SubmodelService;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.instance.MDTTwinCompositionDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor.MDTCompositionDependency;
import mdt.model.instance.MDTTwinCompositionDescriptor.MDTCompositionItem;
import mdt.model.sm.data.Data;
import mdt.model.sm.data.DefaultDataInfo;
import mdt.model.sm.data.ParameterCollection;
import mdt.model.sm.info.MDTAssetType;


/**
 * MDT 매니저 서버 측에서 동작하는 {@link MDTInstance} 추상 구현체이다.
 * <p>
 * 본 클래스는 인스턴스 생명주기(시작/중지)와 상태 polling, AAS/Submodel 서비스 생성, 의존성 기반
 * 인접 인스턴스 조회 등 매니저 공통 로직을 제공한다. 실제 인스턴스 실행 환경에 따라 달라지는
 * 동작(예: 외부 프로세스 시작/중지, AAS/Submodel 디스크립터 산출, 자원 정리)은 추상 메서드로
 * 위임되며 구체 서브클래스에서 구현되어야 한다.
 * <p>
 * {@link InstanceDescriptor}는 {@link AtomicReference}로 보관되어 thread-safe하게 갱신/조회된다.
 * 상태 polling은 {@link #waitWhileStatus(Predicate, Duration, Duration)}에 의해 일정 간격으로
 * 최신 디스크립터를 다시 로드하면서 수행된다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractInstance implements MDTInstance, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractInstance.class);
	private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);
	/**
	 * 시작·중지 요청이 받아들여져 상태가 바뀌기까지(STOPPED→STARTING, RUNNING→STOPPING) 기다리는 최대 시간.
	 * 호출자의 {@code timeout}이 없거나 이보다 길어도 이 단계는 이 시간을 넘기지 않는다.
	 */
	static final Duration DEFAULT_SUBMIT_TIMEOUT = Duration.ofSeconds(30);

	protected final AbstractJpaInstanceManager<? extends AbstractInstance> m_manager;
	protected final AtomicReference<InstanceDescriptor> m_desc;
	private Logger m_logger;

	/**
	 * 본 MDTInstance의 {@link AssetAdministrationShellDescriptor}를 반환한다.
	 * <p>
	 * 본 메서드는 {@link AbstractInstance}를 상속받는 클래스에서 구현되어야 한다.
	 *
	 * @return {@link AssetAdministrationShellDescriptor} 객체.
	 */
	abstract public AssetAdministrationShellDescriptor getAASShellDescriptor();

	/**
	 * 본 MDTInstance에 속한 모든 {@link SubmodelDescriptor} 리스트를 반환한다.
	 * <p>
	 * 본 메서드는 {@link AbstractInstance}를 상속받는 클래스에서 구현되어야 한다.
	 *
	 * @return {@link SubmodelDescriptor} 객체 리스트.
	 */
	abstract public List<SubmodelDescriptor> getAASSubmodelDescriptorAll();
	
	/**
	 * 본 MDTInstance를 비동기적으로 시작시킨다.
	 * <p>
	 * 본 메서드는 시작을 요청하고 바로 반환하므로, 반환된 사실이 해당 MDTInstance가 실제로
	 * 시작되었음을 의미하지 않는다. 시작 완료 여부는 {@link #getStatus()}를 통해 확인해야 한다.
	 * <p>
	 * 본 메서드는 {@link AbstractInstance}를 상속받는 클래스에서 구현되어야 한다.
	 *
	 * @throws InterruptedException	시작 요청 중 쓰레드가 인터럽트된 경우.
	 */
	abstract public void startAsync() throws InterruptedException;

	/**
	 * 본 MDTInstance를 비동기적으로 중지시킨다.
	 * <p>
	 * 본 메서드는 중지를 요청하고 바로 반환하므로, 반환된 사실이 해당 MDTInstance가 실제로
	 * 중지되었음을 의미하지 않는다. 중지 완료 여부는 {@link #getStatus()}를 통해 확인해야 한다.
	 * <p>
	 * 본 메서드는 {@link AbstractInstance}를 상속받는 클래스에서 구현되어야 한다.
	 */
	abstract public void stopAsync();
	
	/**
	 * 본 MDTInstance에 해당하는 {@link InstanceDescriptor}를 JPA 저장소에서 다시 읽어와
	 * 내부 캐시를 갱신하고 반환한다.
	 *
	 * @return 새로 읽어온 {@link InstanceDescriptor}.
	 */
	protected InstanceDescriptor reloadInstanceDescriptor() {
		JpaInstanceDescriptor jpaDesc = m_manager.getInstanceDescriptor(getId());
		InstanceDescriptor desc = jpaDesc.toInstanceDescriptor();
		m_desc.set(desc);

		return desc;
	}

	/**
	 * 본 MDTInstance를 위해 사용된 자원을 해제한다.
	 * <p>
	 * 본 메서드는 {@link AbstractInstance}를 상속받는 클래스에서 구현되어야 한다.
	 *
	 * @throws Exception 자원 해제 과정에서 오류가 발생한 경우.
	 */
	abstract protected void uninitialize() throws Exception;

	/**
	 * 주어진 매니저와 초기 {@link InstanceDescriptor}로 {@link AbstractInstance}를 생성한다.
	 *
	 * @param manager	본 인스턴스가 속한 {@link AbstractJpaInstanceManager}.
	 * @param desc		초기 {@link InstanceDescriptor}.
	 * @throws IllegalArgumentException	{@code manager} 또는 {@code desc}가 {@code null}인 경우.
	 */
	protected AbstractInstance(AbstractJpaInstanceManager<? extends AbstractInstance> manager,
								InstanceDescriptor desc) {
		Preconditions.checkArgument(manager != null, "AbstractJpaInstanceManager is null");
		Preconditions.checkArgument(desc != null, "InstanceDescriptor is null");

		m_manager = manager;
		m_desc = new AtomicReference<>(desc);

		setLogger(s_logger);
	}

	@Override
	public AbstractJpaInstanceManager<? extends AbstractInstance> getInstanceManager() {
		return m_manager;
	}
	
	@Override
	public InstanceDescriptor getInstanceDescriptor() {
		return m_desc.get();
	}

	@Override
	public String getId() {
		return m_desc.get().getId();
	}

	@Override
	public String getAasId() {
		return m_desc.get().getAasId();
	}

	@Override
	public String getAasIdShort() {
		return m_desc.get().getAasIdShort();
	}

	@Override
	public String getGlobalAssetId() {
		return m_desc.get().getGlobalAssetId();
	}

	// 마감 시각까지 남은 시간. 마감이 없으면 null(무한), 지났으면 1ms(곧바로 TimeoutException).
	private static @Nullable Duration remaining(long deadline) {
		if ( deadline == Long.MAX_VALUE ) {
			return null;
		}
		return Duration.ofNanos(Math.max(deadline - System.nanoTime(), 1_000_000L));
	}

	// 요청이 받아들여지는 단계의 제한 시간: 호출자의 timeout과 DEFAULT_SUBMIT_TIMEOUT 중 짧은 것.
	static Duration toSubmitTimeout(@Nullable Duration timeout) {
		return ( timeout == null || timeout.compareTo(DEFAULT_SUBMIT_TIMEOUT) > 0 )
				? DEFAULT_SUBMIT_TIMEOUT : timeout;
	}

	@Override
	public MDTAssetType getAssetType() {
		return m_desc.get().getAssetType();
	}
	
	@Override
	public MDTInstanceStatus getStatus() {
		return m_desc.get().getStatus();
	}
	
	@Override
	public String getServiceEndpoint() {
		return m_desc.get().getBaseEndpoint();
	}
	
	/**
	 * {@inheritDoc}
	 * <p>
	 * 현재 상태가 {@link MDTInstanceStatus#STOPPED} 또는 {@link MDTInstanceStatus#FAILED}일 때만 시작을
	 * 허용하며, 그 외의 상태에서는 {@link InvalidResourceStatusException}을 던진다. 시작 요청 후 내부
	 * 기본 polling 주기 (500ms) 로 {@code STOPPED → STARTING} 전환을 먼저 확인하고,
	 * {@code pollInterval}이 지정된 경우 그 주기로 {@code STARTING} 상태에서 벗어날 때까지 추가 대기한다.
	 * <p>
	 * 첫 단계({@code STOPPED → STARTING})는 {@code timeout}이 {@code null}이어도 {@link #DEFAULT_SUBMIT_TIMEOUT}을
	 * 넘겨 기다리지 않는다(넘으면 {@link TimeoutException}). 2026-11-05 이전에는 {@code timeout}이 {@code null}이면
	 * 시작 요청이 상태를 바꾸지 못할 때 영원히 기다렸다({@code PUT /instances/{id}/start}와 등록 시 auto-start가
	 * {@code start(null, null)}을 부른다).
	 */
	@Override
	public MDTInstanceStatus start(@Nullable Duration pollInterval, @Nullable Duration timeout)
		throws TimeoutException, InterruptedException, InvalidResourceStatusException, ExecutionException {
		// 시작할 수 있는 상태(STOPPED/FAILED)를 DB에서 원자적으로 STARTING으로 바꾼다. 동시에 두 번 시작하면
		// 하나만 통과한다. 2026-11-05 이전에는 캐시된 상태로 검사한 뒤 무조건 STOPPED로 덮어썼고, 그 뒤
		// STOPPED→STARTING 전이를 따로 기다리느라 timeout을 두 단계가 각자 써서 실제로는 두 배까지 기다렸다(D36).
		MDTInstanceStatus previous = getInstanceManager().claimStart(getId());
		try {
			startAsync();
		}
		catch ( Exception e ) {
			// 시작 요청 자체가 실패했으면(예: 지원하지 않는 백엔드) 원래 상태로 되돌린다.
			getInstanceManager().releaseStartClaim(getId(), previous);
			throw e;
		}
		
		// pollInterval이 없으면 시작 요청 후 바로 반환한다(상태는 STARTING).
		if ( pollInterval == null ) {
			return reloadInstanceDescriptor().getStatus();
		}

		// STARTING 상태가 바뀔 때까지 시작 완료를 대기(제한 시간은 timeout 하나).
		return waitWhileStatus(state -> state == MDTInstanceStatus.STARTING, pollInterval, timeout);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * 현재 상태가 {@link MDTInstanceStatus#STARTING} 또는 {@link MDTInstanceStatus#RUNNING}일 때만 중지를
	 * 허용하며, 그 외의 상태에서는 {@link InvalidResourceStatusException}을 던진다. 종료 요청 후 내부
	 * 기본 polling 주기 (500ms) 로 {@code RUNNING → STOPPING} 전환을 먼저 확인하고,
	 * {@code pollInterval}이 지정된 경우 그 주기로 {@code STOPPING} 상태에서 벗어날 때까지 추가 대기한다.
	 * 종료 폴링 후 상태가 {@code STOPPING}/{@code STOPPED}가 아니면 {@link MDTInstanceManagerException}을 던진다.
	 */
	@Override
	public MDTInstanceStatus stop(@Nullable Duration pollInterval, @Nullable Duration timeout)
		throws MDTInstanceManagerException, TimeoutException, InterruptedException, InvalidResourceStatusException {
		// 캐시가 아니라 현재 상태로 검사한다(2026-11-05 이전에는 인스턴스 객체를 만들 때의 상태로 검사했다).
		MDTInstanceStatus status = reloadInstanceDescriptor().getStatus();
		if ( status != MDTInstanceStatus.STARTING && status != MDTInstanceStatus.RUNNING ) {
			throw new InvalidResourceStatusException("MDTInstance", getId(), status);
		}
		
		// 두 단계 대기가 마감 시각 하나를 나눠 쓴다(2026-11-05 이전에는 단계마다 timeout을 새로 써서 최대 두 배).
		final long deadline = ( timeout != null ) ? System.nanoTime() + timeout.toNanos() : Long.MAX_VALUE;
		stopAsync();
		try {
			// MDTInstance의 종료 작업이 시작될 때까지 대기한다.
			status = waitWhileStatus(state -> state == MDTInstanceStatus.RUNNING, DEFAULT_POLL_INTERVAL,
									toSubmitTimeout(remaining(deadline)));
		}
		catch ( ExecutionException e ) {
			throw new MDTInstanceManagerException(e.getCause());
		}
		
		if ( status != MDTInstanceStatus.STOPPING && status != MDTInstanceStatus.STOPPED ) {
			throw new MDTInstanceManagerException("Unexpected status after stopAsync: " + status);
		}
		if ( pollInterval == null || status != MDTInstanceStatus.STOPPING ) {
			return status;
		}
		
		try {
			return waitWhileStatus(state -> state == MDTInstanceStatus.STOPPING, pollInterval, remaining(deadline));
		}
		catch ( ExecutionException e ) {
			throw new MDTInstanceManagerException(e.getCause());
		}
	}

	@Override
	public AssetAdministrationShellService getAssetAdministrationShellService() throws InvalidResourceStatusException {
		String instSvcEp = getServiceEndpoint();
		if ( instSvcEp == null ) {
			throw new InvalidResourceStatusException("MDTInstance", "id=" + getId(), getStatus());
		}
		
		String aasEp = DescriptorUtils.toAASServiceEndpointString(instSvcEp, getAasId());
		return m_manager.getServiceFactory().getAssetAdministrationShellService(aasEp);
	}

	@Override
	public FOption<SubmodelService> getSubmodelServiceById(String submodelId) throws InvalidResourceStatusException {
		var smDesc = Funcs.findFirst(getMDTSubmodelDescriptorAll(),
									desc -> desc.getId().equals(submodelId));
		return FOption.ofNullable(smDesc)
						.map(desc -> toSubmodelService(desc.getId()));
	}

	@Override
	public FOption<SubmodelService> getSubmodelServiceByIdShort(String submodelIdShort)
		throws InvalidResourceStatusException {
		var smDesc = Funcs.findFirst(getMDTSubmodelDescriptorAll(),
									desc -> submodelIdShort.equals(desc.getIdShort()));
		
		return FOption.ofNullable(smDesc)
						.map(desc -> toSubmodelService(desc.getId()));
	}

	@Override
	public List<SubmodelService> getSubmodelServiceAllBySemanticId(String semanticId) {
		return FStream.from(getMDTSubmodelDescriptorAll())
						.filter(desc -> semanticId.equals(desc.getSemanticId()))
						.map(desc -> toSubmodelService(desc.getId()))
						.toList();
	}

	@Override
	public List<SubmodelService> getSubmodelServiceAll() {
		return FStream.from(getMDTSubmodelDescriptorAll())
						.map(desc -> toSubmodelService(desc.getId()))
						.toList();
	}
	
	@Override
	public ParameterCollection getParameterCollection() {
		SubmodelService svc = FStream.from(getSubmodelServiceAllBySemanticId(Data.SEMANTIC_ID))
									.findFirst()
									.getOrThrow(() -> new ResourceNotFoundException("Submodel",
																				"semanticId=" + Data.SEMANTIC_ID));
		DefaultDataInfo dataInfo = new DefaultDataInfo();
		dataInfo.updateFromAasModel(svc.getSubmodelElementByPath("DataInfo"));
		if ( dataInfo.isEquipment() ) {
			return dataInfo.getEquipment();
		}
		else if ( dataInfo.isOperation() ) {
			return dataInfo.getOperation();
		}
		else {
			throw new ResourceNotFoundException("ParameterCollection", "id=" + getId());
		}
	}
	
	@Override
	public List<MDTInstance> getTargetInstanceAllOfDependency(String depType) {
		return findInstanceAllOfDependency(depType, MDTCompositionDependency::getSourceItem,
											MDTCompositionDependency::getTargetItem);
	}

	@Override
	public List<MDTInstance> getSourceInstanceAllOfDependency(String depType) {
		return findInstanceAllOfDependency(depType, MDTCompositionDependency::getTargetItem,
											MDTCompositionDependency::getSourceItem);
	}

	/**
	 * 본 인스턴스가 {@code myEnd} 쪽인 {@code depType} 의존 관계의 {@code otherEnd} 쪽 인스턴스들을 찾는다.
	 * <p>
	 * 찾지 못한 인스턴스는 결과에서 빼고 WARN으로 남긴다(2026-11-07 이전에는 흔적 없이 버렸다, 검토 I11).
	 */
	private List<MDTInstance> findInstanceAllOfDependency(String depType,
														Function<MDTCompositionDependency,String> myEnd,
														Function<MDTCompositionDependency,String> otherEnd) {
		MDTTwinCompositionDescriptor twinComp = m_manager.getTwinCompositionDescriptor(getId());
		Map<String, MDTCompositionItem> itemMap = FStream.from(twinComp.getCompositionItems())
																	.tagKey(MDTCompositionItem::getId)
																	.toMap();

		String myId = twinComp.getId();
		return FStream.from(twinComp.getCompositionDependencies())
						.filter(dep -> dep.getType().equals(depType) && myEnd.apply(dep).equals(myId))
						.mapNotNull(dep -> itemMap.get(otherEnd.apply(dep)))
						.map(MDTCompositionItem::getReference)
						.mapNotNull(this::findInstanceByAasIdOrWarn)
						.toList();
	}

	private @Nullable MDTInstance findInstanceByAasIdOrWarn(String aasId) {
		try {
			return m_manager.getInstanceByAasId(aasId);
		}
		catch ( Exception e ) {
			getLogger().warn("failed to find a dependent MDTInstance: instance={}, aasId={}, cause={}",
							getId(), aasId, "" + e);
			return null;
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * 본 추상 구현은 출력 로그를 지원하지 않으며 호출 시 {@link UnsupportedOperationException}을 던진다.
	 * 로그 조회가 필요한 구체 서브클래스에서 오버라이드해야 한다.
	 */
	@Override
	public String getOutputLog() throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Logger getLogger() {
		return Nullables.orElse(m_logger, s_logger);
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = logger;
	}
	
	@Override
	public boolean equals(Object obj) {
		if ( this == obj ) {
			return true;
		}
		else if ( obj == null || getClass() != obj.getClass() ) {
			return false;
		}

		AbstractInstance other = (AbstractInstance)obj;
		return Objects.equals(getId(), other.getId());
	}
	
	@Override
	public int hashCode() {
		return Objects.hashCode(getId());
	}
	
	@Override
	public String toString() {
		return String.format("id=%s, aas=%s, base-endpoint=%s, status=%s",
								getId(), getAasId(), getServiceEndpoint(), getStatus());
	}
	
	/**
	 * MDTInstance 상태가 {@code waitCond}을 만족하는 동안 polling 대기한다.
	 * <p>
	 * 매 {@code pollInterval}마다 {@link #reloadInstanceDescriptor()}로 JPA 저장소에서 최신 상태를
	 * 다시 로드한 뒤 {@code waitCond}을 평가한다. 더 이상 만족하지 않게 되는 시점에 그 상태를 반환하며,
	 * {@code timeout}이 경과해도 만족 상태가 유지되면 {@link TimeoutException}이 발생한다.
	 *
	 * @param waitCond     대기 조건. {@code true}인 동안 polling이 계속된다.
	 * @param pollInterval Polling 간격. 이전 조회가 끝난 시점부터 대기하는 시간이다(fixed-delay).
	 * @param timeout      제한 기간. {@code null}이면 무한 대기.
	 * @return 대기 종료 시점의 상태.
	 * @throws TimeoutException     {@code timeout} 경과 시까지 대기 조건이 해제되지 않은 경우.
	 * @throws InterruptedException 대기 중 쓰레드가 인터럽트된 경우.
	 * @throws ExecutionException   상태 조회 중 오류가 발생한 경우.
	 */
	public MDTInstanceStatus waitWhileStatus(Predicate<MDTInstanceStatus> waitCond, Duration pollInterval,
											Duration timeout)
		throws TimeoutException, InterruptedException, ExecutionException {
		return PeriodicPoller.poll(() -> {
									InstanceDescriptor desc = reloadInstanceDescriptor();
									return waitCond.test(desc.getStatus()) ? null : desc.getStatus();
								})
								.fixedDelay(pollInterval)
								.timeout(timeout)
								.build()
								.run();
	}

	/**
	 * 주어진 submodel 식별자에 대한 {@link SubmodelService}를 생성한다.
	 * <p>
	 * 현재 인스턴스의 서비스 endpoint가 활성화되어 있지 않으면 {@link InvalidResourceStatusException}을 던진다.
	 *
	 * @param submodelId 대상 submodel 식별자.
	 * @return 대응되는 {@link SubmodelService}.
	 * @throws InvalidResourceStatusException 본 MDTInstance가 동작 중이 아니어서 endpoint를 사용할 수 없는 경우.
	 */
	private SubmodelService toSubmodelService(String submodelId) throws InvalidResourceStatusException {
		String instSvcEp = getServiceEndpoint();
		if ( instSvcEp == null ) {
			throw new InvalidResourceStatusException("MDTInstance", "id=" + getId(), getStatus());
		}

		String smEp = AASUtils.toSubmodelServiceEndpointString(instSvcEp, submodelId);
		return m_manager.getServiceFactory().getSubmodelService(smEp);
	}
}
