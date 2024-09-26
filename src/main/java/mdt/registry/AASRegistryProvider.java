package mdt.registry;

import mdt.model.ResourceNotFoundException;
import mdt.model.registry.AASRegistry;
import mdt.model.registry.RegistryException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface AASRegistryProvider extends AASRegistry {
	public String getJsonAssetAdministrationShellDescriptorById(String aasId)
		throws ResourceNotFoundException, RegistryException;
}
