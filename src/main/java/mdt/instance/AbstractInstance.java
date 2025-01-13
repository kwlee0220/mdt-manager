package mdt.instance;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javax.annotation.Nullable;

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
import utils.stream.FStream;

import mdt.model.DescriptorUtils;
import mdt.model.InvalidResourceStatusException;
import mdt.model.ModelValidationException;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.DefaultMDTInstanceInfo;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceInfo;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.service.AssetAdministrationShellService;
import mdt.model.service.MDTInstance;
import mdt.model.service.SubmodelService;
import mdt.model.sm.data.Data;
import mdt.model.sm.data.DefaultData;
import mdt.model.sm.info.DefaultInformationModel;
import mdt.model.sm.info.InformationModel;
import mdt.model.sm.simulation.DefaultSimulation;
import mdt.model.sm.simulation.Simulation;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractInstance implements MDTInstance, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractInstance.class);
	
	protected final AbstractInstanceManager m_manager;
	protected final AtomicReference<InstanceDescriptor> m_desc;
	private final AtomicReference<InformationModel> m_infoModel = new AtomicReference<>();
	private final AtomicReference<Data> m_data = new AtomicReference<>();
	private final AtomicReference<List<Simulation>> m_simulationList = new AtomicReference<>();
	private Logger m_logger;

	abstract public AssetAdministrationShellDescriptor getAASDescriptor();
	abstract public List<SubmodelDescriptor> getAllSubmodelDescriptors();
	abstract public void startAsync();
	abstract public void stopAsync();
	abstract protected MDTInstanceStatus reloadStatus();
	abstract protected void uninitialize() throws Throwable;
	
	protected AbstractInstance(AbstractInstanceManager<? extends AbstractInstance> manager,
								InstanceDescriptor desc) {
		Preconditions.checkNotNull(manager);
		Preconditions.checkNotNull(desc);
		
		m_manager = manager;
		m_desc = new AtomicReference<>(desc);
		
		setLogger(s_logger);
	}
	
	public InstanceDescriptor getInstanceDescriptor() {
		return m_desc.get();
	}

	@Override
	public String getId() {
		return m_desc.get().getId();
	}

	@Override
	public MDTInstanceStatus getStatus() {
		return reloadStatus();
	}

	@Override
	public String getBaseEndpoint() {
		return m_desc.get().getBaseEndpoint();
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

	@Override
	public MDTInstanceInfo getInfo() {
		return DefaultMDTInstanceInfo.builder(this).build();
	}

	@Override
	public void start(@Nullable Duration pollInterval, @Nullable Duration timeout)
		throws TimeoutException, InterruptedException, InvalidResourceStatusException, ExecutionException {
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
		if ( !Funcs.exists(getAllSubmodelDescriptors(), desc -> desc.getId().equals(submodelId)) ) {
			throw new ResourceNotFoundException("Submodel", "id=" + submodelId);
		}
		return toSubmodelService(submodelId);
	}

	@Override
	public SubmodelService getSubmodelServiceByIdShort(String submodelIdShort)
		throws InvalidResourceStatusException, ResourceNotFoundException {
		String submodelId = Funcs.findFirst(getAllSubmodelDescriptors(),
											desc -> submodelIdShort.equals(desc.getIdShort()))
								.map(SubmodelDescriptor::getId)
								.getOrThrow(() -> new ResourceNotFoundException("Submodel",
																				"idShort=" + submodelIdShort));
		return toSubmodelService(submodelId);
	}

	@Override
	public List<SubmodelService> getAllSubmodelServiceBySemanticId(String semanticId) {
		return FStream.from(getAllSubmodelDescriptorBySemanticId(semanticId))
						.map(desc -> toSubmodelService(desc.getId()))
						.toList();
	}

	@Override
	public List<SubmodelService> getAllSubmodelServices() {
		return FStream.from(getAllSubmodelDescriptors())
						.map(desc -> toSubmodelService(desc.getId()))
						.toList();
	}

	@Override
	public InformationModel getInformationModel() throws ResourceNotFoundException {
		return m_infoModel.updateAndGet(p -> FOption.getOrElse(p, this::loadInformationModel));
	}
	private InformationModel loadInformationModel() throws ResourceNotFoundException {
		List<SubmodelService> svcList = getAllSubmodelServiceBySemanticId(InformationModel.SEMANTIC_ID);
		if ( svcList.size() == 0 ) {
			throw new ResourceNotFoundException("InformationModel", "semanticId=" + InformationModel.SEMANTIC_ID);
		}
		else if ( svcList.size() > 1 ) {
			String msg = String.format("Too many InformationModel is found in MDTInstance: %s, count=%d",
										m_desc.get().getId(), svcList.size());
			throw new ModelValidationException(msg);
		}
		
		DefaultInformationModel infoModel = new DefaultInformationModel();
		infoModel.updateFromAasModel(svcList.get(0).getSubmodel());
		return infoModel;
	}

	@Override
	public Data getData() throws ResourceNotFoundException {
		return m_data.updateAndGet(p -> FOption.getOrElseThrow(p, this::loadData));
	}
	private Data loadData() throws ResourceNotFoundException {
		List<SubmodelService> found = getAllSubmodelServiceBySemanticId(Data.SEMANTIC_ID);
		SubmodelService dataSvc = Funcs.getFirst(found)
										.getOrThrow(() -> new ResourceNotFoundException("DataSubmodel",
																				"semanticId=" + Data.SEMANTIC_ID));
		DefaultData data = new DefaultData();
		data.updateFromAasModel(dataSvc.getSubmodel());
		return data;
	}

	@Override
	public List<Simulation> getAllSimulations() {
		return m_simulationList.updateAndGet(lst -> FOption.getOrElseThrow(lst, this::loadAllSimulations));
	}
	private List<Simulation> loadAllSimulations() {
		return FStream.from(getAllSubmodelServiceBySemanticId(Simulation.SEMANTIC_ID))
						.map(SubmodelService::getSubmodel)
						.map(submodel -> {
							DefaultSimulation sim = new DefaultSimulation();
							sim.updateFromAasModel(submodel);
							return (Simulation)sim;
						})
						.toList();
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
		StateChangePoller.pollWhile(() -> waitCond.test(reloadStatus()))
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
