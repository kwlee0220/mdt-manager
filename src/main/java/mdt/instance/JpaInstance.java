package mdt.instance;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.stream.FStream;

import mdt.instance.jpa.JpaInstanceDescriptor;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class JpaInstance extends AbstractInstance {
	private static final Logger s_logger = LoggerFactory.getLogger(JpaInstance.class);
	
	protected JpaInstance(AbstractJpaInstanceManager<? extends JpaInstance> manager,
							JpaInstanceDescriptor desc) {
		super(manager, desc);
		
		setLogger(s_logger);
	}
	
	@Override
	public JpaInstanceDescriptor getInstanceDescriptor() {
		return (JpaInstanceDescriptor)super.getInstanceDescriptor();
	}

	@Override
	public AssetAdministrationShellDescriptor getAASDescriptor() {
		return m_manager.toAssetAdministrationShellDescriptor(getInstanceDescriptor());
	}

	@Override
	public List<SubmodelDescriptor> getSubmodelDescriptorAll() {
		return FStream.from(getInstanceDescriptor().getSubmodels())
						.map(desc -> m_manager.toSubmodelDescriptor(desc))
						.toList();
	}

	public String getExecutionArguments() {
		return getInstanceDescriptor().getArguments();
	}
}
