package mdt.instance;

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.stream.FStream;

import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.InstanceDescriptor;


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
	protected InstanceDescriptor reloadInstanceDescriptor() {
		JpaInstanceDescriptor desc = m_manager.getEntityManager()
												.find(JpaInstanceDescriptor.class, asJpaInstanceDescriptor().getRowId());
		m_desc.set(desc);
		
		return desc;
	}

	@Override
	public AssetAdministrationShellDescriptor getAASDescriptor() {
		return asJpaInstanceDescriptor().toAssetAdministrationShellDescriptor();
	}

	@Override
	public List<SubmodelDescriptor> getSubmodelDescriptorAll() {
		return FStream.from(asJpaInstanceDescriptor().getSubmodels())
						.map(ismd -> ismd.getInstance().toSubmodelDescriptor(ismd))
						.toList();
	}

	public String getExecutionArguments() {
		return ((JpaInstanceDescriptor)m_desc.get()).getArguments();
	}
	
	protected JpaInstanceDescriptor asJpaInstanceDescriptor() {
		return (JpaInstanceDescriptor)m_desc.get();
	}
	
	protected void update(Consumer<JpaInstanceDescriptor> updater) {
		m_manager.update(asJpaInstanceDescriptor().getRowId(), updater);
	}
}
