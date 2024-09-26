package mdt.instance;

import java.util.List;

import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager.InstanceDescriptorTransform;
import mdt.instance.jpa.JpaInstanceDescriptorManager.SearchCondition;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.instance.MDTInstanceManagerException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface InstanceDescriptorManager {
	public JpaInstanceDescriptor getInstanceDescriptor(String id) throws MDTInstanceManagerException;
	public List<JpaInstanceDescriptor> getInstanceDescriptorAll() throws MDTInstanceManagerException;
	public List<JpaInstanceDescriptor> findInstanceDescriptorAll(String filterExpr);

	public void addInstanceDescriptor(JpaInstanceDescriptor desc) throws MDTInstanceManagerException,
																		ResourceAlreadyExistsException;
	public void removeInstanceDescriptor(String id) throws MDTInstanceManagerException;
	public JpaInstanceDescriptor updateInstanceDescriptor(JpaInstanceDescriptor desc)
		throws MDTInstanceManagerException;
	
	public long count();

	public <S> List<S> query(SearchCondition cond, InstanceDescriptorTransform<S> transform);
}
