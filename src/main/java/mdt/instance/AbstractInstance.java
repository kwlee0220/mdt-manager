package mdt.instance;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetKind;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import utils.StateChangePoller;
import utils.stream.FStream;

import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.DescriptorUtils;
import mdt.model.InvalidResourceStatusException;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.service.AssetAdministrationShellService;
import mdt.model.service.SubmodelService;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractInstance implements MDTInstance {
	@SuppressWarnings("unused")
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractInstance.class);
	
	protected final AbstractInstanceManager<? extends AbstractInstance> m_manager;
	protected final AtomicReference<JpaInstanceDescriptor> m_desc;

	public abstract void startAsync();
	public abstract void stopAsync();
	protected abstract void uninitialize() throws Throwable;
	
	protected AbstractInstance(AbstractInstanceManager<? extends AbstractInstance> manager,
								JpaInstanceDescriptor desc) {
		Preconditions.checkNotNull(manager);
		Preconditions.checkNotNull(desc);
		
		m_manager = manager;
		m_desc = new AtomicReference<>(desc);
	}
	
	public JpaInstanceDescriptor getInstanceDescriptor() {
		return m_desc.get();
	}
	
	public File getWorkspaceDir() {
		return m_manager.getInstanceHomeDir(getId());
	}

	@Override
	public String getId() {
		return m_desc.get().getId();
	}

	@Override
	public MDTInstanceStatus getStatus() {
		return m_desc.get().getStatus();
	}

	@Override
	public String getBaseEndpoint() {
		return m_desc.get().getBaseEndpoint();
	}

	public String getExecutionArguments() {
		return m_desc.get().getArguments();
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
	public String getAssetType() {
		return m_desc.get().getAssetType();
	}

	@Override
	public AssetKind getAssetKind() {
		return m_desc.get().getAssetKind();
	}
	
	public File getInstanceWorkspaceDir() {
		return m_manager.getInstanceHomeDir(getId());
	}

	@Override
	public void start(@Nullable Duration pollInterval, @Nullable Duration timeout)
		throws MDTInstanceManagerException, TimeoutException, InterruptedException, InvalidResourceStatusException {
		MDTInstanceStatus status = m_desc.get().getStatus(); 
		if ( status != MDTInstanceStatus.STOPPED && status != MDTInstanceStatus.FAILED ) {
			throw new InvalidResourceStatusException("MDTInstance", getId(), status);
		}
		
		startAsync();
		switch ( m_desc.get().getStatus() ) {
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
				throw new InvalidResourceStatusException("MDTInstance", getId(), getStatus());
		}
	}

	@Override
	public void stop(@Nullable Duration pollInterval, @Nullable Duration timeout)
		throws MDTInstanceManagerException, TimeoutException, InterruptedException, InvalidResourceStatusException {
		MDTInstanceStatus status = m_desc.get().getStatus(); 
		if ( status != MDTInstanceStatus.RUNNING) {
			throw new InvalidResourceStatusException("MDTInstance", getId(), status);
		}
		
		stopAsync();
		switch ( m_desc.get().getStatus() ) {
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
				throw new InvalidResourceStatusException("MDTInstance", getId(), getStatus());
		}
	}
	
	public AssetAdministrationShellDescriptor getAASDescriptor() {
		return m_desc.get().toAssetAdministrationShellDescriptor();
	}

	@Override
	public List<SubmodelDescriptor> getAllSubmodelDescriptors() {
		return FStream.from(m_desc.get().getSubmodels())
						.map(ismd -> ismd.getInstance().toSubmodelDescriptor(ismd))
						.toList();
	}

	public SubmodelDescriptor getSubmodelDescriptorById(String id) {
		return FStream.from(m_desc.get().getSubmodels())
						.filter(d -> d.getId().equals(id))
						.map(ismd -> ismd.getInstance().toSubmodelDescriptor(ismd))
						.findFirst()
						.getOrThrow(() -> new ResourceNotFoundException("Submodel", "id=" + id));
	}

	public SubmodelDescriptor getSubmodelDescriptorByIdShort(String idShort) {
		return FStream.from(m_desc.get().getSubmodels())
						.filter(d -> idShort.equals(d.getId()))
						.map(ismd -> ismd.getInstance().toSubmodelDescriptor(ismd))
						.findFirst()
						.getOrThrow(() -> new ResourceNotFoundException("Submodel", "idShort=" + idShort));
	}

	public SubmodelDescriptor getSubmodelDescriptorBySemanticId(String semanticId) {
		return FStream.from(m_desc.get().getSubmodels())
						.filter(d -> semanticId.equals(d.getSemanticId()))
						.map(ismd -> ismd.getInstance().toSubmodelDescriptor(ismd))
						.findFirst()
						.getOrThrow(() -> new ResourceNotFoundException("Submodel", "semanticId=" + semanticId));
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

	public SubmodelService getSubmodelServiceById(String submodelId)
		throws InvalidResourceStatusException, ResourceNotFoundException {
		if ( !FStream.from(m_desc.get().getSubmodels())
					.exists(desc -> submodelId.equals(desc.getId())) ) {
			throw new ResourceNotFoundException("Submodel", "id=" + submodelId);
		}
		
		String instSvcEp = getBaseEndpoint();
		if ( instSvcEp == null ) {
			throw new InvalidResourceStatusException("MDTInstance", "id=" + getId(), getStatus());
		}
		
		String smEp = DescriptorUtils.toSubmodelServiceEndpointString(instSvcEp, submodelId);
		return m_manager.getServiceFactory().getSubmodelService(smEp);
	}

	public SubmodelService getSubmodelServiceByIdShort(String submodelIdShort)
		throws InvalidResourceStatusException, ResourceNotFoundException {
		String submodelId = FStream.from(m_desc.get().getSubmodels())
									.findFirst(desc -> submodelIdShort.equals(desc.getIdShort()))
									.map(desc -> desc.getId())
									.getOrThrow(() -> new ResourceNotFoundException("Submodel",
																				"idShort=" + submodelIdShort));
		
		String instSvcEp = getBaseEndpoint();
		if ( instSvcEp == null ) {
			throw new InvalidResourceStatusException("MDTInstance", "id=" + getId(), getStatus());
		}
		
		String smEp = DescriptorUtils.toSubmodelServiceEndpointString(instSvcEp, submodelId);
		return m_manager.getServiceFactory().getSubmodelService(smEp);
	}

	@Override
	public SubmodelService getSubmodelServiceBySemanticId(String semanticId) {
		SubmodelDescriptor smDesc = getSubmodelDescriptorBySemanticId(semanticId);
		
		String instSvcEp = getBaseEndpoint();
		if ( instSvcEp == null ) {
			throw new InvalidResourceStatusException("MDTInstance", "id=" + getId(), getStatus());
		}
		
		String smEp = DescriptorUtils.toSubmodelServiceEndpointString(instSvcEp, smDesc.getId());
		return m_manager.getServiceFactory().getSubmodelService(smEp);
	}

	@Override
	public List<SubmodelService> getAllSubmodelServices() {
		String instSvcEp = getBaseEndpoint();
		if ( instSvcEp == null ) {
			throw new InvalidResourceStatusException("MDTInstance", "id=" + getId(), getStatus());
		}
		
		return FStream.from(m_desc.get().getSubmodels())
						.map(instSmDesc -> {
							String smEp = DescriptorUtils.toSubmodelServiceEndpointString(instSvcEp, instSmDesc.getId());
							return m_manager.getServiceFactory().getSubmodelService(smEp);
						})
						.toList();
	}
	
	public AbstractInstance reload() {
		JpaInstanceDescriptor newDesc = m_manager.getEntityManager()
												.find(JpaInstanceDescriptor.class, m_desc.get().getRowId());
		m_desc.set(newDesc);
		
		return this;
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
		return Objects.equal(getId(), other.getId());
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
	
	protected void update(Consumer<JpaInstanceDescriptor> updater) {
		m_manager.update(m_desc.get().getRowId(), updater);
	}
	
	@SuppressWarnings("unchecked")
	protected <T extends AbstractInstanceManager<? extends AbstractInstance>> T getInstanceManager() {
		return (T)m_manager;
	}
	
	public void waitWhileStatus(Predicate<MDTInstanceStatus> waitCond, Duration pollInterval, Duration timeout)
		throws TimeoutException, InterruptedException, ExecutionException {
		StateChangePoller.pollWhile(() -> waitCond.test(reload().getStatus()))
						.interval(pollInterval)
						.timeout(timeout)
						.build()
						.run();
	}
}
