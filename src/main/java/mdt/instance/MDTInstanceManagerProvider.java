package mdt.instance;

import java.io.File;
import java.io.IOException;

import mdt.model.ModelValidationException;
import mdt.model.ServiceFactory;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface MDTInstanceManagerProvider extends MDTInstanceManager {
	public ServiceFactory getServiceFactory();
	
	public MDTInstanceStatus getInstanceStatus(String id);
	public String getInstanceServiceEndpoint(String id);
	
	public InstanceDescriptor addInstance(String id, int faaastPort, File bundleDir)
		throws ModelValidationException, IOException;
}
