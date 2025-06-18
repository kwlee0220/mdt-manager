package mdt.instance;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetKind;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import utils.LoggerSettable;
import utils.StateChangePoller;
import utils.func.FOption;
import utils.func.Funcs;
import utils.jpa.JpaSession;
import utils.stream.FStream;

import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AssetAdministrationShellService;
import mdt.model.DescriptorUtils;
import mdt.model.InvalidResourceStatusException;
import mdt.model.ResourceNotFoundException;
import mdt.model.SubmodelService;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.sm.data.Data;
import mdt.model.sm.data.DefaultDataInfo;
import mdt.model.sm.data.ParameterCollection;
import mdt.model.sm.info.MDTAssetType;

import jakarta.annotation.Nullable;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractInstance implements MDTInstance, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractInstance.class);
	
	protected final AbstractJpaInstanceManager<? extends AbstractInstance> m_manager;
	protected final AtomicReference<InstanceDescriptor> m_desc;
	private Logger m_logger;

	/**
	 * 본 MDTInstance의 AssetAdministrationShellDescriptor를 반환한다.
	 * <p>
	 * 이 메소드는 {@link AbstractInstance}를 상속받는 클래스에서 구현되어야 한다.
	 * 
	 * @return    AssetAdministrationShellDescriptor
	 */
	abstract public AssetAdministrationShellDescriptor getAASDescriptor();
	
	/**
	 * 본 MDTInstance에 속한 모든 SubmodelDescriptor를 반환한다.
	 * <p>
	 * 이 메소드는 {@link AbstractInstance}를 상속받는 클래스에서 구현되어야 한다.
	 * 
	 * @return    SubmodelDescriptor 리스트
	 */
	abstract public List<SubmodelDescriptor> getSubmodelDescriptorAll();
	
	/**
	 * 본 MDTInstance를 비동기적으로 시작시킨다.
	 * <p>
	 * 이 메소드는 MDTInstance를 시작시키고 바로 반환하기 때문에 메소드가 반환된 사실이
	 * 해당 MDTInstance가 시작되었음을 의미하지 않는다.
	 * MDTInstance의 시작 여부는 {@link #getStatus()} 메소드를 통해 확인해야 한다.
	 * <p>
	 * 이 메소드는 {@link AbstractInstance}를 상속받는 클래스에서 구현되어야 한다.
	 *
	 * @throws InterruptedException	시작 중에 쓰레드가 인터럽트된 경우.
	 */
	abstract public void startAsync() throws InterruptedException;
	
	/**
	 * 본 MDTInstance를 비동기적으로 중지시킨다.
	 * <p>
	 * 이 메소드는 MDTInstance를 중지시키고 바로 반환하기 때문에 메소드가 반환된 사실이
	 * 해당 MDTInstance가 중지되었음을 의미하지 않는다.
	 * MDTInstance의 중지 여부는 {@link #getStatus()} 메소드를 통해 확인해야 한다.
	 * <p>
	 * 이 메소드는 {@link AbstractInstance}를 상속받는 클래스에서 구현되어야 한다.
	 */
	abstract public void stopAsync();
	
	/**
	 * 본 MDTInstance에 해당하는 {@link InstanceDescriptor}를 읽어온다.
	 *
	 * @return   InstanceDescriptor
	 */
	protected InstanceDescriptor reloadInstanceDescriptor() {
		JpaInstanceDescriptor desc = m_manager.getInstanceDescriptor(getId());
		m_desc.set(desc);
		
		return desc;
	}
	
	/**
	 * 본 MDTInstance를 위해 사용된 자원을 해제한다.
	 *
	 * @throws Throwable
	 */
	abstract protected void uninitialize() throws Throwable;
	
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

	@Override
	public MDTAssetType getAssetType() {
		return m_desc.get().getAssetType();
	}

	@Override
	public AssetKind getAssetKind() {
		return m_desc.get().getAssetKind();
	}
	
	@Override
	public MDTInstanceStatus getStatus() {
		return m_desc.get().getStatus();
	}
	
	@Override
	public String getBaseEndpoint() {
		return m_desc.get().getBaseEndpoint();
	}
	
	private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

	@Override
	public void start(@Nullable Duration pollInterval, @Nullable Duration timeout)
		throws TimeoutException, InterruptedException, InvalidResourceStatusException, ExecutionException {
		MDTInstanceStatus status = getStatus(); 
		if ( status != MDTInstanceStatus.STOPPED && status != MDTInstanceStatus.FAILED ) {
			throw new InvalidResourceStatusException("MDTInstance", getId(), status);
		}
		
		startAsync();
		try {
			// MDTInstance가 시작될 때까지 대기한다.
			waitWhileStatus(state -> state == MDTInstanceStatus.STOPPED || state ==  MDTInstanceStatus.FAILED,
							DEFAULT_POLL_INTERVAL, timeout);
		}
		catch ( ExecutionException e ) {
			throw new MDTInstanceManagerException(e.getCause());
		}

		// MDTInstance를 시작시켜 놓고, 시작이 완료될 때까지 대기한다.
		status = getStatus();
		switch ( status ) {
			case RUNNING:
				return;
			case STARTING:
				if ( pollInterval != null ) {
					try {
						waitWhileStatus(state -> state == MDTInstanceStatus.STARTING, pollInterval, timeout);
					}
					catch ( ExecutionException e ) {
						throw new MDTInstanceManagerException(e.getCause());
					}
				}
				break;
			default:
				throw new InvalidResourceStatusException("MDTInstance", getId(), status);
		}
	}

	@Override
	public void stop(@Nullable Duration pollInterval, @Nullable Duration timeout)
		throws MDTInstanceManagerException, TimeoutException, InterruptedException, InvalidResourceStatusException {
		stopAsync();
		try {
			// MDTInstance의 종료 작업이 시작될 때까지 대기한다.
			waitWhileStatus(state -> state == MDTInstanceStatus.RUNNING, DEFAULT_POLL_INTERVAL, timeout);
		}
		catch ( ExecutionException e ) {
			throw new MDTInstanceManagerException(e.getCause());
		}
		
		MDTInstanceStatus status = getStatus();
		switch ( status ) {
			case STOPPED:
				return;
			case STOPPING:
				if ( pollInterval != null ) {
					try {
						waitWhileStatus(state -> state == MDTInstanceStatus.STOPPING, pollInterval, timeout);
					}
					catch ( ExecutionException e ) {
						throw new MDTInstanceManagerException(e.getCause());
					}
				}
				break;
			default:
				throw new InvalidResourceStatusException("MDTInstance", getId(), status);
		}
	}

	@Override
	public AssetAdministrationShellService getAssetAdministrationShellService()
		throws InvalidResourceStatusException {
		String instSvcEp = getBaseEndpoint();
		if ( instSvcEp == null ) {
			throw new InvalidResourceStatusException("MDTInstance", "id=" + getId(), getStatus());
		}
		
		String aasEp = DescriptorUtils.toAASServiceEndpointString(instSvcEp, getAasId());
		return m_manager.getServiceFactory().getAssetAdministrationShellService(aasEp);
	}

	@Override
	public SubmodelService getSubmodelServiceById(String submodelId)
		throws InvalidResourceStatusException, ResourceNotFoundException {
		if ( !Funcs.exists(getInstanceSubmodelDescriptorAll(), desc -> desc.getId().equals(submodelId)) ) {
			throw new ResourceNotFoundException("Submodel", "id=" + submodelId);
		}
		return toSubmodelService(submodelId);
	}

	@Override
	public SubmodelService getSubmodelServiceByIdShort(String submodelIdShort)
		throws InvalidResourceStatusException, ResourceNotFoundException {
		String submodelId = Funcs.findFirst(getSubmodelDescriptorAll(),
											desc -> submodelIdShort.equals(desc.getIdShort()))
								.map(SubmodelDescriptor::getId)
								.getOrThrow(() -> new ResourceNotFoundException("Submodel",
																				"idShort=" + submodelIdShort));
		return toSubmodelService(submodelId);
	}

	@Override
	public List<SubmodelService> getSubmodelServiceAllBySemanticId(String semanticId) {
		return FStream.from(getInstanceSubmodelDescriptorAll())
						.filter(desc -> semanticId.equals(desc.getSemanticId()))
						.map(desc -> toSubmodelService(desc.getId()))
						.toList();
	}

	@Override
	public List<SubmodelService> getSubmodelServiceAll() {
		return FStream.from(getSubmodelDescriptorAll())
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
	public String getOutputLog() throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Logger getLogger() {
		return FOption.getOrElse(m_logger, s_logger);
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
		else if ( this == null || getClass() != obj.getClass() ) {
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
								getId(), getAasId(), getBaseEndpoint(), getStatus());
	}
	
	public void waitWhileStatus(Predicate<MDTInstanceStatus> waitCond, Duration pollInterval, Duration timeout)
		throws TimeoutException, InterruptedException, ExecutionException {
		StateChangePoller.pollWhile(() -> {
							try (JpaSession session = m_manager.allocateJpaSession()) {
								InstanceDescriptor desc = reloadInstanceDescriptor();
								return waitCond.test(desc.getStatus());
							}
						})
						.pollInterval(pollInterval)
						.timeout(timeout)
						.build()
						.run();
	}
	
	private SubmodelService toSubmodelService(String submodelId) {
		String instSvcEp = getBaseEndpoint();
		if ( instSvcEp == null ) {
			throw new InvalidResourceStatusException("MDTInstance", "id=" + getId(), getStatus());
		}
		
		String smEp = DescriptorUtils.toSubmodelServiceEndpointString(instSvcEp, submodelId);
		return m_manager.getServiceFactory().getSubmodelService(smEp);
	}
}
