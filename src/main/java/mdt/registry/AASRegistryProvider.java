package mdt.registry;

import mdt.aas.AASRegistry;
import mdt.model.ResourceNotFoundException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface AASRegistryProvider extends AASRegistry {
	public String getJsonAssetAdministrationShellDescriptorById(String aasId)
		throws ResourceNotFoundException;
}
