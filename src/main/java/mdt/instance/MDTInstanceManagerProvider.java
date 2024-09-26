package mdt.instance;

import org.eclipse.digitaltwin.aas4j.v3.model.Environment;

import mdt.model.ServiceFactory;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface MDTInstanceManagerProvider extends MDTInstanceManager {
	public ServiceFactory getServiceFactory();
	
	public MDTInstanceStatus getInstanceStatus(String id);
	public String getInstanceServiceEndpoint(String id);
	
	public MDTInstance addInstance(String id, Environment env, String arguments)
		throws MDTInstanceManagerException;
}
