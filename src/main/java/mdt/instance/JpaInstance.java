package mdt.instance;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.stream.FStream;

import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.instance.MDTOperationDescriptor;
import mdt.model.instance.MDTParameterDescriptor;
import mdt.model.instance.MDTSubmodelDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class JpaInstance extends AbstractInstance {
	private static final Logger s_logger = LoggerFactory.getLogger(JpaInstance.class);
	
	protected JpaInstance(AbstractJpaInstanceManager<? extends JpaInstance> manager, JpaInstanceDescriptor desc) {
		super(manager, desc.toInstanceDescriptor());
		
		setLogger(s_logger);
	}

	@Override
	public AssetAdministrationShellDescriptor getAASShellDescriptor() {
		AssetAdministrationShellDescriptor shell = m_manager.getAssetAdministrationShellDescriptor(getId());
		if ( getServiceEndpoint() != null ) {
			shell = AASUtils.attachEndpoint(shell, getServiceEndpoint());
		}
		return shell;
	}
	
	@Override
	public List<SubmodelDescriptor> getAASSubmodelDescriptorAll() {
		List<SubmodelDescriptor> descList = m_manager.getAASSubmodelDescriptorAll(getId());
		if ( getServiceEndpoint() != null ) {
			descList = FStream.from(descList)
								.map( smDesc -> AASUtils.attachEndpoint(smDesc, getServiceEndpoint()))
								.toList();
		}
		
		return descList;
	}

	public String getExecutionArguments() {
		return getJpaInstanceDescriptor().getArguments();
	}

	@Override
	public List<MDTSubmodelDescriptor> getMDTSubmodelDescriptorAll() {
		return m_manager.getMDTSubmodelDescriptorAll(getId());
	}

	@Override
	public List<MDTParameterDescriptor> getMDTParameterDescriptorAll() {
		return m_manager.getMDTParameterDescriptorAll(getId());
	}

	@Override
	public List<MDTOperationDescriptor> getMDTOperationDescriptorAll() {
		return m_manager.getMDTOperationDescriptorAll(getId());
	}

	@Override
	public MDTTwinCompositionDescriptor getMDTTwinCompositionDescriptor() {
		return m_manager.getTwinCompositionDescriptor(getId());
	}
	
	protected JpaInstanceDescriptor getJpaInstanceDescriptor() {
		return m_manager.getJpaInstanceDescriptor(getId());
	}
}
